import type { InstanceEntry } from './types';
import { instanceTiming, type ValueMode } from './profiling';

export type MapSource = 'all' | 'blocks' | 'entities';
export interface MapRect { minX: number; minZ: number; maxX: number; maxZ: number }
export const mapSizes = [1, 16, 512] as const;
export const mapNames = ['Block', 'Chunk', 'Region'];
export const minimumMerge = 2;
export const maximumMerge = 80;
export const defaultMerge = 16;
export function mergeThreshold(value: unknown) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(minimumMerge, Math.min(maximumMerge, parsed)) : defaultMerge;
}
export const cellKey = (x: number, z: number) => `${x},${z}`;
export const cellOrigin = (value: number, size: number) => Math.floor(value / size) * size;
export const mapCell = (x: number, z: number, size: number): MapRect => ({
  minX: cellOrigin(x, size), minZ: cellOrigin(z, size),
  maxX: cellOrigin(x, size) + size, maxZ: cellOrigin(z, size) + size
});
export function mapSelectionRect(fromX: number, fromZ: number, toX: number, toZ: number, size: number): MapRect {
  const from = mapCell(fromX, fromZ, size), to = mapCell(toX, toZ, size);
  return { minX: Math.min(from.minX, to.minX), minZ: Math.min(from.minZ, to.minZ),
    maxX: Math.max(from.maxX, to.maxX), maxZ: Math.max(from.maxZ, to.maxZ) };
}
export const inRects = (x: number, z: number, rects: MapRect[]) =>
  rects.some(r => x >= r.minX && x < r.maxX && z >= r.minZ && z < r.maxZ);

export function cellSize(pixelsPerBlock: number, mergePixels: number): (typeof mapSizes)[number] {
  return mapSizes.find(size => size * pixelsPerBlock >= mergeThreshold(mergePixels)) ?? mapSizes[mapSizes.length - 1];
}

/** Subtract one rectangle with at most four output rectangles; no block enumeration. */
function cutRect(rect: MapRect, cut: MapRect): MapRect[] {
  const x1 = Math.max(rect.minX, cut.minX), z1 = Math.max(rect.minZ, cut.minZ);
  const x2 = Math.min(rect.maxX, cut.maxX), z2 = Math.min(rect.maxZ, cut.maxZ);
  if (x1 >= x2 || z1 >= z2) return [rect];
  return [
    { minX: rect.minX, minZ: rect.minZ, maxX: rect.maxX, maxZ: z1 },
    { minX: rect.minX, minZ: z2, maxX: rect.maxX, maxZ: rect.maxZ },
    { minX: rect.minX, minZ: z1, maxX: x1, maxZ: z2 },
    { minX: x2, minZ: z1, maxX: rect.maxX, maxZ: z2 }
  ].filter(r => r.minX < r.maxX && r.minZ < r.maxZ);
}

export function isRectSelected(existing: MapRect[], rect: MapRect) {
  let remaining = [rect];
  for (const part of existing) {
    remaining = remaining.flatMap(candidate => cutRect(candidate, part));
    if (!remaining.length) return true;
  }
  return false;
}

function mergeAdjacent(rects: MapRect[]) {
  let result = rects;
  let before: number;
  do {
    before = result.length;
    for (const horizontal of [true, false]) {
      const groups = new Map<string, MapRect[]>();
      for (const rect of result) {
        const key = horizontal ? `${rect.minZ},${rect.maxZ}` : `${rect.minX},${rect.maxX}`;
        const group = groups.get(key);
        if (group) group.push(rect); else groups.set(key, [rect]);
      }
      result = [];
      for (const group of groups.values()) {
        group.sort((a, b) => horizontal ? a.minX - b.minX : a.minZ - b.minZ);
        for (const rect of group) {
          const last = result[result.length - 1];
          if (last && (horizontal
            ? last.minZ === rect.minZ && last.maxZ === rect.maxZ && last.maxX === rect.minX
            : last.minX === rect.minX && last.maxX === rect.maxX && last.maxZ === rect.minZ)) {
            if (horizontal) last.maxX = rect.maxX; else last.maxZ = rect.maxZ;
          } else result.push({ ...rect });
        }
      }
    }
  } while (result.length < before);
  return result;
}

