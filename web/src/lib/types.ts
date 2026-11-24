export interface Profile {
  entities: Record<string, Entry[]>;
  blocks: Record<string, Entry[]>;
  ticks: number;
  traces?: Trace;
  peakMode?: boolean;
  timelineTicks: number;
  tickStarts?: { epochMillis: number; offsetsMillis: number[] };
  callTrace?: CallTrace;
  chunkTelemetry?: ChunkTelemetry;
}

export interface ChunkTelemetryScope {
  kind: 'all' | 'dimension' | 'box' | 'radius' | 'chunks' | 'block' | 'entity';
  dimension?: string;
  minX?: number; maxX?: number; minZ?: number; maxZ?: number;
  centerX?: number; centerZ?: number; radius?: number;
}
export interface ChunkTicketSource {
  dimension: string; x: number; z: number; type: string; typeId?: string; level: number;
  key?: string; keyClass?: string;
  modId?: string;
  owner?: { kind: string; uuid?: string; name?: string };
  channel: 'loading' | 'simulation' | 'both' | 'none';
  forceTicks?: boolean;
  outsideScope?: boolean;
}
export interface ChunkState {
  loaded: boolean | null; fullStatus: string | null;
  generationStatus?: string | null; targetGenerationStatus?: string | null;
  ticketLevel: number | null; simulationLevel?: number | null;
  fullReady: boolean | null; blockTickingReady: boolean | null; entityTickingReady: boolean | null;
  blockTicking: boolean | null; entityTicking: boolean | null;
  forcedTicking?: boolean | null;
  managedSources?: { kind: string; mode: 'loading' | 'loading_natural_spawning'; modId: string;
    key?: string; keyClass?: string; owner?: { kind: string; uuid?: string; name?: string } }[] | null;
  visibleHolder: boolean | null; watchers: string[];
  inView?: string[]; pendingSends?: string[];
  pendingUnload?: boolean | null;
  directTickets: number[];
  influencingTickets: { source: number; distance: number; computedLevel: number; relation: 'equal' | 'candidate'; channel: 'loading' | 'simulation' }[];
}
export type ChunkInfluence = ChunkState['influencingTickets'][number];
export interface EncodedChunkState extends Omit<ChunkState, 'watchers' | 'inView' | 'pendingSends' | 'directTickets' | 'influencingTickets'> {
  watcherSet: number | null; directTicketSet: number | null; influenceSet: number | null;
  inViewSet?: number | null; pendingSendSet?: number | null;
}
export interface RecordedLayers {
  runtime: boolean; generation: boolean; ticketLevels: boolean; ticketSources: boolean; visibility: boolean; players: boolean;
}
export interface ChunkChange { dimension: number; x: number; z: number; state: number }
export interface TelemetryPlayer extends Position { uuid: string; name: string; dimension: string }
export interface ChunkTelemetryFrame {
  tick: number; baseline?: boolean;
  scope?: ChunkTelemetryScope;
  chunks: ChunkChange[];
  removedChunks: { dimension: number; x: number; z: number }[];
  players: TelemetryPlayer[]; removedPlayers: string[];
}
export interface ChunkTelemetry {
  version: 2;
  capturePoint: 'server_tick_end';
  scope: ChunkTelemetryScope;
  dimensions: string[];
  actors?: { uuid: string; name: string }[];
  capabilities: {
    holders: 'updating_and_visible' | 'updating_visible_and_pending_unload' | 'not_recorded'; generationStatus: 'last_completed' | 'last_completed_or_persisted' | 'not_recorded';
    tickets: 'direct_and_effective_candidates' | 'not_recorded'; ticketOwners: 'native_key_only' | 'not_exposed' | 'managed_groups_only' | 'not_recorded';
    watchers: 'server_tracking_view' | 'server_tracking_excluding_pending_sends' | 'server_view_range' | 'not_recorded';
    pendingSends?: 'observed' | 'unavailable' | 'not_recorded'; clientRender: false; simulationLevel: boolean;
    layers?: RecordedLayers;
  };
  watcherSets: string[][];
  directTicketSets: number[][];
  influences: ChunkInfluence[];
  influenceSets: number[][];
  states: EncodedChunkState[];
  ticketSources: ChunkTicketSource[];
  frames: ChunkTelemetryFrame[];
  completeThroughTick: number;
  truncated?: { tick: number; reason: string };
}

