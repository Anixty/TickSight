import type { CallTrace, Entry, InstanceEntry, Profile, TickHistory, TickTiming } from './types';

export type ValueMode = 'average' | 'peak' | 'tick';

const tickDate = new Intl.DateTimeFormat('en-GB', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  second: '2-digit', fractionalSecondDigits: 3, timeZoneName: 'longOffset', hour12: false
});

export function tickStartMillis(starts: Profile['tickStarts'], tick: number): number | undefined {
  if (!starts || !Number.isSafeInteger(tick) || tick < 1) return undefined;
  const offset = starts.offsetsMillis?.[tick - 1];
  if (!Number.isSafeInteger(starts.epochMillis) || !Number.isSafeInteger(offset) || offset < 0) return undefined;
  const millis = starts.epochMillis + offset;
  return Number.isSafeInteger(millis) && Math.abs(millis) <= 8.64e15 ? millis : undefined;
}

export function tickStartLabel(starts: Profile['tickStarts'], tick: number) {
  const millis = tickStartMillis(starts, tick);
  return millis === undefined ? 'Tick start time wasn’t recorded' : tickDate.format(millis);
}

export function readMode(value: string | null, peakMode?: boolean): ValueMode {
  if (value === 'average' || value === 'peak' || value === 'tick') return value;
  return peakMode ? 'peak' : 'average';
}

export function readTick(value: string | null, lastTick: number) {
  const tick = Number(value);
  return Number.isSafeInteger(tick) ? Math.max(1, Math.min(lastTick, tick)) : 1;
}

function timingSlot(history: TickHistory, tick: number) {
  return history.ticks?.indexOf(tick) ?? tick - history.firstTick;
}

function timingAt(history: TickHistory, slot: number): TickTiming | undefined {
  const time = history.times[slot];
  if (time === undefined) return undefined;
  return {
    tick: history.ticks?.[slot] ?? history.firstTick + slot,
    time,
    calls: history.calls?.[slot] ?? 1,
    traces: history.traces?.[slot]
  };
}

export function instanceTiming(entry: Entry, tick: number): TickTiming | undefined {
  const history = entry.tickHistory;
  return history ? timingAt(history, timingSlot(history, tick)) : undefined;
}

export function peakTick(entry: Entry): TickTiming | undefined {
  const history = entry.tickHistory;
  if (!history) return undefined;
  let peak = 0;
  for (let slot = 1; slot < history.times.length; slot++) {
    if (history.times[slot] > history.times[peak]) peak = slot;
  }
  return timingAt(history, peak);
}

export function entryValue(entry: Entry, mode: ValueMode, tick: number) {
  if (mode === 'peak') return entry.peak;
  if (mode === 'tick') {
    const history = entry.tickHistory;
    return history ? history.times[timingSlot(history, tick)] ?? 0 : 0;
  }
  return entry.rate;
}

function modeParams(mode: ValueMode, tick: number) {
  const params = new URLSearchParams({ mode });
  if (mode === 'tick' || tick > 1) params.set('tick', String(tick));
  return params;
}

export function individualUrl(base: string, mode: ValueMode, tick: number) {
  return `${base}?${modeParams(mode, tick)}`;
}

export function instanceTraceUrl(
  base: string,
  dimension: string,
  entry: InstanceEntry,
  mode: ValueMode,
  tick: number
) {
  const params = modeParams(mode, tick);
  params.set('dimension', dimension);
  params.set('source', entry.source);
  params.set('index', String(entry.index));
  return `${base}/traces?${params}`;
}

export function instanceCallTicks(trace: CallTrace, entry: Entry) {
  return entry.callTargetId == null ? undefined : trace.objectTicks[entry.callTargetId];
}

export function profileCommand(dimension: string, entry: Entry) {
  const entity = entry.entityUuid;
  const { x, y, z } = entry.position;
  const target = entity != null ? `entity ${entity}` : `block ${x} ${y} ${z}`;
  return `/ticksight run 6t calls in ${dimension} ${target} full`;
}

export function instanceCallsUrl(
  base: string,
  entry: InstanceEntry,
  mode: ValueMode,
  tick: number
) {
  const params = new URLSearchParams();
  if (mode === 'peak') params.set('tick', String(peakTick(entry)?.tick ?? 1));
  if (mode === 'tick') params.set('tick', String(tick));
  if (entry.callTargetId != null) params.set('object', String(entry.callTargetId));
  return `${base}/calls${params.size ? `?${params}` : ''}`;
}