/** A single area represented by disjoint rectangles. The gesture's mode is fixed at its start. */
export function selectRect(existing: MapRect[], rect: MapRect, subtract: boolean) {
  if (subtract) return mergeAdjacent(existing.flatMap(part => cutRect(part, rect)));
  let added = [rect];
  for (const part of existing) added = added.flatMap(candidate => cutRect(candidate, part));
  return mergeAdjacent([...existing, ...added]);
}

export interface MapEdge { x1: number; z1: number; x2: number; z2: number }
export function selectionEdges(rects: MapRect[]): MapEdge[] {
  const lines = new Map<string, { horizontal: boolean; at: number; events: Map<number, number> }>();
  function edge(horizontal: boolean, at: number, start: number, end: number, sign: number) {
    const key = `${horizontal}/${at}`;
    let line = lines.get(key);
    if (!line) { line = { horizontal, at, events: new Map() }; lines.set(key, line); }
    line.events.set(start, (line.events.get(start) ?? 0) + sign);
    line.events.set(end, (line.events.get(end) ?? 0) - sign);
  }
  for (const r of rects) {
    edge(true, r.minZ, r.minX, r.maxX, 1); edge(true, r.maxZ, r.minX, r.maxX, -1);
    edge(false, r.minX, r.minZ, r.maxZ, 1); edge(false, r.maxX, r.minZ, r.maxZ, -1);
  }
  const result: MapEdge[] = [];
  for (const line of lines.values()) {
    let previous: number | undefined, count = 0;
    for (const [position, change] of [...line.events].sort((a, b) => a[0] - b[0])) {
      if (previous !== undefined && count !== 0 && position > previous) result.push(line.horizontal
        ? { x1: previous, z1: line.at, x2: position, z2: line.at }
        : { x1: line.at, z1: previous, x2: line.at, z2: position });
      count += change; previous = position;
    }
  }
  return result;
}

export function scaleBar(pixelsPerBlock: number, availableWidth: number) {
  const target = Math.max(1, Math.min(140, availableWidth * .28)) / pixelsPerBlock;
  const power = 10 ** Math.floor(Math.log10(Math.max(Number.MIN_VALUE, target)));
  const leading = target / power;
  const blocks = (leading >= 5 ? 5 : leading >= 2 ? 2 : 1) * power;
  return { blocks, pixels: blocks * pixelsPerBlock };
}

export function unionArea(rects: MapRect[]) {
  const xs = [...new Set(rects.flatMap(r => [r.minX, r.maxX]))].sort((a, b) => a - b);
  let area = 0;
  for (let i = 1; i < xs.length; i++) {
    const strips = rects.filter(r => r.minX < xs[i] && r.maxX > xs[i - 1])
      .map(r => [r.minZ, r.maxZ]).sort((a, b) => a[0] - b[0]);
    let start: number | undefined, end = 0, length = 0;
    for (const [a, b] of strips) {
      if (start === undefined) { start = a; end = b; }
      else if (a > end) { length += end - start; start = a; end = b; }
      else end = Math.max(end, b);
    }
    area += (length + (start === undefined ? 0 : end - start)) * (xs[i] - xs[i - 1]);
  }
  return area;
}

const matchesSource = (entry: InstanceEntry, source: MapSource) => source === 'all' || entry.source === source;
const timingExists = (entry: InstanceEntry, tick: number) => instanceTiming(entry, tick) !== undefined;

export interface MapMetric { value: number; tick: number; denominator: number; percent: number }
export interface MapSummary extends MapMetric {
  objects: number; blocks: number; entities: number; columns: number;
  coveredChunks: number; totalChunks: number; coverage: number;
  minY?: number; maxY?: number;
  recordedTicks?: number; recordedCalls: number;
  hottest: { type: string; value: number; y: number }[];
}
export interface MapBucket { rect: MapRect; entries: InstanceEntry[]; metric: MapMetric; coverage: number }

