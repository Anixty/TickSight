<script lang="ts">
  import { onMount, tick as flush } from 'svelte';
  import { page } from '$app/stores';
  import type { Snapshot } from '@sveltejs/kit';
  import ValueControls from '$lib/ValueControls.svelte';
  import { readMode, readTick, type ValueMode } from '$lib/profiling';
  import { cellKey, cellSize, loadColor, mapCell, mapSelectionRect, isRectSelected, mapIndividualUrl, mapNames, mapSizes,
    mapTeleport, spatialIndex, selectRect, selectionEdges, scaleBar, minimumMerge, maximumMerge,
    defaultMerge, type MapRect, type MapSource } from '$lib/heatmap';
  import { copyTPCommand } from '$lib/utils';
  import { chunkTelemetryIndex, recordedLayers } from '$lib/chunkTelemetry';
  import { defaultLayers, layerOptions, layerStorageKey, readLayers, writeLayers, layerColor,
    mergeStorageKey, readMerge, writeMerge } from '$lib/mapLayers';
  import type { ChunkState, ChunkTicketSource, ChunkTelemetryScope } from '$lib/types';
  import type { LayoutData } from '../$types';

  export let data: LayoutData;
  let dimension = data.entries[0]?.name ?? '';
  let source: MapSource = 'all';
  let mode: ValueMode = readMode($page.url.searchParams.get('mode'), data.profile.peakMode);
  let selectedTick = readTick($page.url.searchParams.get('tick'), data.timelineTicks);
  let merge = defaultMerge;
  let scale = 32;
  let center = { x: 0, z: 0 };
  let viewport: HTMLDivElement;
  let canvas: HTMLCanvasElement;
  let menu: HTMLDivElement;
  let optionsMenu: HTMLDivElement;
  let layersMenu: HTMLDivElement;
  let layersOpen = false;
  let layersLeft = 8, layersTop = 8;
  let layers = { ...defaultLayers };
  let layersReady = false;
  let stateTick = Math.max(1, data.profile.chunkTelemetry?.completeThroughTick ?? data.timelineTicks);
  let compact = false;
  let optionsOpen = false;
  let width = 0, height = 0;
  let selection: MapRect[] = [];
  let draft: MapRect | null = null;
  type PointerPoint = { x: number; y: number; clientX: number; clientY: number; type: string };
  let drag: { pointerId: number; pointerType: string; x: number; y: number; worldX: number; worldZ: number;
    cx: number; cz: number; select: boolean; pendingSelect: boolean; subtract: boolean; moved: boolean; canTap: boolean; startedAt: number } | null = null;
  const pointers = new Map<number, PointerPoint>();
  let pinch: { ids: number[]; distance: number; scale: number; worldX: number; worldZ: number } | null = null;
  let lastTap: { at: number; x: number; y: number } | null = null;
  const touchSlop = 6, tapDuration = 300, doubleTapDelay = 350, doubleTapDistance = 24;
  let longPressTimer: ReturnType<typeof setTimeout> | undefined;
  let touchMenuClick = false;
  type Hover = { key: string; rect: MapRect; x: number; y: number };
  let hover: Hover | null = null;
  let tooltip: Hover | null = null;
  let pointer: { x: number; y: number } | null = null;
  let hoverTimer: ReturnType<typeof setTimeout>;
  let context: { rects: MapRect[]; x: number; y: number; worldX: number; worldZ: number } | null = null;
  let fittedDimension = '';
  let fittedProfile: LayoutData['profile'] | null = null;
  let animation = 0;
  let resizeFrame = 0;
  let zoomFrame = 0;
  let zoomTime = 0;
  let zoomTarget: number | null = null;
  let zoomAnchor: { x: number; y: number; worldX: number; worldZ: number } | null = null;
  let reducedMotion = false;
  let autoFit = true;
  let activeProfile = data.profile;
  const number = new Intl.NumberFormat('en', { maximumFractionDigits: 3 });
  const percent = new Intl.NumberFormat('en', { maximumFractionDigits: 2 });

  $: base = $page.params.id ? `/p/${$page.params.id}` : '/p';
  $: entries = data.entries.find(group => group.name === dimension)?.entries ?? [];
  $: telemetry = chunkTelemetryIndex(data.profile.chunkTelemetry);
  $: dimensions = [...new Set([...data.entries.map(group => group.name), ...telemetry.dimensions])];
  $: layerTick = mode === 'tick' ? selectedTick : stateTick;
  $: recorded = recordedLayers(data.profile.chunkTelemetry, data.diagnostics.recording?.layers);
  $: layerAvailable = { runtime: telemetry.available && recorded.runtime, generation: telemetry.available && recorded.generation,
    levels: telemetry.available && recorded.ticketLevels, sources: telemetry.available && recorded.ticketSources,
    visibility: telemetry.available && recorded.visibility, players: telemetry.available && recorded.players, axes: true };
  $: effectiveLayers = { ...layers, ...Object.fromEntries(layerOptions.map(({ key }) => [key, layers[key] && layerAvailable[key]])) } as typeof layers;
  $: chunkLayers = effectiveLayers.runtime || effectiveLayers.generation || effectiveLayers.levels || effectiveLayers.sources || effectiveLayers.visibility;
  $: stateLayers = chunkLayers || effectiveLayers.players;
  $: layerStatus = telemetry.stateAt(layerTick);
  $: layerScope = telemetry.scopeAt(layerTick);
  $: visibleWorld = { minX: center.x - width / 2 / scale, maxX: center.x + width / 2 / scale,
    minZ: center.z - height / 2 / scale, maxZ: center.z + height / 2 / scale };
  $: zoneChunks = chunkLayers ? telemetry.chunksAt(dimension, layerTick, visibleWorld) : [];
  $: statePlayers = stateLayers ? telemetry.playersAt(dimension, layerTick) : [];
  $: layerPlayers = effectiveLayers.players ? statePlayers : [];
  $: playerNames = new Map([...(telemetry.data?.actors?.map(actor => [actor.uuid, actor.name] as const) ?? []),
    ...statePlayers.map(player => [player.uuid, player.name] as const)]);
  $: hoveredChunk = chunkLayers && cursor ? telemetry.chunkAt(dimension, cursor.x, cursor.z, layerTick) : null;
  $: runtimeLegend = [...new Set(telemetry.data?.states.map(runtimeName) ?? [])].sort();
  $: generationLegend = [...new Set(telemetry.data?.states.flatMap(s => s.generationStatus ? [s.generationStatus] : []) ?? [])].sort();
  $: sourceLegend = [...new Set([...(telemetry.data?.ticketSources.map(s => s.type) ?? []),
    ...(telemetry.data?.states.flatMap(s => s.managedSources?.map(group => `${group.kind} · ${group.modId} · ${group.mode}`) ?? []) ?? [])])].sort();
  $: if (layersReady) writeLayers(layers);
  $: if (layersReady) writeMerge(merge);
  $: index = spatialIndex(entries, source, data.profile.ticks, data.timelineTicks);
  $: if (!index.history && mode !== 'average' && (mode !== 'tick' || !telemetry.available)) mode = 'average';
  $: size = cellSize(scale, merge);
  $: cells = index.buckets(size, mode, selectedTick);
  $: maximum = [...cells.values()].reduce((max, cell) => Math.max(max, cell.metric.value), 0);
  $: parentSize = mapSizes[mapSizes.indexOf(size) + 1];
  $: parentCells = parentSize === undefined ? null : index.buckets(parentSize, mode, selectedTick);
  $: parentMaximum = parentCells ? [...parentCells.values()].reduce((max, cell) => Math.max(max, cell.metric.value), 0) : 0;
  $: summary = selection.length ? index.summarize(selection, mode, selectedTick) : null;
  $: tooltipRects = selection.length ? selection : tooltip ? [tooltip.rect] : [];
  $: info = tooltip ? index.summarize(tooltipRects, mode, selectedTick) : null;
  $: contextInfo = context ? index.summarize(context.rects, mode, selectedTick) : null;
  $: contextTick = mode === 'peak' ? contextInfo?.tick ?? selectedTick : selectedTick;
  $: teleport = context ? mapTeleport(entries, context.worldX, context.worldZ) : null;
  $: shownSelection = draft && drag?.select ? selectRect(selection, draft, drag.subtract) : selection;
  $: outline = selectionEdges(shownSelection);
  $: ruler = scaleBar(scale, width);
  $: origin = { x: width / 2 - center.x * scale, y: height / 2 - center.z * scale };
  $: cursor = pointer && !pinch ? cursorBlock(pointer, width, height, scale, center) : null;
  $: if (data.profile !== activeProfile) resetProfile();
  $: if (dimensions.length && !dimensions.includes(dimension)) dimension = dimensions[0];
  $: if (viewport && width && (dimension !== fittedDimension || data.profile !== fittedProfile)) {
    fittedDimension = dimension; fittedProfile = data.profile; selection = []; fit();
  }
  $: if (canvas) requestPaint(width, height, scale, center, size, cells, maximum, parentSize, parentCells, parentMaximum,
    shownSelection, outline, draft, index, mode, ruler, layers, effectiveLayers, zoneChunks, layerPlayers, layerScope);
  $: hideOnChange(dimension, source, mode, selectedTick, merge);

  type MapSnapshot = { profile: string; dimension: string; source: MapSource; mode: ValueMode;
    selectedTick: number; stateTick: number; merge: number; center: { x: number; z: number }; scale: number; selection: MapRect[] };
  const profileKey = () => $page.params.id ?? 'local:' + data.diagnostics.start;
  export const snapshot: Snapshot<MapSnapshot> = {
    capture: () => {
      // Store the visible camera, never the outstanding animated zoom target.
      cancelZoom(); cancelGesture();
      return { profile: profileKey(), dimension, source, mode, selectedTick, stateTick, merge,
        center: { ...center }, scale, selection: selection.map(rect => ({ ...rect })) };
    },
    restore: (saved) => {
      if (!saved || saved.profile !== profileKey() || !dimensions.includes(saved.dimension)
        || !Number.isFinite(saved.scale) || !Number.isFinite(saved.center.x) || !Number.isFinite(saved.center.z)) return;
      cancelZoom(); cancelGesture();
      activeProfile = data.profile;
      dimension = saved.dimension; source = saved.source; mode = saved.mode;
      selectedTick = saved.selectedTick; merge = readMerge();
      stateTick = saved.stateTick ?? Math.max(1, data.profile.chunkTelemetry?.completeThroughTick ?? data.timelineTicks); layers = readLayers();
      center = { ...saved.center }; scale = saved.scale;
      selection = saved.selection.map(rect => ({ ...rect }));
      // The first ResizeObserver notification must not replace a restored camera with Fit.
      autoFit = false; fittedProfile = data.profile; fittedDimension = dimension;
      context = null; optionsOpen = false; layersOpen = false;
    }
  };

  function resetProfile() {
    activeProfile = data.profile;
    cancelGesture(); cancelZoom();
    dimension = data.entries[0]?.name ?? ''; source = 'all';
    mode = readMode($page.url.searchParams.get('mode'), data.profile.peakMode);
    selectedTick = readTick($page.url.searchParams.get('tick'), data.timelineTicks);
    stateTick = Math.max(1, data.profile.chunkTelemetry?.completeThroughTick ?? data.timelineTicks); layersOpen = false;
    merge = readMerge(); selection = []; autoFit = true; fittedProfile = null;
  }

  const formatTime = (value: number) => value >= 1_000_000
    ? `${number.format(value / 1_000_000)} ms` : `${number.format(value / 1_000)} µs`;
  const metricLabel = (value: number) => `${formatTime(value)}${mode === 'average' ? '/t' : ''}`;
  const scopeName = () => source === 'blocks' ? 'Blocks' : source === 'entities' ? 'Entities' : 'All objects';
  const rectLabel = (rect: MapRect) => `X ${rect.minX}…${rect.maxX - 1}, Z ${rect.minZ}…${rect.maxZ - 1}`;

  onMount(() => {
    layers = readLayers(); merge = readMerge(); layersReady = true;
    const storage = (event: StorageEvent) => {
      if (event.key === layerStorageKey || event.key === null) layers = readLayers();
      if (event.key === mergeStorageKey || event.key === null) merge = readMerge();
    };
    window.addEventListener('storage', storage);
    const media = window.matchMedia('(max-width: 639px)');
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const updateCompact = () => { compact = media.matches; };
    updateCompact(); media.addEventListener('change', updateCompact);
    const updateMotion = () => {
      reducedMotion = motion.matches;
      if (reducedMotion && zoomTarget !== null && zoomAnchor) { applyZoom(zoomTarget, zoomAnchor); cancelZoom(); }
    };
    updateMotion(); motion.addEventListener('change', updateMotion);
    const resize = new ResizeObserver(() => {
      const changed = width !== viewport.clientWidth || height !== viewport.clientHeight;
      width = viewport.clientWidth;
      height = viewport.clientHeight;
      if (changed) {
        cancelZoom(); hideHover(); context = null;
        if (pointers.size) cancelGesture();
        if (autoFit) fit();
        cancelAnimationFrame(resizeFrame);
        resizeFrame = requestAnimationFrame(() => { if (pointer) pointer = { ...pointer }; });
      }
    });
    resize.observe(viewport);
    const wheel = (event: WheelEvent) => {
      event.preventDefault();
      const units = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? height : 1;
      const delta = Math.max(-150, Math.min(150, (event.deltaY || event.deltaX) * units));
      const speed = (event.ctrlKey ? 2 : 1) / (event.shiftKey ? 3 : 1);
      const p = point(event);
      zoom(Math.exp(-delta * .008 * speed), p.x, p.y);
    };
    viewport.addEventListener('wheel', wheel, { passive: false });
    return () => { window.removeEventListener('storage', storage); media.removeEventListener('change', updateCompact); motion.removeEventListener('change', updateMotion); resize.disconnect(); viewport.removeEventListener('wheel', wheel); clearTimeout(hoverTimer); cancelAnimationFrame(animation); cancelAnimationFrame(resizeFrame); cancelZoom(); cancelGesture(); };
  });

  function windowClick(event: MouseEvent) {
    context = null;
    if (compact && optionsMenu && !optionsMenu.contains(event.target as Node)) optionsOpen = false;
    if (layersMenu && !layersMenu.contains(event.target as Node)) layersOpen = false;
  }
  function positionLayers() {
    if (!layersOpen || !layersMenu) return;
    const box = layersMenu.getBoundingClientRect();
    layersLeft = Math.max(8, Math.min(box.left, window.innerWidth - Math.min(288, window.innerWidth - 16) - 8));
    layersTop = Math.max(8, Math.min(box.bottom + 4, window.innerHeight - 120));
  }
  function toggleLayers() { layersOpen = !layersOpen; positionLayers(); }
  function consumeTouchMenuClick(event: MouseEvent) {
    if (!touchMenuClick) return;
    touchMenuClick = false;
    // A long press may place the menu under the held finger. Its release must
    // neither activate a menu item nor immediately close the menu.
    if (event.detail) { event.preventDefault(); event.stopPropagation(); }
  }
  function hideOnChange(..._values: unknown[]) { cancelZoom(); clearLongPress(); hideHover(); context = null; }
  function hideHover() { clearTimeout(hoverTimer); hover = null; tooltip = null; }
  function clearLongPress() { clearTimeout(longPressTimer); longPressTimer = undefined; }
  function point(event: { clientX: number; clientY: number }) {
    const box = canvas.getBoundingClientRect();
    // Input uses CSS pixels; DPR only affects the canvas backing store in paint().
    return { x: (event.clientX - box.left) * width / (box.width || 1),
      y: (event.clientY - box.top) * height / (box.height || 1) };
  }
  function cursorBlock(p: { x: number; y: number }, w: number, h: number, s: number, c: typeof center) {
    const box = canvas?.getBoundingClientRect();
    if (!box?.width || !box.height || p.x < box.left || p.x >= box.right || p.y < box.top || p.y >= box.bottom) return null;
    return { x: Math.floor(c.x + ((p.x - box.left) * w / box.width - w / 2) / s),
      z: Math.floor(c.z + ((p.y - box.top) * h / box.height - h / 2) / s) };
  }
  function world(x: number, y: number) {
    return { x: center.x + (x - width / 2) / scale, z: center.z + (y - height / 2) / scale };
  }
  function fit() {
    cancelZoom(); cancelGesture();
    autoFit = true;
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    for (const entry of entries) {
      minX = Math.min(minX, entry.position.x); maxX = Math.max(maxX, entry.position.x + 1);
      minZ = Math.min(minZ, entry.position.z); maxZ = Math.max(maxZ, entry.position.z + 1);
    }
    const layerBounds = telemetry.bounds(dimension);
    if (layerBounds && (!entries.length || stateLayers)) {
      minX = Math.min(minX, layerBounds.minX); maxX = Math.max(maxX, layerBounds.maxX);
      minZ = Math.min(minZ, layerBounds.minZ); maxZ = Math.max(maxZ, layerBounds.maxZ);
    }
    center = Number.isFinite(minX) ? { x: (minX + maxX) / 2, z: (minZ + maxZ) / 2 } : { x: 0, z: 0 };
    scale = Number.isFinite(minX) ? Math.max(.001, Math.min(64, width / (maxX - minX + 8), height / (maxZ - minZ + 8))) : 16;
    hideHover(); context = null;
  }
  function zoom(factor: number, x = width / 2, y = height / 2) {
    clearLongPress();
    autoFit = false;
    const anchor = world(x, y);
    const pending = zoomTarget ?? scale;
    // Reverse from the visible scale, rather than playing out an old wheel backlog.
    const start = Math.log(pending / scale) * Math.log(factor) < 0 ? scale : pending;
    zoomTarget = Math.max(.001, Math.min(256, start * factor));
    zoomAnchor = { x, y, worldX: anchor.x, worldZ: anchor.z };
    hideHover(); context = null;
    if (reducedMotion || zoomTarget === scale) { applyZoom(zoomTarget, zoomAnchor); cancelZoom(); }
    else if (!zoomFrame) { zoomTime = performance.now(); zoomFrame = requestAnimationFrame(advanceZoom); }
  }
  function applyZoom(value: number, anchor: NonNullable<typeof zoomAnchor>) {
    scale = value;
    center = { x: anchor.worldX - (anchor.x - width / 2) / scale,
      z: anchor.worldZ - (anchor.y - height / 2) / scale };
  }
  function advanceZoom(now: number) {
    zoomFrame = 0;
    if (zoomTarget === null || !zoomAnchor) return;
    const remaining = Math.log(zoomTarget / scale);
    const fraction = 1 - Math.exp(-Math.min(100, Math.max(0, now - zoomTime)) / 55);
    zoomTime = now;
    const next = scale * Math.exp(remaining * fraction);
    const done = Math.abs(Math.log(zoomTarget / next)) < .0001;
    applyZoom(done ? zoomTarget : next, zoomAnchor);
    if (done) { zoomTarget = null; zoomAnchor = null; }
    else zoomFrame = requestAnimationFrame(advanceZoom);
  }
  function cancelZoom() {
    cancelAnimationFrame(zoomFrame); zoomFrame = 0; zoomTarget = null; zoomAnchor = null;
  }
  function pointerdown(event: PointerEvent) {
    if (event.button !== 0) return;
    if (event.pointerType === 'touch') event.preventDefault();
    clearLongPress(); touchMenuClick = false;
    cancelZoom();
    hideHover(); context = null; viewport.focus({ preventScroll: true });
    if ([...pointers.values()].some(p => p.type !== event.pointerType)) cancelGesture();
    const p = { ...point(event), clientX: event.clientX, clientY: event.clientY, type: event.pointerType };
    pointers.set(event.pointerId, p);
    autoFit = false;
    viewport.setPointerCapture(event.pointerId);
    if (event.pointerType === 'touch' && pointers.size > 1) { beginPinch(); return; }
    const touchSelect = event.pointerType === 'touch' && lastTap !== null
      && performance.now() - lastTap.at <= doubleTapDelay
      && Math.hypot(event.clientX - lastTap.x, event.clientY - lastTap.y) <= doubleTapDistance;
    lastTap = null;
    beginDrag(event.pointerId, p, event.ctrlKey || event.metaKey, touchSelect);
    pointer = { x: event.clientX, y: event.clientY };
    if (event.pointerType === 'touch' && !touchSelect && !drag!.select) {
      const id = event.pointerId;
      longPressTimer = setTimeout(() => {
        longPressTimer = undefined;
        const current = pointers.get(id);
        if (current && pointers.size === 1 && drag?.pointerId === id && !drag.moved)
          void openContextAt(current.clientX, current.clientY, true);
      }, 500);
    }
  }
  function beginDrag(pointerId: number, p: PointerPoint, select = false, pendingSelect = false, continued = false) {
    const w = world(p.x, p.y);
    const cell = mapCell(w.x, w.z, size);
    drag = { pointerId, pointerType: p.type, x: p.x, y: p.y, worldX: w.x, worldZ: w.z, cx: center.x, cz: center.z,
      select, pendingSelect, subtract: (select || pendingSelect) && isRectSelected(selection, cell),
      moved: continued, canTap: !continued, startedAt: performance.now() };
    if (select) draft = cell;
  }
  function beginPinch() {
    clearLongPress();
    const pair = [...pointers.entries()].slice(0, 2);
    const [a, b] = pair.map(([, p]) => p), w = world((a.x + b.x) / 2, (a.y + b.y) / 2);
    drag = null; draft = null; lastTap = null; pointer = null; hideHover();
    pinch = { ids: pair.map(([id]) => id), distance: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)),
      scale, worldX: w.x, worldZ: w.z };
  }
  function updatePinch() {
    if (!pinch) return;
    const [a, b] = pinch.ids.map(id => pointers.get(id)!);
    scale = Math.max(.001, Math.min(256, pinch.scale * Math.hypot(a.x - b.x, a.y - b.y) / pinch.distance));
    center = { x: pinch.worldX - ((a.x + b.x) / 2 - width / 2) / scale,
      z: pinch.worldZ - ((a.y + b.y) / 2 - height / 2) / scale };
  }
  function updateDrag(p: PointerPoint) {
    if (!drag) return;
    const distance = Math.hypot(p.x - drag.x, p.y - drag.y);
    if (!drag.moved && distance <= (drag.pointerType === 'touch' ? touchSlop : 0)) return;
    clearLongPress();
    if (!drag.moved) drag.moved = true;
    if (drag.pendingSelect) { drag.select = true; drag.pendingSelect = false; }
    const w = world(p.x, p.y);
    if (drag.select) {
      const next = mapSelectionRect(drag.worldX, drag.worldZ, w.x, w.z, size);
      if (!draft || next.minX !== draft.minX || next.minZ !== draft.minZ || next.maxX !== draft.maxX || next.maxZ !== draft.maxZ)
        draft = next;
    }
    else center = { x: Math.max(-30_000_000, Math.min(30_000_000, drag.cx - (p.x - drag.x) / scale)),
      z: Math.max(-30_000_000, Math.min(30_000_000, drag.cz - (p.y - drag.y) / scale)) };
  }
  function pointermove(event: PointerEvent) {
    if (pointers.has(event.pointerId)) {
      const p = { ...point(event), clientX: event.clientX, clientY: event.clientY, type: event.pointerType };
      pointers.set(event.pointerId, p);
      if (pinch) updatePinch();
      else if (drag?.pointerId === event.pointerId) { pointer = { x: event.clientX, y: event.clientY }; updateDrag(p); }
      return;
    }
    if (event.pointerType === 'touch') return;
    pointer = { x: event.clientX, y: event.clientY };
    const p = point(event), w = world(p.x, p.y);
    if (context) return;
    const rect = mapCell(w.x, w.z, size);
    const key = selection.length ? 'selection' : cellKey(rect.minX, rect.minZ);
    if (hover?.key === key) return;
    hideHover();
    hover = { key, rect, x: event.clientX, y: event.clientY };
    hoverTimer = setTimeout(() => { tooltip = hover; }, 1000);
  }
  function pointerup(event: PointerEvent) {
    clearLongPress();
    if (!pointers.has(event.pointerId)) return;
    const p = { ...point(event), clientX: event.clientX, clientY: event.clientY, type: event.pointerType };
    pointers.set(event.pointerId, p);
    if (pinch) {
      updatePinch(); pointers.delete(event.pointerId);
      if (pointers.size > 1) beginPinch();
      else {
        pinch = null;
        const remaining = [...pointers.entries()][0];
        if (remaining) { beginDrag(remaining[0], remaining[1], false, false, true);
          pointer = { x: remaining[1].clientX, y: remaining[1].clientY }; }
        else { drag = null; pointer = null; }
      }
    } else if (drag?.pointerId === event.pointerId) {
      updateDrag(p);
      if (drag.select) selection = selectRect(selection, draft ?? mapCell(drag.worldX, drag.worldZ, size), drag.subtract);
      else if (event.pointerType === 'touch' && drag.canTap && !drag.moved && !drag.pendingSelect
        && performance.now() - drag.startedAt <= tapDuration && p.x >= 0 && p.x < width && p.y >= 0 && p.y < height)
        lastTap = { at: performance.now(), x: event.clientX, y: event.clientY };
      pointers.delete(event.pointerId); drag = null; draft = null;
      pointer = event.pointerType === 'touch' ? null : { x: event.clientX, y: event.clientY };
    }
    hideHover();
    if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
  }
  function cancelGesture() {
    clearLongPress(); touchMenuClick = false;
    const ids = [...pointers.keys()]; pointers.clear();
    drag = null; pinch = null; draft = null; lastTap = null; pointer = null; hideHover();
    for (const id of ids) {
      if (viewport?.hasPointerCapture(id)) viewport.releasePointerCapture(id);
    }
  }
  function lostCapture(event: PointerEvent) { if (pointers.has(event.pointerId)) cancelGesture(); }
  function pointerleave() { if (!pointers.size) { pointer = null; hideHover(); } }
  function clearSelection() { selection = []; draft = null; hideHover(); context = null; }
  async function openContext(event: MouseEvent) {
    event.preventDefault();
    if (touchMenuClick && context) return;
    if (pinch || drag?.select || drag?.pendingSelect) return;
    const touch = [...pointers.values()].some(p => p.type === 'touch');
    await openContextAt(event.clientX, event.clientY, touch);
  }
  async function openContextAt(clientX: number, clientY: number, touch = false) {
    cancelZoom(); cancelGesture();
    touchMenuClick = touch;
    const p = point({ clientX, clientY }), w = world(p.x, p.y);
    context = { rects: selection.length ? [...selection] : [mapCell(w.x, w.z, size)],
      x: Math.min(clientX, window.innerWidth - 240), y: Math.min(clientY, window.innerHeight - 160), worldX: w.x, worldZ: w.z };
    await flush(); menu?.querySelector<HTMLAnchorElement>('a')?.focus();
  }
  function keydown(event: KeyboardEvent) {
    if (event.key === 'Escape') { context = null; cancelGesture(); return; }
    if (event.key === '+' || event.key === '=') zoom(1.5);
    else if (event.key === '-') zoom(1 / 1.5);
    else if (event.key.startsWith('Arrow')) {
      cancelZoom();
      autoFit = false;
      center = { x: center.x + (event.key === 'ArrowRight' ? 48 / scale : event.key === 'ArrowLeft' ? -48 / scale : 0),
        z: center.z + (event.key === 'ArrowDown' ? 48 / scale : event.key === 'ArrowUp' ? -48 / scale : 0) };
      hideHover();
    } else return;
    event.preventDefault();
  }
  function requestPaint(..._values: unknown[]) {
    if (!animation) animation = requestAnimationFrame(paint);
  }
  function strokePosition(value: number, physicalWidth: number, dpr: number) {
    return (Math.round(value * dpr) + (physicalWidth % 2 ? .5 : 0)) / dpr;
  }
  function gridLines(ctx: CanvasRenderingContext2D, blocks: number, left: number, top: number,
    physicalWidth: number, dpr: number) {
    ctx.lineWidth = physicalWidth / dpr;
    ctx.beginPath();
    for (let x = Math.floor(left / blocks) * blocks; x < left + width / scale; x += blocks) {
      const px = strokePosition((x - left) * scale, physicalWidth, dpr); ctx.moveTo(px, 0); ctx.lineTo(px, height);
    }
    for (let z = Math.floor(top / blocks) * blocks; z < top + height / scale; z += blocks) {
      const py = strokePosition((z - top) * scale, physicalWidth, dpr); ctx.moveTo(0, py); ctx.lineTo(width, py);
    }
    ctx.stroke();
  }
  function sourceName(source: ChunkTicketSource) {
    const owner = source.owner?.name ?? source.owner?.uuid;
    return `${source.type}${source.modId ? ` · ${source.modId}` : ''}${owner ? ` · ${source.owner!.kind}: ${owner}` : source.key ? ` · ${source.key}` : source.owner ? ` · ${source.owner.kind}` : ''}`;
  }
  function runtimeName(state: ChunkState) {
    if (state.entityTicking == null) return state.entityTickingReady ? 'Entity-ticking future ready'
      : state.blockTickingReady ? 'Block-ticking future ready' : state.fullReady ? 'Full future ready'
        : state.loaded ? 'Chunk present' : 'Holder only';
    return `${state.entityTicking ? 'Entity ticking' : state.blockTicking ? 'Block ticking' : state.loaded ? 'Loaded' : 'Holder only'}${state.fullStatus ? ` · ${state.fullStatus}` : ''}`;
  }
  function chunkSources(state: ChunkState) {
    const sources = telemetry.data?.ticketSources ?? [];
    return state.directTickets.map(index => sources[index]).filter((source): source is ChunkTicketSource => !!source);
  }
  function clipScope(ctx: CanvasRenderingContext2D, scope: ChunkTelemetryScope | null, left: number, top: number) {
    if (!scope) return;
    if (scope.minX !== undefined && scope.maxX !== undefined && scope.minZ !== undefined && scope.maxZ !== undefined) {
      ctx.beginPath(); ctx.rect((scope.minX - left) * scale, (scope.minZ - top) * scale,
        (scope.maxX - scope.minX) * scale, (scope.maxZ - scope.minZ) * scale); ctx.clip();
    }
    if (scope.kind === 'radius' && scope.radius !== undefined && scope.centerX !== undefined && scope.centerZ !== undefined) {
      ctx.beginPath(); ctx.arc((scope.centerX - left) * scale, (scope.centerZ - top) * scale, scope.radius * scale, 0, Math.PI * 2); ctx.clip();
    }
  }
  function paintLayers(ctx: CanvasRenderingContext2D, left: number, top: number) {
    if (layerScope?.dimension && layerScope.dimension !== dimension) return;
    ctx.save(); clipScope(ctx, layerScope, left, top);
    if (chunkLayers && layerScope) {
      ctx.strokeStyle = '#9ca3af'; ctx.lineWidth = 1.5; ctx.globalAlpha = .8; ctx.setLineDash([5, 4]);
      if (layerScope.kind === 'radius' && layerScope.centerX !== undefined && layerScope.centerZ !== undefined && layerScope.radius !== undefined) {
        ctx.beginPath(); ctx.arc((layerScope.centerX - left) * scale, (layerScope.centerZ - top) * scale,
          layerScope.radius * scale, 0, Math.PI * 2); ctx.stroke();
      } else if (layerScope.minX !== undefined && layerScope.maxX !== undefined && layerScope.minZ !== undefined && layerScope.maxZ !== undefined)
        ctx.strokeRect((layerScope.minX - left) * scale, (layerScope.minZ - top) * scale,
          (layerScope.maxX - layerScope.minX) * scale, (layerScope.maxZ - layerScope.minZ) * scale);
      ctx.setLineDash([]); ctx.globalAlpha = 1;
    }
    const pixels = 16 * scale;
    const modes = new Map(effectiveLayers.runtime ? zoneChunks.map(chunk => [`${chunk.x},${chunk.z}`, runtimeName(chunk.state)]) : []);
    const borders = new Map<string, { path: Path2D; loaded: boolean }>();
    for (const chunk of zoneChunks) {
      const state = chunk.state, x = (chunk.x * 16 - left) * scale, y = (chunk.z * 16 - top) * scale;
      if (effectiveLayers.runtime) {
        const mode = runtimeName(state);
        ctx.fillStyle = layerColor(mode); ctx.globalAlpha = state.loaded ? .24 : .09;
        ctx.fillRect(x, y, pixels, pixels);
        let border = borders.get(mode);
        if (!border) { border = { path: new Path2D(), loaded: state.loaded === true }; borders.set(mode, border); }
        for (const [dx, dz, x1, y1, x2, y2] of [[-1, 0, x, y, x, y + pixels], [1, 0, x + pixels, y, x + pixels, y + pixels],
          [0, -1, x, y, x + pixels, y], [0, 1, x, y + pixels, x + pixels, y + pixels]]) {
          if (modes.get(`${chunk.x + dx},${chunk.z + dz}`) !== mode) { border.path.moveTo(x1, y1); border.path.lineTo(x2, y2); }
        }
      }
      if (effectiveLayers.generation && state.generationStatus) {
        ctx.fillStyle = layerColor(state.generationStatus); ctx.globalAlpha = .12; ctx.fillRect(x, y, pixels, pixels);
        if (pixels >= 6) {
          ctx.globalAlpha = .8; ctx.strokeStyle = layerColor(state.generationStatus); ctx.lineWidth = 1.5; ctx.setLineDash([2, 2]);
          ctx.strokeRect(x + 2, y + 2, pixels - 4, pixels - 4);
        }
      }
      if (effectiveLayers.levels) {
        const level = layers.levelChannel === 'loading' ? state.ticketLevel : state.simulationLevel;
        if (level != null && Number.isFinite(level)) {
          ctx.globalAlpha = .12; ctx.fillStyle = `hsl(${Math.max(0, Math.min(220, level * 5))} 70% 60%)`;
          ctx.fillRect(x, y, pixels, pixels);
          if (pixels >= 28 && ctx.measureText(String(level)).width + 8 <= pixels) {
            ctx.globalAlpha = 1; ctx.fillStyle = '#f5f5f5'; ctx.fillText(String(level), x + 4, y + pixels - 5);
          }
        }
      }
      if (effectiveLayers.sources) {
        const direct = chunkSources(state), influence = state.influencingTickets;
        const first = direct[0] ?? telemetry.data?.ticketSources[influence[0]?.source];
        const managed = state.managedSources?.[0];
        if (first || managed) {
          ctx.strokeStyle = layerColor(first?.type ?? `${managed!.kind} · ${managed!.modId} · ${managed!.mode}`); ctx.lineWidth = direct.length ? 2.5 : 1; ctx.globalAlpha = .9;
          ctx.setLineDash(direct.length ? [] : managed && !first ? [1, 3] : [4, 3]);
          if (pixels >= 4) ctx.strokeRect(x + 1, y + 1, pixels - 2, pixels - 2);
          else { ctx.fillStyle = ctx.strokeStyle; ctx.globalAlpha = .4; ctx.fillRect(x, y, pixels, pixels); }
          if (direct.length && pixels >= 12) { ctx.fillStyle = ctx.strokeStyle; ctx.beginPath(); ctx.arc(x + 5, y + 5, 3, 0, Math.PI * 2); ctx.fill(); }
        }
      }
      if (effectiveLayers.visibility && (state.watchers.length || state.pendingSends?.length || state.inView?.length)) {
        const color = state.watchers.length ? '#d8b4fe' : state.pendingSends?.length ? '#fcd34d' : '#94a3b8';
        ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.globalAlpha = .9; ctx.setLineDash(state.watchers.length ? [6, 2] : [2, 3]);
        if (pixels >= 8) ctx.strokeRect(x + 3, y + 3, pixels - 6, pixels - 6);
        else { ctx.fillStyle = color; ctx.globalAlpha = .2; ctx.fillRect(x, y, pixels, pixels); }
      }
    }
    for (const [mode, border] of borders) {
      ctx.globalAlpha = .95; ctx.strokeStyle = layerColor(mode); ctx.lineWidth = 1.5;
      ctx.setLineDash(border.loaded ? [] : [3, 3]); ctx.stroke(border.path);
    }
    ctx.setLineDash([]); ctx.globalAlpha = 1;
    const labels: { x: number; y: number; width: number }[] = [];
    for (const player of layerPlayers) {
      const x = (player.x - left) * scale, y = (player.z - top) * scale;
      if (x < -10 || x > width + 10 || y < -10 || y > height + 10) continue;
      ctx.fillStyle = '#e0f2fe'; ctx.strokeStyle = '#082f49'; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
      ctx.font = '12px ui-monospace, monospace';
      const label = `${player.name} · ${Math.floor(player.x)}, ${Math.floor(player.y)}, ${Math.floor(player.z)}`;
      const labelWidth = ctx.measureText(label).width;
      const labelX = Math.max(2, Math.min(width - labelWidth - 6, x + 8));
      let labelY = Math.max(14, Math.min(height - 4, y - 8));
      for (let attempt = 0; attempt < layerPlayers.length; attempt++) {
        if (!labels.some(r => labelX < r.x + r.width && labelX + labelWidth > r.x && Math.abs(labelY - r.y) < 17)) break;
        labelY = labelY + 18 <= height - 4 ? labelY + 18 : Math.max(14, labelY - 36);
      }
      labels.push({ x: labelX, y: labelY, width: labelWidth });
      ctx.strokeStyle = '#7dd3fc'; ctx.lineWidth = .75; ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(labelX, labelY - 5); ctx.stroke();
      ctx.fillStyle = '#082f49e6'; ctx.fillRect(labelX - 2, labelY - 12, labelWidth + 4, 16);
      ctx.fillStyle = '#e0f2fe'; ctx.fillText(label, labelX, labelY);
    }
    ctx.restore();
  }
  function paint() {
    animation = 0;
    if (!width || !height) return;
    const dpr = window.devicePixelRatio || 1;
    // Keep raster resizing local; assigning properties on the Svelte-bound canvas would requeue paint.
    const surface = canvas;
    surface.width = Math.ceil(width * dpr); surface.height = Math.ceil(height * dpr);
    const ctx = surface.getContext('2d')!;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = '#17191a'; ctx.fillRect(0, 0, width, height);
    const left = center.x - width / 2 / scale, top = center.z - height / 2 / scale;
    const step = size * scale;
    ctx.font = '11px ui-monospace, monospace';
    // Empty cells are one background plus grid lines, even at the 2px merge limit.
    ctx.globalAlpha = .12; ctx.fillStyle = '#9ca3af'; ctx.fillRect(0, 0, width, height);
    if (parentSize !== undefined && parentCells) {
      ctx.globalAlpha = .10;
      const parentStep = parentSize * scale;
      for (const cell of parentCells.values()) {
        const px = (cell.rect.minX - left) * scale, py = (cell.rect.minZ - top) * scale;
        if (px + parentStep < 0 || py + parentStep < 0 || px > width || py > height) continue;
        ctx.fillStyle = loadColor(parentMaximum > 0 ? cell.metric.value / parentMaximum : 0);
        ctx.fillRect(px, py, parentStep, parentStep);
      }
    }
    ctx.globalAlpha = 1; ctx.strokeStyle = '#17191a';
    // Below 4px the minor grid would obscure the heat; the parent grid remains visible.
    if (step >= 4) gridLines(ctx, size, left, top, 1, dpr);
    for (const cell of cells.values()) {
      const px = (cell.rect.minX - left) * scale, py = (cell.rect.minZ - top) * scale;
      if (px + step < 0 || py + step < 0 || px > width || py > height) continue;
      ctx.globalAlpha = cell.coverage >= 1 ? .9 : .58;
      ctx.fillStyle = loadColor(maximum > 0 ? cell.metric.value / maximum : 0);
      ctx.fillRect(px + .5, py + .5, Math.max(.5, step - 1), Math.max(.5, step - 1));
      if (step > 65) {
        ctx.globalAlpha = 1; ctx.fillStyle = '#fff';
        ctx.save(); ctx.beginPath(); ctx.rect(px + 3, py, step - 6, step); ctx.clip();
        ctx.fillText(metricLabel(cell.metric.value), px + 4, py + 17); ctx.restore();
      }
    }
    if (parentSize !== undefined) {
      const parentStep = parentSize * scale;
      ctx.globalAlpha = parentStep < 8 ? .45 : .68;
      ctx.strokeStyle = '#c4c8cc';
      gridLines(ctx, parentSize, left, top, parentStep < 8 ? 1 : parentStep < 16 ? 2 : Math.max(2, Math.round(2 * dpr)), dpr);
    }
    ctx.globalAlpha = 1;
    paintLayers(ctx, left, top);
    const axisWidth = Math.max(3, Math.round(3 * dpr));
    ctx.strokeStyle = '#e4e7eb'; ctx.lineWidth = axisWidth / dpr; ctx.beginPath();
    if (layers.axes && origin.x >= 0 && origin.x <= width) {
      const x = strokePosition(origin.x, axisWidth, dpr);
      ctx.moveTo(x, 0); ctx.lineTo(x, height);
    }
    if (layers.axes && origin.y >= 0 && origin.y <= height) {
      const y = strokePosition(origin.y, axisWidth, dpr);
      ctx.moveTo(0, y); ctx.lineTo(width, y);
    }
    ctx.stroke();
    for (const rect of shownSelection) {
      const x = (rect.minX - left) * scale, y = (rect.minZ - top) * scale;
      ctx.fillStyle = '#e5e7eb22'; ctx.fillRect(x, y, (rect.maxX - rect.minX) * scale, (rect.maxZ - rect.minZ) * scale);
    }
    ctx.strokeStyle = '#f5f5f5'; ctx.lineWidth = 2; ctx.beginPath();
    for (const edge of outline) {
      ctx.moveTo((edge.x1 - left) * scale, (edge.z1 - top) * scale);
      ctx.lineTo((edge.x2 - left) * scale, (edge.z2 - top) * scale);
    }
    ctx.stroke();
    if (draft) {
      ctx.strokeStyle = drag?.subtract ? '#fdba74' : '#d4d4d4'; ctx.lineWidth = 1;
      ctx.setLineDash([5, 3]);
      ctx.strokeRect((draft.minX - left) * scale, (draft.minZ - top) * scale,
        (draft.maxX - draft.minX) * scale, (draft.maxZ - draft.minZ) * scale);
      ctx.setLineDash([]);
    }
    ctx.fillStyle = '#171717dd'; ctx.fillRect(width - ruler.pixels - 24, height - 42, ruler.pixels + 16, 36);
    ctx.strokeStyle = '#f5f5f5'; ctx.lineWidth = 1.5; ctx.beginPath();
    const rulerX = width - ruler.pixels - 16, rulerY = height - 15;
    ctx.moveTo(rulerX, rulerY - 4); ctx.lineTo(rulerX, rulerY + 4);
    ctx.moveTo(rulerX, rulerY); ctx.lineTo(rulerX + ruler.pixels, rulerY);
    ctx.moveTo(rulerX + ruler.pixels, rulerY - 4); ctx.lineTo(rulerX + ruler.pixels, rulerY + 4); ctx.stroke();
    ctx.fillStyle = '#eee'; ctx.textAlign = 'center';
    ctx.fillText(`${number.format(ruler.blocks)} blocks`, rulerX + ruler.pixels / 2, rulerY - 10);
    ctx.textAlign = 'left';
    if (layers.axes) ctx.fillText('N ↑', width - 36, 18);
  }