export interface CallMethod {
  className: string;
  name: string;
  descriptor: string;
}

export interface MethodCall {
  parent: number;
  method: number;
  count: number;
  elapsedNanos?: number;
  untracedCalls?: number;
  untracedNanos?: number;
}

export interface CallTrace {
  target: string;
  limitMs: number;
  methods: CallMethod[];
  objectTicks: Record<number, Record<number, MethodCall[]>>;
  cutoff?: {
    tick: number;
    elapsedNanos: number;
    reason?: 'tick_time' | 'hard_tick_time' | 'memory' | 'heap' | 'report_size';
    recordingElapsedNanos?: number;
    limit?: number;
    reservedBytes?: number;
    heapAvailableBytes?: number;
  };
  skippedClasses?: number;
  skippedMethods?: number;
  maxRepeats?: number;
  hardLimitMs?: number;
  memoryLimitMiB?: number;
  heapReserveMiB?: number;
  reservedBytes?: number;
}

export interface Position {
  x: number;
  y: number;
  z: number;
}

export interface Entry {
  entityId?: number;
  entityUuid?: string;
  callTargetId?: number;
  position: Position;
  type: string;
  rate: number;
  ticks: number;
  traces?: Trace;
  peak: number;
  tickHistory?: TickHistory;
}

export interface TickHistory {
  firstTick: number;
  times: number[];
  ticks?: number[];
  calls?: Record<number, number>;
  traces?: Record<number, Trace | null>;
}

export interface TickTiming {
  tick: number;
  time: number;
  calls: number;
  traces?: Trace | null;
}

export interface InstanceEntry extends Entry {
  source: 'entities' | 'blocks';
  index: number;
}

export interface Trace {
  className: string;
  methodName: string;
  count: number;
  children: Trace[];
}

export type ProfileOwner =
  | { kind: 'player'; name: string; uuid: string }
  | { kind: 'console' | 'command_block' | 'automation'; name: string; uuid?: never };

export interface Diagnostics {
  owner: ProfileOwner;
  start?: number;
  duration?: number;
  minecraftVersion?: string;
  modLoader?: string;
  tickSightVersion?: string;
  observableVersion?: string;
  source?: 'server' | 'client';
  content?: 'full' | 'overlay';
  exportedBy?: 'client';
  capturedAt?: number;
  jvmFlagsSanitized?: boolean;
  server?: ServerDiagnostics;
  settings?: {
    traceIntervalMs?: number;
    deviationMs?: number;
    includeJvmArgs?: boolean;
    callTraceTickLimitMs?: number;
    callTraceMaxRepeats?: number;
    callTraceHardTickLimitMs?: number;
    callTraceMemoryLimitMiB?: number;
    callTraceHeapReserveMiB?: number;
  };
  recording?: {
    started?: boolean;
    mode?: 'timings' | 'sample' | 'calls';
    requestedDuration?: { amount: number; unit: 'ticks' | 'seconds' };
    initialView?: 'average' | 'peak';
    target?: string;
    scope?: string;
    samplingIntervalNanos?: number;
    samplingDeviationNanos?: number;
    stopReason?: string;
    completed?: boolean;
    tickets?: boolean;
    layers?: RecordedLayers;
    calls?: {
      tickLimitMs?: number;
      maxRepeats?: number;
      hardTickLimitMs?: number;
      memoryLimitMiB?: number;
      heapReserveMiB?: number;
    };
  };
  additionalDiagnostics?: Record<string, unknown>;
}

export interface ServerDiagnostics {
  type?: 'dedicated' | 'integrated';
  minecraftVersion?: string;
  modLoader?: string;
  loaderVersion?: string;
  tickSightVersion?: string;
  players?: number;
  maxPlayers?: number;
  viewDistance?: number;
  simulationDistance?: number;
  gameMode?: string;
  runtime?: {
    javaVersion?: string;
    javaVendor?: string;
    javaVm?: string;
    os?: string;
    osVersion?: string;
    architecture?: string;
    processors?: number;
    maxHeapBytes?: number;
  };
}

export interface ReportFileDetails {
  bytes: number;
  source: 'file' | 'http';
  fileName?: string;
  transferBytes?: number;
  encoding?: string;
}

export interface DataWithDiagnostics {
  data: Profile;
  diagnostics: Diagnostics;
}