/** Sparse object/tick index; never allocates a full world grid. */
export function spatialIndex(entries: InstanceEntry[], source: MapSource, ticks: number, lastTick: number) {
  const selected = entries.filter(entry => matchesSource(entry, source));
  const history = entries.length > 0 && lastTick > 0 && entries.every(entry => !!entry.tickHistory);
  const totals = new Map<number, number>();
  const allChunks = new Map<string, MapRect>();
  const chunksByTick = new Map<number, Set<string>>();
  for (const entry of entries) {
    const rect = mapCell(entry.position.x, entry.position.z, 16);
    const key = cellKey(rect.minX, rect.minZ);
    if (entry.ticks > 0) allChunks.set(key, rect);
    const h = entry.tickHistory;
    if (!h) continue;
    for (let i = 0; i < h.times.length; i++) {
      const tick = h.ticks?.[i] ?? h.firstTick + i;
      let keys = chunksByTick.get(tick);
      if (!keys) { keys = new Set(); chunksByTick.set(tick, keys); }
      keys.add(key);
      if (matchesSource(entry, source)) totals.set(tick, (totals.get(tick) ?? 0) + h.times[i]);
    }
  }
  const averageTotal = selected.reduce((sum, entry) => sum + entry.rate, 0);
  const chunkCache = new Map<number, MapRect[]>();
  const coverageCache = new Map<string, Map<string, number>>();
  function tickedChunks(tick: number) {
    let chunks = chunkCache.get(tick);
    if (!chunks) {
      // Coverage always includes both sources, independently of the load filter.
      const keys = tick ? [...(chunksByTick.get(tick) ?? [])] : [...allChunks.keys()];
      chunks = keys.map(key => allChunks.get(key)!).filter(Boolean);
      chunkCache.set(tick, chunks);
    }
    return chunks;
  }
  function cellCoverage(rect: MapRect, size: number, tick: number) {
    const key = `${size}/${tick}`;
    let counts = coverageCache.get(key);
    if (!counts) {
      counts = new Map();
      for (const chunk of tickedChunks(tick)) {
        const x = cellOrigin(chunk.minX, Math.max(size, 16)), z = cellOrigin(chunk.minZ, Math.max(size, 16));
        const key = cellKey(x, z);
        counts.set(key, (counts.get(key) ?? 0) + 1);
      }
      coverageCache.set(key, counts);
    }
    return (counts.get(cellKey(cellOrigin(rect.minX, Math.max(size, 16)), cellOrigin(rect.minZ, Math.max(size, 16)))) ?? 0)
      / Math.max(1, size * size / 256);
  }
  function metric(rows: InstanceEntry[], mode: ValueMode, tick: number): MapMetric {
    let value = 0;
    let actualTick = tick;
    if (mode === 'average') value = rows.reduce((sum, entry) => sum + entry.rate, 0);
    else if (mode === 'tick') value = rows.reduce((sum, entry) => sum + (instanceTiming(entry, tick)?.time ?? 0), 0);
    else {
      const sums = new Map<number, number>();
      for (const entry of rows) {
        const h = entry.tickHistory;
        if (!h) continue;
        for (let i = 0; i < h.times.length; i++) {
          const t = h.ticks?.[i] ?? h.firstTick + i;
          sums.set(t, (sums.get(t) ?? 0) + h.times[i]);
        }
      }
      let found = false;
      for (const [t, sum] of sums) if (!found || sum > value || sum === value && t < actualTick) {
        found = true; value = sum; actualTick = t;
      }
    }
    const denominator = mode === 'average' ? averageTotal : totals.get(actualTick) ?? 0;
    return { value, tick: actualTick, denominator, percent: denominator > 0 ? 100 * value / denominator : 0 };
  }
  function rowsIn(rects: MapRect[], mode: ValueMode, tick: number) {
    return selected.filter(entry => inRects(entry.position.x, entry.position.z, rects)
      && (mode === 'average' || timingExists(entry, tick)));
  }
  function summarize(rects: MapRect[], mode: ValueMode, tick: number): MapSummary {
    const allRows = rowsIn(rects, 'average', tick);
    const m = metric(allRows, mode, tick);
    const rows = mode === 'average' ? allRows : allRows.filter(entry => timingExists(entry, m.tick));
    const chunkRects = rects.map(r => ({ minX: Math.floor(r.minX / 16), minZ: Math.floor(r.minZ / 16),
      maxX: Math.ceil(r.maxX / 16), maxZ: Math.ceil(r.maxZ / 16) }));
    const totalChunks = unionArea(chunkRects);
    const coveredChunks = tickedChunks(mode === 'average' ? 0 : m.tick).filter(chunk =>
      rects.some(r => r.minX < chunk.maxX && r.maxX > chunk.minX && r.minZ < chunk.maxZ && r.maxZ > chunk.minZ)).length;
    let minY: number | undefined, maxY: number | undefined;
    const columns = new Set<string>();
    const recordedTicks = new Set<number>();
    const hottest: MapSummary['hottest'] = [];
    let recordedCalls = 0;
    let blocks = 0;
    for (const entry of rows) {
      const { x, y, z } = entry.position;
      columns.add(cellKey(x, z));
      minY = Math.min(minY ?? y, y); maxY = Math.max(maxY ?? y, y);
      if (entry.source === 'blocks') blocks++;
      const timing = mode === 'average' ? undefined : instanceTiming(entry, m.tick);
      recordedCalls += mode === 'average' ? entry.ticks : timing?.calls ?? 0;
      if (mode === 'average' && entry.tickHistory) {
        const h = entry.tickHistory;
        for (let i = 0; i < h.times.length; i++) recordedTicks.add(h.ticks?.[i] ?? h.firstTick + i);
      } else if (timing) recordedTicks.add(m.tick);
      hottest.push({ type: entry.type, value: mode === 'average' ? entry.rate : timing?.time ?? 0, y });
      hottest.sort((a, b) => b.value - a.value);
      if (hottest.length > 3) hottest.pop();
    }
    return { ...m, objects: rows.length, blocks, entities: rows.length - blocks, columns: columns.size,
      coveredChunks, totalChunks, coverage: totalChunks ? coveredChunks / totalChunks : 0, minY, maxY,
      recordedTicks: history ? recordedTicks.size : undefined, recordedCalls, hottest };
  }
  function buckets(size: number, mode: ValueMode, tick: number) {
    const cells = new Map<string, MapBucket>();
    for (const entry of selected) {
      if (mode === 'tick' && !timingExists(entry, tick)) continue;
      const rect = mapCell(entry.position.x, entry.position.z, size);
      const key = cellKey(rect.minX, rect.minZ);
      let cell = cells.get(key);
      if (!cell) { cell = { rect, entries: [], metric: { value: 0, denominator: 0, percent: 0, tick }, coverage: 0 }; cells.set(key, cell); }
      cell.entries.push(entry);
    }
    for (const cell of cells.values()) {
      cell.metric = metric(cell.entries, mode, tick);
      cell.coverage = cellCoverage(cell.rect, size, mode === 'average' ? 0 : cell.metric.tick);
    }
    return cells;
  }
  return { entries, selected, history, ticks, lastTick, averageTotal, metric, buckets, summarize, tickedChunks, cellCoverage, rowsIn };
}