</script>

<svelte:window on:click={windowClick} on:click|capture={consumeTouchMenuClick} on:pointerdown|capture={() => touchMenuClick = false}
  on:resize={positionLayers} on:scroll={positionLayers}
  on:keydown={(event) => { if (event.key === 'Escape') { context = null; optionsOpen = false; layersOpen = false; hideHover(); } }} />

<div class="heatmap-page">
  <div class="map-controls">
    <div bind:this={optionsMenu} class="map-options" class:compact>
      <button type="button" class="options-toggle" aria-label="Map options" aria-controls="heatmap-options" aria-expanded={optionsOpen}
        on:click={() => optionsOpen = !optionsOpen}>Options</button>
      <div id="heatmap-options" class="options-fields" class:closed={compact && !optionsOpen}>
        <label class="map-label dimension-option">Dimension <select bind:value={dimension} class="map-input">
          {#each dimensions as name}<option value={name}>{name}</option>{/each}
        </select></label>
        <label class="map-label source-option">Source <select bind:value={source} class="map-input">
          <option value="blocks">Blocks</option><option value="entities">Entities</option><option value="all">All</option>
        </select></label>
        <label class="map-label merge-option" title="Cell width in pixels before the next merge level">Merge
          <input aria-label="Merge sensitivity" type="range" min={minimumMerge} max={maximumMerge} step="1" bind:value={merge} class="w-24 accent-neutral-400" />
          <span class="w-10 text-neutral-400">{merge}px</span>
        </label>
      </div>
    </div>
    <div bind:this={layersMenu} class="map-layers">
      <button type="button" class="map-button" aria-label="Map layers" aria-controls="heatmap-layers" aria-expanded={layersOpen}
        on:click={toggleLayers}>Layers</button>
      {#if layersOpen}
      <div id="heatmap-layers" class="layers-fields" style:left={`${layersLeft}px`} style:top={`${layersTop}px`}
        style:max-height={`min(36rem, 70dvh, calc(100dvh - ${layersTop + 8}px))`}>
        {#each layerOptions as option}
          <label class="flex items-center gap-2 text-sm" title={layerAvailable[option.key] ? undefined : 'Not recorded in this report'}><input type="checkbox" checked={layers[option.key]} class="accent-neutral-400"
            disabled={!layerAvailable[option.key]}
            on:change={(event) => layers = { ...layers, [option.key]: event.currentTarget.checked }} />{option.label}{!layerAvailable[option.key] ? ' · not recorded' : ''}</label>
        {/each}
        {#if telemetry.available}
          <label class="flex items-center gap-2 text-sm">State tick
            <input aria-label="Layer state tick" class="map-input w-24" type="number" min="1" max={Math.max(1, data.timelineTicks)}
              value={layerTick} disabled={mode === 'tick'} on:change={(event) => { const value = event.currentTarget.valueAsNumber;
                if (Number.isFinite(value)) stateTick = Math.max(1, Math.min(data.timelineTicks, Math.trunc(value))); }} />
          </label>
          {#if stateLayers && !layerStatus.available}<p class="text-xs text-amber-200">{layerStatus.reason}</p>{/if}
          {#if chunkLayers && layerScope && layerScope.kind !== 'all'}<p class="text-xs text-neutral-400">Dashed boundary: recorded {layerScope.kind} scope</p>{/if}
          {#if effectiveLayers.runtime}
            <div class="layer-legend">{#each runtimeLegend as name}<span><i style:background={layerColor(name)}></i>{name}</span>{/each}</div>
          {/if}
          {#if effectiveLayers.generation}
            <div class="layer-legend">{#each generationLegend as name}<span><i style:background={layerColor(name)}></i>{name}</span>{/each}</div>
          {/if}
          {#if effectiveLayers.levels}
            <label class="flex items-center gap-2 text-sm">Level channel <select aria-label="Ticket level channel" class="map-input"
              value={layers.levelChannel} on:change={(event) => layers = { ...layers, levelChannel: event.currentTarget.value === 'simulation' ? 'simulation' : 'loading' }}>
              <option value="loading">Loading</option><option value="simulation">Simulation</option>
            </select></label>
            <p class="text-xs text-neutral-400">{layers.levelChannel === 'loading' ? 'Loading' : 'Simulation'} effective level · lower is stronger</p>
            {#if layers.levelChannel === 'simulation' && !telemetry.data?.capabilities.simulationLevel}<p class="text-xs text-amber-200">Simulation level unavailable</p>{/if}
          {/if}
          {#if effectiveLayers.sources}
            <p class="text-xs text-neutral-400">Solid: direct · dashed: propagated candidate</p>
            <div class="layer-legend">{#each sourceLegend as name}<span><i style:background={layerColor(name)}></i>{name}</span>{/each}</div>
          {/if}
          {#if effectiveLayers.visibility}
            <div class="layer-legend">
              <span><i style:background={'#d8b4fe'}></i>{telemetry.data?.capabilities.watchers === 'server_view_range' ? 'Server view range' : 'Server tracking'}</span>
              {#if telemetry.data?.capabilities.pendingSends === 'observed'}<span><i style:background={'#fcd34d'}></i>Pending send</span><span><i style:background={'#94a3b8'}></i>View only</span>{/if}
            </div>
            <p class="text-xs text-neutral-400">Client rendering unknown</p>
          {/if}
        {:else}<p class="text-xs text-neutral-400">{Object.values(recorded).some(Boolean) ? telemetry.reason : 'Map layers not recorded'}</p>{/if}
      </div>
      {/if}
    </div>
    <div class="map-values"><ValueControls {mode} tick={selectedTick} lastTick={Math.max(1, data.timelineTicks)} tickStarts={data.profile.tickStarts}
      hasPeaks={index.history} hasTicks={index.history || telemetry.available}
      on:change={(event) => { mode = event.detail.mode; selectedTick = event.detail.tick; }} /></div>
    {#if selection.length}<button class="map-button map-clear" on:click={clearSelection}>Clear selection</button>{/if}
  </div>
  <div class="flex flex-wrap items-center gap-2 text-sm">
    <button class="map-button" aria-label="Zoom in" on:click={() => zoom(1.7)}>+</button>
    <button class="map-button" aria-label="Zoom out" on:click={() => zoom(1 / 1.7)}>−</button>
    <button class="map-button" on:click={fit}>Fit</button>
    {#if origin.x < 0 || origin.x > width || origin.y < 0 || origin.y > height}
    <button class="map-button" aria-label="Go to world origin" title="World origin"
      on:click={() => { autoFit = false; cancelZoom(); center = { x: 0, z: 0 }; hideHover(); context = null; }}>
      <svg width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <circle cx="10" cy="10" r="4" />
        <path d="M10 1v4m0 10v4M1 10h4m10 0h4" />
      </svg>
    </button>
    {/if}
    <span class="text-neutral-300">{mapNames[mapSizes.indexOf(size)]} · {size} × {size} blocks</span>
    {#if summary}<span class="text-neutral-300">Selected {summary.objects} entries · {metricLabel(summary.value)} · {percent.format(summary.percent)}%{mode === 'peak' ? ` · tick ${summary.tick}` : ''}</span>{/if}
    <span class="ml-auto text-xs text-neutral-400" title="A chunk with at least one recorded block or entity tick">Ticked chunks</span>
    <span class="h-2 w-20 rounded" style="background:linear-gradient(to right,hsl(120 62% 46%),hsl(60 62% 46%),hsl(20 62% 46%))"></span>
    <span class="text-xs text-neutral-400">0 — {metricLabel(maximum)}</span>
    {#if stateLayers && telemetry.available}<span class="text-xs text-neutral-400">State tick {layerTick}{!layerStatus.available ? ' · unavailable' : ''}</span>{/if}
  </div>
  <div bind:this={viewport} class="map-viewport" role="grid" tabindex="0" aria-label="Top-down heatmap" aria-rowcount="-1" aria-colcount="-1"
    aria-describedby="map-help" on:pointerdown={pointerdown} on:pointermove={pointermove} on:pointerup={pointerup}
    on:pointercancel={cancelGesture} on:lostpointercapture={lostCapture} on:pointerleave={pointerleave}
    on:contextmenu={openContext} on:keydown={keydown}>
    <canvas bind:this={canvas} class="block" style:width={`${width}px`} style:height={`${height}px`} aria-hidden="true"></canvas>
    {#if cursor}<output class="pointer-events-none absolute bottom-1 left-2 font-mono text-[11px] text-neutral-300"
      aria-label="Cursor block position" aria-live="off">X {cursor.x}, Z {cursor.z}</output>{/if}
    <div class="sr-only" role="row"><span role="gridcell">{dimension} · X {Math.floor(center.x)}, Z {Math.floor(center.z)} · {size} × {size} blocks. Scale: {number.format(ruler.blocks)} blocks. Arrow keys pan, plus and minus zoom.</span></div>
    <p id="map-help" class="sr-only">Drag to pan. Scroll to zoom. Ctrl-drag adds from outside or cuts from inside the selected area. Right-click for actions.</p>
    {#if !entries.length}<p class="pointer-events-none absolute left-4 top-4 text-neutral-300">No recorded objects in this dimension.</p>{/if}
  </div>
</div>

{#if tooltip && info && pointer}
  <div role="tooltip" class="map-tooltip" style:left={`${Math.max(8, Math.min(pointer.x + 14, window.innerWidth - 336))}px`}
    style:top={`${Math.max(8, Math.min(pointer.y + 14, window.innerHeight - 430))}px`}>
    <strong>{selection.length ? 'Selection' : mapNames[mapSizes.indexOf(size)]}</strong>
    <p class="break-all text-neutral-300">{dimension}</p>
    {#each tooltipRects.slice(0, 4) as rect}<p>{rectLabel(rect)}</p>{/each}
    {#if tooltipRects.length > 4}<p>+ {tooltipRects.length - 4} rectangles</p>{/if}
    <p class="mt-2 font-semibold">{mode === 'average' ? 'Average' : mode === 'peak' ? 'Peak' : 'Tick'} {metricLabel(info.value)}{mode !== 'average' ? ` · tick ${info.tick}` : ''}</p>
    <p>{percent.format(info.percent)}% of {scopeName()} measured object time</p>
    <p class="text-neutral-400">Denominator: {metricLabel(info.denominator)} · {dimension}{mode !== 'average' ? ` · tick ${info.tick}` : ''}</p>
    <p class="mt-2">{info.objects} entries · {info.blocks} blocks · {info.entities} entities · {info.columns} XZ columns</p>
    <p>{info.recordedTicks === undefined ? '' : `${info.recordedTicks} ticks · `}{info.recordedCalls} recorded object calls</p>
    {#if info.minY !== undefined}<p>Recorded Y: {info.minY}…{info.maxY}</p>{/if}
    <p>Ticked chunks: {info.coveredChunks}/{info.totalChunks} ({percent.format(info.coverage * 100)}%) · {info.coverage >= 1 ? 'Full' : info.coverage > 0 ? 'Partial' : 'None'}</p>
    <p class="text-neutral-400">{mode === 'average' ? 'During recording' : `Tick ${info.tick}`} · Both sources</p>
    {#each info.hottest as item}<p class="mt-1 break-all text-neutral-300">{item.type} · Y {item.y} · {metricLabel(item.value)}</p>{/each}
    <p class="mt-1 text-neutral-400">Positions saved in the report. Blocks include block entities, scheduled blocks and fluids.</p>
    {#if hoveredChunk && cursor}
      <div class="mt-2 border-t border-neutral-700 pt-2">
        <strong>Chunk {Math.floor(cursor.x / 16)}, {Math.floor(cursor.z / 16)} · state tick {layerTick}</strong>
        {#if !hoveredChunk.available || !hoveredChunk.chunk}<p>{hoveredChunk.reason}</p>
        {:else}
          {@const state = hoveredChunk.chunk.state}
          {#if layerAvailable.runtime}
            <p>{state.loaded ? 'Chunk present' : 'No completed chunk'}</p>
            <p>Policy: {state.fullStatus ?? 'unavailable'}</p>
            <p>Ticking: block {state.blockTicking == null ? 'unavailable' : state.blockTicking ? 'yes' : 'no'} · entity {state.entityTicking == null ? 'unavailable' : state.entityTicking ? 'yes' : 'no'}</p>
            {#if state.forcedTicking != null}<p>Native forced ticking: {state.forcedTicking ? 'yes' : 'no'}</p>{/if}
            <p>Ready: full {state.fullReady ? 'yes' : 'no'} · block ticking {state.blockTickingReady ? 'yes' : 'no'} · entity ticking {state.entityTickingReady ? 'yes' : 'no'}</p>
            <p>Pending unload: {state.pendingUnload == null ? 'unavailable' : state.pendingUnload ? 'yes' : 'no'}</p>
          {/if}
          {#if layerAvailable.generation}<p>Generation: {state.generationStatus ?? 'unavailable'}{state.targetGenerationStatus ? ` · target ${state.targetGenerationStatus}` : ''}</p>{/if}
          {#if layerAvailable.levels}
          <p>Loading level {state.ticketLevel ?? 'unavailable'} · simulation {state.simulationLevel ?? 'unavailable'}</p>
          {/if}
          {#if layerAvailable.sources}
          {#each chunkSources(state).slice(0, 8) as ticket}
            <p class="break-all">Direct · {sourceName(ticket)} · level {ticket.level} · {ticket.channel} · chunk {ticket.x}, {ticket.z}{ticket.forceTicks === undefined ? '' : ` · force ticks ${ticket.forceTicks ? 'yes' : 'no'}`}{ticket.outsideScope ? ' · outside scope' : ''}</p>
          {/each}
          {#if state.directTickets.length > 8}<p>+ {state.directTickets.length - 8} direct tickets</p>{/if}
          {#each state.influencingTickets.slice(0, 8) as influence}
            {@const ticket = telemetry.data?.ticketSources[influence.source]}
            {#if ticket}<p class="break-all">Propagated {influence.relation === 'equal' ? 'level match' : 'candidate'} · {sourceName(ticket)} · {influence.channel} · chunk {ticket.x}, {ticket.z} · distance {influence.distance} · level {influence.computedLevel}</p>{/if}
          {/each}
          {#if state.influencingTickets.length > 8}<p>+ {state.influencingTickets.length - 8} propagated candidates</p>{/if}
          {#each state.managedSources?.slice(0, 8) ?? [] as group}<p class="break-all">Managed group · {group.kind} · {group.modId} · {group.mode}{group.owner?.name || group.owner?.uuid ? ` · ${group.owner.name ?? group.owner.uuid}` : group.key ? ` · ${group.key}` : ''}</p>{/each}
          <p class="text-neutral-400">{telemetry.data?.capabilities.ticketOwners === 'not_exposed' ? 'Ticket owners not exposed' : telemetry.data?.capabilities.ticketOwners === 'managed_groups_only' ? 'Managed groups only' : 'Native ticket keys'}</p>
          {/if}
          {#if layerAvailable.visibility}
          <p>Visible-map holder: {state.visibleHolder == null ? 'unavailable' : state.visibleHolder ? 'yes' : 'no'}</p>
          <p>{telemetry.data?.capabilities.watchers === 'server_view_range' ? 'Server view range' : 'Server tracking'}: {state.watchers.length}</p>
          <p>In view: {state.inView?.length ?? 'unavailable'} · pending sends: {state.pendingSends?.length ?? 'unavailable'}</p>
          {#each state.watchers.slice(0, 6) as uuid}<p class="break-all">{playerNames.get(uuid) ?? uuid}</p>{/each}
          {#each state.pendingSends?.slice(0, 6) ?? [] as uuid}<p class="break-all">Pending send · {playerNames.get(uuid) ?? uuid}</p>{/each}
          <p class="text-neutral-400">Server tracking, not client rendering</p>
          {/if}
        {/if}
      </div>
    {/if}
  </div>
{/if}

{#if context && contextInfo}
  <div bind:this={menu} role="menu" aria-label="Map actions" class="map-menu" style:left={`${Math.max(4, context.x)}px`} style:top={`${Math.max(4, context.y)}px`}>
    <a role="menuitem" class="menu-action" href={mapIndividualUrl(base, dimension, source, context.rects, mode === 'peak' ? 'tick' : mode, contextTick)}>Open Individual</a>
    {#if teleport}<button role="menuitem" class="menu-action" title={teleport.y === '~' ? 'Keep your Y; no unique recorded height at the clicked column' : `Recorded Y ${teleport.y}`}
      on:click={() => { copyTPCommand(dimension, teleport); context = null; }}>Copy teleport command</button>{/if}
    <button role="menuitem" class="menu-action" disabled={!selection.length} on:click={clearSelection}>Clear selection</button>
  </div>
{/if}

<style lang="postcss">
  .heatmap-page { @apply flex min-h-0 min-w-0 flex-1 flex-col gap-3 pt-2; }
  .map-controls { @apply flex flex-wrap items-center gap-3; }
  .map-options, .options-fields { display: contents; }
  .map-layers { position: relative; order: 3; }
  .layers-fields { @apply fixed z-30 flex w-72 max-w-[calc(100vw-1rem)] flex-col gap-2 overflow-y-auto rounded-lg border border-neutral-600 bg-neutral-950 p-3 shadow-xl; }
  .layer-legend { @apply flex flex-col gap-1 border-t border-neutral-800 pt-2 text-xs text-neutral-300; }
  .layer-legend span { @apply flex items-center gap-2 break-all; }
  .layer-legend i { @apply inline-block h-2 w-2 shrink-0 rounded-sm; }
  .options-toggle { display: none; }
  .dimension-option { order: 0; }
  .source-option { order: 1; }
  .map-values { order: 2; min-width: 0; }
  .merge-option { order: 3; }
  .map-clear { order: 4; }
  .map-options.compact { display: block; position: relative; order: 3; }
  .map-options.compact .options-toggle { @apply block rounded-md border border-neutral-700 bg-neutral-900 px-2.5 py-2 text-sm hover:bg-neutral-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-neutral-300; }
  .map-options.compact .options-fields { @apply absolute right-0 z-30 flex w-80 max-w-[calc(100vw-1rem)] flex-col gap-3 rounded-lg border border-neutral-600 bg-neutral-950 p-3 shadow-xl; top: calc(100% + 4px); }
  .map-options.compact .options-fields.closed { display: none; }
  .map-label { @apply flex min-w-0 items-center gap-2 text-sm; }
  .map-input { @apply min-w-0 max-w-[15rem] rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5; }
  .map-button { @apply rounded-md border border-neutral-700 bg-neutral-900 px-2.5 py-1.5 enabled:hover:bg-neutral-700 disabled:opacity-40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-neutral-300; }
  .map-viewport { @apply relative min-h-0 w-full flex-1 overflow-hidden rounded-md border border-neutral-700; touch-action: none; cursor: grab; }
  .map-viewport:active { cursor: grabbing; }
  .map-viewport:focus-visible { @apply outline outline-2 outline-offset-1 outline-neutral-300; }
  .map-tooltip { @apply pointer-events-none fixed z-40 w-80 max-w-[calc(100vw-1rem)] rounded-lg border border-neutral-600 bg-neutral-950/95 p-3 text-xs leading-relaxed text-neutral-100 shadow-xl; }
  .map-menu { @apply fixed z-50 min-w-56 rounded-lg border border-neutral-600 bg-neutral-950 p-1 text-sm shadow-xl; }
  .menu-action { @apply block w-full rounded px-3 py-2 text-left enabled:hover:bg-neutral-700 hover:bg-neutral-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-neutral-300 disabled:opacity-40; }
</style>
