import type { ChunkState, ChunkTelemetry, ChunkTelemetryScope, TelemetryPlayer, RecordedLayers } from './types';
import type { MapRect } from './heatmap';

type Change<T> = { tick: number; value: T | null };
type ChunkHistory = { dimension: string; x: number; z: number; changes: Change<number>[] };
export type ChunkObservation = { dimension: string; x: number; z: number; state: ChunkState };

function at<T>(changes: Change<T>[], tick: number): T | null {
  let left = 0, right = changes.length;
  while (left < right) {
    const middle = (left + right) >>> 1;
    if (changes[middle].tick <= tick) left = middle + 1; else right = middle;
  }
  return left ? changes[left - 1].value : null;
}
const key = (dimension: string, x: number, z: number) => `${dimension}:${x},${z}`;

/** alpha.23 v2 reports predate the independent flags; their existing capabilities remain authoritative. */
export function recordedLayers(raw: ChunkTelemetry | undefined, recording?: RecordedLayers): RecordedLayers {
  const explicit = raw?.capabilities.layers ?? recording;
  if (explicit) return explicit;
  const caps = raw?.capabilities;
  return { runtime: !!raw, generation: !!caps && caps.generationStatus !== 'not_recorded',
    ticketLevels: !!caps && caps.simulationLevel, ticketSources: !!caps && caps.tickets !== 'not_recorded',
    visibility: !!caps && caps.watchers !== 'not_recorded', players: !!raw };
}
function lowerBound<T>(values: T[], value: number, number: (entry: T) => number) {
  let left = 0, right = values.length;
  while (left < right) { const middle = (left + right) >>> 1;
    if (number(values[middle]) < value) left = middle + 1; else right = middle; }
  return left;
}

export function scopeContains(scope: ChunkTelemetryScope, dimension: string, x: number, z: number) {
  if (scope.dimension && scope.dimension !== dimension) return false;
  if (scope.minX !== undefined && x < scope.minX || scope.maxX !== undefined && x >= scope.maxX
    || scope.minZ !== undefined && z < scope.minZ || scope.maxZ !== undefined && z >= scope.maxZ) return false;
  if (scope.kind === 'radius' && scope.radius !== undefined && scope.centerX !== undefined && scope.centerZ !== undefined)
    return Math.hypot(x - scope.centerX, z - scope.centerZ) <= scope.radius;
  return true;
}

