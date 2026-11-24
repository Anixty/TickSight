import { defaultMerge, minimumMerge, maximumMerge } from './heatmap';
export const layerStorageKey = 'ticksight.heatmap.layers.v1';
export const mergeStorageKey = 'ticksight.heatmap.merge.v1';

export const defaultLayers = {
  runtime: false,
  generation: false,
  levels: false,
  sources: false,
  visibility: false,
  players: false,
  axes: true,
  levelChannel: 'loading' as 'loading' | 'simulation'
};
export type MapLayers = typeof defaultLayers;
export type MapLayer = Exclude<keyof MapLayers, 'levelChannel'>;
export const layerOptions: { key: MapLayer; label: string }[] = [
  { key: 'runtime', label: 'Runtime zones' },
  { key: 'generation', label: 'Generation stages' },
  { key: 'levels', label: 'Ticket levels' },
  { key: 'sources', label: 'Ticket sources' },
  { key: 'visibility', label: 'Server visibility' },
  { key: 'players', label: 'Players' },
  { key: 'axes', label: 'World axes' }
];

export function readLayers(): MapLayers {
  const result = { ...defaultLayers };
  try {
    const saved = JSON.parse(window.localStorage.getItem(layerStorageKey) ?? 'null');
    if (saved && typeof saved === 'object') {
      for (const { key } of layerOptions) if (typeof saved[key] === 'boolean') result[key] = saved[key];
      if (saved.levelChannel === 'loading' || saved.levelChannel === 'simulation') result.levelChannel = saved.levelChannel;
    }
  } catch { /* Storage can be disabled or contain a previous malformed value. */ }
  return result;
}

export function writeLayers(layers: MapLayers) {
  try { window.localStorage.setItem(layerStorageKey, JSON.stringify(layers)); } catch { /* Keep the current view usable. */ }
}

export function validMerge(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.max(minimumMerge, Math.min(maximumMerge, Math.round(value))) : defaultMerge;
}
export function readMerge() {
  try { return validMerge(JSON.parse(window.localStorage.getItem(mergeStorageKey) ?? 'null')); }
  catch { return defaultMerge; }
}
export function writeMerge(value: number) {
  try { window.localStorage.setItem(mergeStorageKey, JSON.stringify(validMerge(value))); } catch { /* In-memory controls still work. */ }
}

export function layerColor(value: string) {
  let hash = 0;
  for (const char of value) hash = (Math.imul(hash, 31) + char.charCodeAt(0)) | 0;
  return `hsl(${(hash >>> 0) % 360} 68% 65%)`;
}