export function parseMapRects(params: URLSearchParams): MapRect[] {
  return params.getAll('xz').flatMap(value => {
    const a = value.split(',').map(Number);
    return a.length === 4 && a.every(v => Number.isSafeInteger(v) && Math.abs(v) <= 60_000_000)
      && a[0] < a[2] && a[1] < a[3]
      ? [{ minX: a[0], minZ: a[1], maxX: a[2], maxZ: a[3] }] : [];
  });
}

export function mapIndividualUrl(base: string, dimension: string, source: MapSource,
  rects: MapRect[], mode: ValueMode, tick: number) {
  const params = new URLSearchParams({ dimension, source, mode, tick: String(tick) });
  for (const r of rects) params.append('xz', [r.minX, r.minZ, r.maxX, r.maxZ].join(','));
  return `${base}?${params}`;
}

export function mapTeleport(entries: InstanceEntry[], x: number, z: number) {
  x = Math.floor(x); z = Math.floor(z);
  const heights = new Set(entries.filter(entry => entry.position.x === x
    && entry.position.z === z).map(entry => entry.position.y));
  return { x, y: heights.size === 1 ? [...heights][0] : '~' as const, z };
}

export function loadColor(fraction: number) {
  // Green → yellow → muted red-orange, rather than a pure red endpoint.
  return `hsl(${120 - 100 * Math.max(0, Math.min(1, fraction))} 62% 46%)`;
}