/** Indexed once per report. Seeking reads per-object histories, never replays the world. */
export function chunkTelemetryIndex(raw: ChunkTelemetry | undefined) {
  const unavailable = () => ({ available: false as const, reason: !raw ? 'Chunk telemetry not recorded'
    : raw.version !== 2 ? 'Unsupported chunk telemetry version' : 'Chunk telemetry unavailable' });
  if (!raw || raw.version !== 2 || raw.capturePoint !== 'server_tick_end'
    || !Array.isArray(raw.frames) || !Array.isArray(raw.states) || !Array.isArray(raw.ticketSources)
    || !Array.isArray(raw.watcherSets) || !Array.isArray(raw.directTicketSets) || !Array.isArray(raw.influenceSets) || !Array.isArray(raw.influences)
    || raw.influenceSets.some(ids => !Array.isArray(ids) || ids.some(id => !raw.influences[id]))
    || raw.states.some(state => !state || (state.watcherSet != null && !raw.watcherSets[state.watcherSet])
      || recordedLayers(raw).visibility && state.watcherSet == null
      || recordedLayers(raw).ticketSources && (state.directTicketSet == null || state.influenceSet == null
        || !raw.directTicketSets[state.directTicketSet] || !raw.influenceSets[state.influenceSet])
      || state.inViewSet != null && !raw.watcherSets[state.inViewSet]
      || state.pendingSendSet != null && !raw.watcherSets[state.pendingSendSet])
    || !raw.frames.length || !raw.frames[0].baseline || !raw.scope || !Array.isArray(raw.dimensions))
    return { ...unavailable(), data: null, dimensions: [] as string[], lastTick: 0,
      stateAt: (_tick: number) => unavailable(), chunksAt: (_dimension: string, _tick: number, _rect: MapRect) => [] as ChunkObservation[],
      playersAt: (_dimension: string, _tick: number) => [] as TelemetryPlayer[],
      bounds: (_dimension: string) => null as MapRect | null,
      scopeAt: (_tick: number) => null as ChunkTelemetryScope | null,
      chunkAt: (_dimension: string, _x: number, _z: number, _tick: number) => ({ ...unavailable(), chunk: null as ChunkObservation | null }) };

  // Sets are interned in the wire format. Resolve once and share their arrays;
  // a state never copies its watchers, native tickets or propagated candidates.
  const influenceSets = raw.influenceSets.map(ids => ids.map(id => raw.influences[id]));
  const states: ChunkState[] = raw.states.map(state => ({ ...state,
    watchers: state.watcherSet == null ? [] : raw.watcherSets[state.watcherSet] ?? [],
    inView: state.inViewSet == null ? undefined : raw.watcherSets[state.inViewSet],
    pendingSends: state.pendingSendSet == null ? undefined : raw.watcherSets[state.pendingSendSet],
    directTickets: state.directTicketSet === null ? [] : raw.directTicketSets[state.directTicketSet] ?? [],
    influencingTickets: state.influenceSet === null ? [] : influenceSets[state.influenceSet] ?? [] }));
  const decoded = { ...raw, states };

  const chunks = new Map<string, ChunkHistory>();
  const byDimension = new Map<string, { xs: number[]; columns: Map<number, ChunkHistory[]> }>();
  const players = new Map<string, TelemetryPlayer>();
  const playerFrames: Change<Map<string, TelemetryPlayer[]>>[] = [];
  const scopes: Change<ChunkTelemetryScope>[] = [];
  const boundsByDimension = new Map<string, MapRect>();
  function extend(dimension: string, minX: number, minZ: number, maxX: number, maxZ: number) {
    const previous = boundsByDimension.get(dimension);
    boundsByDimension.set(dimension, previous ? { minX: Math.min(previous.minX, minX), minZ: Math.min(previous.minZ, minZ),
      maxX: Math.max(previous.maxX, maxX), maxZ: Math.max(previous.maxZ, maxZ) } : { minX, minZ, maxX, maxZ });
  }
  let complete = raw.frames[0].tick - 1;
  let valid = true;
  for (const frame of raw.frames) {
    if (!Number.isSafeInteger(frame.tick) || frame.tick !== complete + 1 || frame.tick > raw.completeThroughTick) break;
    if (!scopes.length || frame.scope) scopes.push({ tick: frame.tick, value: frame.scope ?? raw.scope });
    if (frame.baseline && complete >= raw.frames[0].tick) {
      for (const history of chunks.values()) history.changes.push({ tick: frame.tick, value: null });
      players.clear();
    }
    for (const chunk of frame.chunks ?? []) {
      const name = raw.dimensions[chunk.dimension];
      if (!Number.isInteger(chunk.dimension) || !name || !Number.isInteger(chunk.x) || !Number.isInteger(chunk.z) || !states[chunk.state]) { valid = false; break; }
      const id = key(name, chunk.x, chunk.z);
      let history = chunks.get(id);
      if (!history) {
        history = { dimension: name, x: chunk.x, z: chunk.z, changes: [] };
        chunks.set(id, history);
        let dimension = byDimension.get(name);
        if (!dimension) { dimension = { xs: [], columns: new Map() }; byDimension.set(name, dimension); }
        const column = dimension.columns.get(chunk.x) ?? [];
        column.push(history); dimension.columns.set(chunk.x, column);
        extend(name, chunk.x * 16, chunk.z * 16, (chunk.x + 1) * 16, (chunk.z + 1) * 16);
      }
      history.changes.push({ tick: frame.tick, value: chunk.state });
    }
    if (!valid) break;
    for (const chunk of frame.removedChunks ?? []) chunks.get(key(raw.dimensions[chunk.dimension], chunk.x, chunk.z))?.changes.push({ tick: frame.tick, value: null });
    for (const player of frame.players ?? []) {
      players.set(player.uuid, player);
      extend(player.dimension, player.x, player.z, player.x + 1, player.z + 1);
    }
    for (const uuid of frame.removedPlayers ?? []) players.delete(uuid);
    if (frame.baseline || frame.players?.length || frame.removedPlayers?.length) {
      const dimensions = new Map<string, TelemetryPlayer[]>();
      for (const player of players.values()) { const list = dimensions.get(player.dimension) ?? [];
        list.push(player); dimensions.set(player.dimension, list); }
      playerFrames.push({ tick: frame.tick, value: dimensions });
    }
    const scope = frame.scope ?? at(scopes, frame.tick);
    if (scope) {
      const dimensions = scope.dimension ? [scope.dimension] : raw.dimensions;
      if (scope.minX !== undefined && scope.maxX !== undefined && scope.minZ !== undefined && scope.maxZ !== undefined)
        for (const dimension of dimensions) extend(dimension, scope.minX, scope.minZ, scope.maxX, scope.maxZ);
      else if (scope.kind === 'radius' && scope.centerX !== undefined && scope.centerZ !== undefined && scope.radius !== undefined)
        for (const dimension of dimensions) extend(dimension, scope.centerX - scope.radius, scope.centerZ - scope.radius,
          scope.centerX + scope.radius, scope.centerZ + scope.radius);
    }
    complete = frame.tick;
  }
  for (const dimension of byDimension.values()) {
    dimension.xs = [...dimension.columns.keys()].sort((a, b) => a - b);
    for (const column of dimension.columns.values()) column.sort((a, b) => a.z - b.z);
  }
  function stateAt(tick: number) {
    return tick >= raw!.frames[0].tick && tick <= complete
      ? { available: true as const, reason: '' }
      : { available: false as const, reason: tick > complete ? `State unavailable after tick ${complete}` : 'State not recorded at this tick' };
  }
  function scopeAt(tick: number) { return stateAt(tick).available ? at(scopes, tick) : null; }
  function chunkAt(dimension: string, x: number, z: number, tick: number) {
    const status = stateAt(tick);
    if (!status.available) return { ...status, chunk: null };
    const scope = scopeAt(tick)!;
    if (!raw!.dimensions.includes(dimension) || !scopeContains(scope, dimension, x, z))
      return { available: false as const, reason: 'Outside recorded scope', chunk: null };
    const cx = Math.floor(x / 16), cz = Math.floor(z / 16), history = chunks.get(key(dimension, cx, cz));
    const index = history ? at(history.changes, tick) : null;
    if (index === null && scope.kind === 'entity' && scope.minX === undefined)
      return { available: false as const, reason: 'Outside observed entity scope', chunk: null };
    return { available: true as const, reason: index === null ? 'No observed holder' : '',
      chunk: index === null ? null : { dimension, x: cx, z: cz, state: states[index] } };
  }
  function chunksAt(dimension: string, tick: number, rect: MapRect): ChunkObservation[] {
    if (!stateAt(tick).available) return [];
    const result: ChunkObservation[] = [];
    const spatial = byDimension.get(dimension);
    if (!spatial) return result;
    const minX = Math.floor(rect.minX / 16), minZ = Math.floor(rect.minZ / 16);
    for (let x = lowerBound(spatial.xs, minX, x => x); x < spatial.xs.length && spatial.xs[x] * 16 < rect.maxX; x++) {
      const column = spatial.columns.get(spatial.xs[x])!;
      for (let z = lowerBound(column, minZ, chunk => chunk.z); z < column.length && column[z].z * 16 < rect.maxZ; z++) {
        const history = column[z], index = at(history.changes, tick);
        if (index !== null) result.push({ dimension, x: history.x, z: history.z, state: states[index] });
      }
    }
    return result;
  }
  function playersAt(dimension: string, tick: number) {
    if (!stateAt(tick).available) return [];
    return at(playerFrames, tick)?.get(dimension) ?? [];
  }
  return { available: true as const, reason: '', data: decoded, dimensions: raw.dimensions, lastTick: complete,
    stateAt, scopeAt, chunkAt, chunksAt, playersAt, bounds: (dimension: string) => boundsByDimension.get(dimension) ?? null };
}
