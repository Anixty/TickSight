<script lang="ts">
  import { onMount, tick } from 'svelte';
  import {
    layoutFlame, flameNodeDetail, flameColoring, flameColorDetail, heatColor, type FlameTree, type FlameFrame
  } from './flamegraph';

  export let tree: FlameTree;
  export let id: string;
  export let colored = true;

  const rowHeight = 18;
  const inset = 8;
  let graph: HTMLDivElement;
  let body: HTMLDivElement;
  let path: HTMLDivElement;
  let canvas: HTMLCanvasElement;
  let movingCanvas: HTMLCanvasElement;
  let tooltip: HTMLDivElement;
  let width = 0;
  let paintHeight = 0;
  let paintTop = 0;
  let screenHeight = 0;
  let screenWidth = 0;
  let dpr = 1;
  let root = 0;
  let focused = 0;
  let hovered: number | null = null;
  let tooltipMode: 'pointer' | 'keyboard' | null = null;
  let anchorX = 0;
  let anchorY = 0;
  let tooltipWidth = 0;
  let tooltipHeight = 0;
  let measureFrame = 0;
  let focusVersion = 0;
  let background: HTMLCanvasElement;
  let lastPaint: unknown[] = [];
  let animationFrame = 0;
  let reducedMotion = false;
  let mounted = false;

  type Position = { x: number; width: number; y: number; rgb: number[] };
  type MovingFrame = { index: number; depth: number; from: Position; to: Position; pinned: boolean };
  type Navigation = {
    tree: FlameTree; rows: MovingFrame[][]; pinned: MovingFrame[]; frames: Map<number, MovingFrame>;
    pathLength: number; progress: number; pinOffset: number; height: number; targetHeight: number;
    fromScroll: number; toScroll: number; started: number; version: number; keyboard: boolean;
    hits: { index: number; x: number; y: number; width: number }[];
  };
  let navigation: Navigation | undefined;

  $: originalLayout = layoutFlame(tree);
  $: layout = layoutFlame(tree, root);
  $: colors = flameColoring(tree, layout);
  $: bodyHeight = Math.max(0, layout.rows.length - layout.path.length) * rowHeight;
  // A long path scrolls naturally before its last rows stick. It never consumes the whole viewport.
  $: pathTop = Math.min(0, Math.floor(screenHeight * 0.35 / rowHeight) * rowHeight - layout.path.length * rowHeight);
  $: inspected = tooltipMode === 'pointer' ? hovered ?? focused : focused;
  $: node = tree.nodes[inspected];
  $: detail = node ? flameNodeDetail(node, tree.unit, tree.nodes[node.parent]) : '';
  $: ancestor = !!layout.frames[inspected]?.ancestor;
  $: shareBase = tree.nodes[ancestor ? layout.path[0] : root]?.weight ?? 0;
  $: share = shareBase > 0 ? 100 * (node?.weight ?? 0) / shareBase : 0;
  $: widthDetail = `${share.toFixed(2)}% of ${ancestor ? 'recording · ancestor' : 'view'}`;
  $: colorDetail = node && !ancestor && colored ? flameColorDetail(tree, inspected, colors) : '';
  $: focusedNode = tree.nodes[focused];
  $: focusedAncestor = !!layout.frames[focused]?.ancestor;
  $: focusedBase = tree.nodes[focusedAncestor ? layout.path[0] : root]?.weight ?? 0;
  $: focusedDescription = focusedNode
    ? `${focusedNode.label}. ${flameNodeDetail(focusedNode, tree.unit, tree.nodes[focusedNode.parent])}`
      + (focusedBase ? ` · ${(100 * focusedNode.weight / focusedBase).toFixed(2)}% of ${focusedAncestor ? 'recording · ancestor' : 'view'}` : '')
      + (!focusedAncestor && colored ? ` · ${flameColorDetail(tree, focused, colors)}` : '')
    : '';
  $: metricLabel = tree.metric === 'self' ? 'Self time' : tree.metric === 'per-parent' ? 'Calls per parent'
    : tree.metric === 'samples' ? 'Samples' : 'Total time';
  $: tooltipLeft = Math.max(inset, Math.min(anchorX + 12, screenWidth - tooltipWidth - inset));
  $: tooltipTop = Math.max(inset, Math.min(
    anchorY + 16 + tooltipHeight <= screenHeight - inset ? anchorY + 16 : anchorY - tooltipHeight - 12,
    screenHeight - tooltipHeight - inset
  ));
  $: if (canvas && !navigation) draw(layout, colors, width, paintHeight, paintTop, inspected, colored, dpr);
  $: if (graph && layout) scheduleMeasure();
  $: if (navigation && navigation.tree !== tree) cancelNavigation();

  onMount(() => {
    mounted = true;
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)');
    reducedMotion = preference.matches;
    const motionPreference = () => {
      reducedMotion = preference.matches;
      if (reducedMotion && navigation) finishNavigation(navigation);
    };
    preference.addEventListener('change', motionPreference);
    const html = document.documentElement;
    html.classList.add('ticksight-flame-view');
    const resize = new ResizeObserver(() => {
      if (graph.clientWidth !== width) resizeViewport();
      scheduleMeasure();
    });
    resize.observe(graph);
    const tooltipResize = new ResizeObserver(() => {
      tooltipWidth = tooltip.offsetWidth;
      tooltipHeight = tooltip.offsetHeight;
    });
    tooltipResize.observe(tooltip);
    const changeViewport = () => {
      hideTooltip();
      scheduleMeasure();
    };
    const resizeViewport = () => {
      hideTooltip();
      const active = navigation;
      if (active) {
        active.toScroll = navigationScroll(active);
        finishNavigation(active);
      }
      scheduleMeasure();
    };
    window.addEventListener('scroll', changeViewport, { passive: true });
    window.addEventListener('resize', resizeViewport, { passive: true });
    measure();
    return () => {
      mounted = false;
      focusVersion++;
      cancelNavigation();
      preference.removeEventListener('change', motionPreference);
      html.classList.remove('ticksight-flame-view');
      resize.disconnect();
      tooltipResize.disconnect();
      cancelAnimationFrame(measureFrame);
      window.removeEventListener('scroll', changeViewport);
      window.removeEventListener('resize', resizeViewport);
    };
  });

  function hideTooltip() {
    tooltipMode = null;
    hovered = null;
  }

  function scheduleMeasure() {
    if (!measureFrame) measureFrame = requestAnimationFrame(measure);
  }

  function measure() {
    measureFrame = 0;
    if (!body) return;
    width = graph.clientWidth;
    screenHeight = window.innerHeight;
    screenWidth = window.innerWidth;
    dpr = window.devicePixelRatio || 1;
    const bounds = body.getBoundingClientRect();
    // Keep the backing store bounded by the window, even for very deep recordings.
    paintTop = Math.max(0, Math.min(bodyHeight, Math.floor((-bounds.top - rowHeight) / rowHeight) * rowHeight));
    paintHeight = Math.max(0, Math.min(bodyHeight - paintTop, screenHeight + rowHeight * 2));
  }

  function fillColor(index: number, scale = colors, color = colored) {
    const score = scale.scores[index];
    return !color || score == null ? '#414541' : heatColor(score);
  }

  function draw(
    current: ReturnType<typeof layoutFlame>, scale: ReturnType<typeof flameColoring>,
    w: number, h: number, top: number, selected: number, color: boolean, ratio: number
  ) {
    const surface = canvas;
    if (!w || !h) {
      surface.width = surface.height = 0;
      lastPaint = [];
      return;
    }
    const paintKey = [current, scale, w, h, top, color, ratio];
    if (paintKey.some((value, index) => value !== lastPaint[index])) {
      lastPaint = paintKey;
      background ??= document.createElement('canvas');
      surface.width = background.width = Math.ceil(w * ratio);
      surface.height = background.height = Math.ceil(h * ratio);
      const ctx = background.getContext('2d')!;
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.fillStyle = '#171717';
      ctx.fillRect(0, 0, w, h);
      ctx.font = '11px ui-monospace, SFMono-Regular, Consolas, monospace';
      const first = Math.floor(top / rowHeight) + current.path.length;
      const end = Math.min(current.rows.length, Math.ceil((top + h) / rowHeight) + current.path.length);
      for (let depth = first; depth < end; depth++) {
        const y = (depth - current.path.length) * rowHeight - top;
        for (const frame of current.rows[depth]) {
          const x = Math.max(0, frame.x * w);
          const barWidth = Math.min(w - x, frame.width * w);
          ctx.fillStyle = fillColor(frame.index, scale, color);
          ctx.fillRect(x, y, barWidth < 1 ? barWidth : Math.max(0.5, barWidth - 1), rowHeight - 1);
          if (barWidth > 14) {
            ctx.save();
            ctx.beginPath();
            ctx.rect(x + 3, y, Math.max(0, barWidth - 7), rowHeight);
            ctx.clip();
            ctx.fillStyle = '#fff';
            ctx.fillText(tree.nodes[frame.index].label, x + 4, y + 12.5);
            ctx.restore();
          }
        }
      }
    }
    const ctx = surface.getContext('2d')!;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, surface.width, surface.height);
    ctx.drawImage(background, 0, 0);
    const frame = current.frames[selected];
    if (frame && frame.depth >= current.path.length) {
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
      ctx.strokeStyle = '#f5f5f4';
      ctx.lineWidth = 1;
      ctx.strokeRect(Math.min(w - 1, Math.max(0, frame.x * w)) + 0.5,
        (frame.depth - current.path.length) * rowHeight - top + 0.5,
        Math.max(0, frame.width * w - 1), rowHeight - 2);
    }
  }

  function hit(event: MouseEvent) {
    if (navigation) {
      const bounds = graph.getBoundingClientRect();
      const x = event.clientX - bounds.left;
      const y = event.clientY - bounds.top;
      // These rectangles are the last painted positions, including pinned ancestors.
      for (let slot = navigation.hits.length - 1; slot >= 0; slot--) {
        const frame = navigation.hits[slot];
        if (x >= frame.x && x < frame.x + frame.width && y >= frame.y && y < frame.y + rowHeight) return frame.index;
      }
      return null;
    }
    const target = event.target instanceof Element ? event.target.closest<HTMLElement>('[data-flame-frame]') : null;
    if (target && path.contains(target)) return Number(target.dataset.flameFrame);
    const bounds = body.getBoundingClientRect();
    const x = (event.clientX - bounds.left) / width;
    const y = event.clientY - bounds.top;
    if (x < 0 || x >= 1 || y < 0 || y >= bodyHeight) return null;
    const depth = Math.floor(y / rowHeight) + layout.path.length;
    const row = layout.rows[depth] ?? [];
    let lo = 0;
    let hi = row.length - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1;
      const frame = row[mid];
      if (x < frame.x) hi = mid - 1;
      else if (x >= frame.x + frame.width) lo = mid + 1;
      else return frame.index;
    }
    return null;
  }

  function pointer(event: MouseEvent) {
    if (navigation) return;
    hovered = hit(event);
    tooltipMode = hovered == null ? null : 'pointer';
    anchorX = event.clientX;
    anchorY = event.clientY;
  }

  function keyboardTooltip() {
    if (navigation) return;
    const frame = layout.frames[focused];
    if (!frame) return;
    const element = path.querySelector<HTMLElement>(`[data-flame-frame="${focused}"]`);
    const bounds = element?.getBoundingClientRect() ?? body.getBoundingClientRect();
    anchorX = element ? Math.min(bounds.right - inset, bounds.left + width * 0.5)
      : bounds.left + (frame.x + frame.width * 0.5) * width;
    anchorY = element ? bounds.bottom
      : bounds.top + (frame.depth - layout.path.length + 1) * rowHeight;
    tooltipMode = 'keyboard';
  }

  async function selectRoot(index: number, source: 'pointer' | 'keyboard') {
    const version = ++focusVersion;
    const next = layoutFlame(tree, index);
    const nextColors = flameColoring(tree, next);
    const previous = navigation;
    cancelAnimationFrame(animationFrame);
    animationFrame = 0;
    const animate = mounted && !reducedMotion && (index !== root || !!previous);
    const beforeTop = graph.getBoundingClientRect().top + window.scrollY;
    const prepared = animate ? prepareNavigation(next, nextColors, previous, version, source === 'keyboard') : undefined;
    navigation = prepared;
    root = focused = index;
    hideTooltip();
    graph.focus({ preventScroll: true });
    await tick();
    if (!mounted || version !== focusVersion) return;
    const graphTop = graph.getBoundingClientRect().top + window.scrollY;
    const target = graphTop + Math.max(0, path.offsetHeight - Math.floor(window.innerHeight * 0.35 / rowHeight) * rowHeight);
    if (prepared) {
      prepared.toScroll = navigationScroll(prepared);
      for (const frame of prepared.frames.values()) frame.from.y += beforeTop - graphTop;
      prepared.started = performance.now();
      paintNavigation(prepared, 0);
      animationFrame = requestAnimationFrame(animateNavigation);
      return;
    }
    window.scrollTo({ top: target });
    await afterViewport();
    measure();
    if (mounted && version === focusVersion && source === 'keyboard') keyboardTooltip();
  }

  function rgb(color: string) {
    if (color.startsWith('#')) return [1, 3, 5].map((start) => parseInt(color.slice(start, start + 2), 16));
    return color.match(/[\d.]+/g)!.slice(0, 3).map(Number);
  }

  function movingPosition(frame: MovingFrame, active: Navigation): Position {
    const progress = active.progress;
    return {
      x: frame.from.x + (frame.to.x - frame.from.x) * progress,
      width: frame.from.width + (frame.to.width - frame.from.width) * progress,
      y: frame.from.y + (frame.to.y + (frame.pinned ? active.pinOffset : 0) - frame.from.y) * progress,
      rgb: frame.from.rgb.map((value, i) => value + (frame.to.rgb[i] - value) * progress)
    };
  }

  function prepareNavigation(
    next: ReturnType<typeof layoutFlame>, nextColors: ReturnType<typeof flameColoring>,
    previous: Navigation | undefined, version: number, keyboard: boolean
  ): Navigation {
    const rows: MovingFrame[][] = [];
    const pinned: MovingFrame[] = [];
    const frames = new Map<number, MovingFrame>();
    const oldCamera = originalLayout.frames[root];
    const newRoot = next.path[next.path.length - 1];
    const newCamera = originalLayout.frames[newRoot];
    const oldPinOffset = path.getBoundingClientRect().top - graph.getBoundingClientRect().top;
    const oldPath = new Set(layout.path);
    const newPath = new Set(next.path);
    const movingAncestors = new Set([...layout.path, ...next.path, ...(previous?.pinned.map((frame) => frame.index) ?? [])]);
    const projected = (base: FlameFrame, camera: FlameFrame | undefined) => ({
      x: camera?.width ? (base.x - camera.x) / camera.width : base.x,
      width: camera?.width ? base.width / camera.width : base.width
    });
    // Prepare the union once per navigation. Animation visits only viewport rows and the ancestor paths.
    for (let index = 0; index < tree.nodes.length; index++) {
      const before = layout.frames[index];
      const after = next.frames[index];
      const base = originalLayout.frames[index] ?? before ?? after;
      if (!base) continue;
      const current = previous?.frames.get(index);
      const from = current && previous ? movingPosition(current, previous) : {
        ...(before ?? projected(base, oldCamera)),
        y: base.depth * rowHeight + (oldPath.has(index) ? oldPinOffset : 0),
        rgb: rgb(before?.ancestor ? '#3c423d' : fillColor(index))
      };
      const to = {
        ...(after ?? projected(base, newCamera)), y: base.depth * rowHeight,
        rgb: rgb(after?.ancestor ? '#3c423d' : fillColor(index, nextColors, colored))
      };
      const frame = { index, depth: base.depth, from, to, pinned: newPath.has(index) };
      frames.set(index, frame);
      if (movingAncestors.has(index)) pinned.push(frame);
      else (rows[base.depth] ??= []).push(frame);
    }
    for (const row of rows) row?.sort((a, b) => a.to.x - b.to.x);
    return {
      tree, rows, pinned, frames, pathLength: next.path.length, progress: 0, pinOffset: 0,
      height: Math.max(graph.clientHeight, next.rows.length * rowHeight), targetHeight: next.rows.length * rowHeight,
      fromScroll: window.scrollY, toScroll: window.scrollY, started: 0, version, keyboard, hits: []
    };
  }

  function animateNavigation(now: number) {
    animationFrame = 0;
    const active = navigation;
    if (!active || !mounted) return;
    const elapsed = Math.min(1, Math.max(0, (now - active.started) / 200));
    const progress = elapsed * elapsed * (3 - 2 * elapsed);
    window.scrollTo({ top: active.fromScroll + (active.toScroll - active.fromScroll) * progress });
    paintNavigation(active, progress);
    if (elapsed < 1) animationFrame = requestAnimationFrame(animateNavigation);
    else finishNavigation(active);
  }

  function paintNavigation(active: Navigation, progress: number) {
    const surface = movingCanvas;
    if (!surface || !mounted) return;
    const bounds = graph.getBoundingClientRect();
    const w = graph.clientWidth;
    const top = Math.max(0, Math.floor((-bounds.top - rowHeight) / rowHeight) * rowHeight);
    const h = Math.max(0, Math.min(graph.clientHeight - top, window.innerHeight + rowHeight * 2));
    const ratio = window.devicePixelRatio || 1;
    surface.style.top = `${top}px`;
    surface.style.width = `${w}px`;
    surface.style.height = `${h}px`;
    if (surface.width !== Math.ceil(w * ratio)) surface.width = Math.ceil(w * ratio);
    if (surface.height !== Math.ceil(h * ratio)) surface.height = Math.ceil(h * ratio);
    active.progress = progress;
    const pathHeight = active.pathLength * rowHeight;
    const pinTop = Math.min(0, Math.floor(window.innerHeight * 0.35 / rowHeight) * rowHeight - pathHeight);
    active.pinOffset = Math.min(Math.max(bounds.top, pinTop), bounds.top + active.targetHeight - pathHeight) - bounds.top;
    const ctx = surface.getContext('2d')!;
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.fillStyle = '#171717';
    ctx.fillRect(0, 0, w, h);
    ctx.font = '11px ui-monospace, SFMono-Regular, Consolas, monospace';
    active.hits = [];
    const paint = (frame: MovingFrame) => {
      const position = movingPosition(frame, active);
      const x = Math.max(0, position.x * w);
      const right = Math.min(w, (position.x + position.width) * w);
      const barWidth = right - x;
      const y = position.y - top;
      if (barWidth <= 0 || y + rowHeight < 0 || y > h) return;
      ctx.fillStyle = `rgb(${position.rgb.map(Math.round).join(' ')})`;
      ctx.fillRect(x, y, barWidth < 1 ? barWidth : Math.max(0.5, barWidth - 1), rowHeight - 1);
      if (barWidth > 14) {
        ctx.save();
        ctx.beginPath();
        ctx.rect(x + 3, y, Math.max(0, barWidth - 7), rowHeight);
        ctx.clip();
        ctx.fillStyle = '#fff';
        ctx.fillText(tree.nodes[frame.index].label, x + 4, y + 12.5);
        ctx.restore();
      }
      active.hits.push({ index: frame.index, x, y: position.y, width: barWidth });
    };
    const first = Math.max(0, Math.floor(top / rowHeight));
    const last = Math.min(active.rows.length, Math.ceil((top + h) / rowHeight));
    for (let depth = first; depth < last; depth++) {
      const row = active.rows[depth] ?? [];
      // Camera interpolation preserves row order. Skip offscreen branches without transforming the whole row.
      let lo = 0;
      let hi = row.length;
      while (lo < hi) {
        const mid = (lo + hi) >>> 1;
        const frame = row[mid];
        const right = frame.from.x + frame.from.width
          + (frame.to.x + frame.to.width - frame.from.x - frame.from.width) * progress;
        if (right <= 0) lo = mid + 1;
        else hi = mid;
      }
      for (let slot = lo; slot < row.length; slot++) {
        const frame = row[slot];
        if (frame.from.x + (frame.to.x - frame.from.x) * progress >= 1) break;
        paint(frame);
      }
    }
    for (const frame of active.pinned) paint(frame);
  }

  function cancelNavigation() {
    cancelAnimationFrame(animationFrame);
    animationFrame = 0;
    navigation = undefined;
  }

  function navigationScroll(active: Navigation) {
    const graphTop = graph.getBoundingClientRect().top + window.scrollY;
    const target = graphTop + Math.max(0,
      active.pathLength * rowHeight - Math.floor(window.innerHeight * 0.35 / rowHeight) * rowHeight);
    const finalPageHeight = document.documentElement.scrollHeight - (graph.clientHeight - active.targetHeight);
    return Math.max(0, Math.min(target, finalPageHeight - window.innerHeight));
  }

  async function finishNavigation(active: Navigation) {
    if (navigation !== active) return;
    window.scrollTo({ top: active.toScroll });
    cancelNavigation();
    await tick();
    if (!mounted || active.version !== focusVersion) return;
    measure();
    await afterViewport();
    if (mounted && active.version === focusVersion && active.keyboard) keyboardTooltip();
  }

  async function focusFrame(index: number) {
    const version = ++focusVersion;
    focused = index;
    hideTooltip();
    await tick();
    const frame = layout.frames[index];
    if (!frame || version !== focusVersion) return;
    if (frame.depth < layout.path.length) {
      const element = path.querySelector<HTMLElement>(`[data-flame-frame="${index}"]`);
      const bounds = element?.getBoundingClientRect();
      if (bounds && (bounds.top < 0 || bounds.bottom > window.innerHeight)) {
        window.scrollTo({ top: window.scrollY + graph.getBoundingClientRect().top + frame.depth * rowHeight - inset });
      }
    } else {
      const y = body.getBoundingClientRect().top + (frame.depth - layout.path.length) * rowHeight;
      const visibleTop = Math.max(inset, path.getBoundingClientRect().bottom);
      if (y < visibleTop) window.scrollBy(0, y - visibleTop);
      else if (y + rowHeight > window.innerHeight - inset) window.scrollBy(0, y + rowHeight - window.innerHeight + inset);
    }
    await afterViewport();
    measure();
    if (version === focusVersion) keyboardTooltip();
  }

  function afterViewport() {
    return new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
  }

  function keydown(event: KeyboardEvent) {
    const parent = tree.nodes[focused]?.parent ?? -1;
    const siblings = tree.children.get(parent)?.filter((index) => layout.frames[index]) ?? [0];
    const slot = siblings.indexOf(focused);
    let next: number | undefined;
    switch (event.key) {
      case 'ArrowUp': next = parent; break;
      case 'ArrowDown': next = tree.children.get(focused)?.find((index) => layout.frames[index]); break;
      case 'ArrowLeft': next = siblings[Math.max(0, slot - 1)]; break;
      case 'ArrowRight': next = siblings[Math.min(siblings.length - 1, slot + 1)]; break;
      case 'Home': next = layout.path[0]; break;
      case 'Enter': case ' ': selectRoot(focused, 'keyboard'); break;
      case 'Escape': case 'Backspace': if (root !== layout.path[0]) selectRoot(tree.nodes[root].parent, 'keyboard'); break;
      default: return;
    }
    event.preventDefault();
    if (next != null && layout.frames[next]) focusFrame(next);
  }
</script>

<section class="min-w-0 space-y-1" aria-label="Flame graph">
  {#if colored}
    <div class="flex min-w-0 flex-wrap items-center justify-end gap-x-2 gap-y-1 text-xs text-neutral-300">
      <span>{metricLabel}{tree.metric === 'per-parent' ? '' : ' · % of view'}</span>
      <span>{tree.metric === 'per-parent' ? '≤1' : '0%'}</span>
      <span
        class="h-2 w-20 rounded-sm"
        style:background={tree.metric === 'per-parent' && colors.maximum <= 1 ? heatColor(0)
          : `linear-gradient(to right, ${heatColor(0)}, ${heatColor(40)}, ${heatColor(70)}, ${heatColor(100)})`}
        aria-hidden="true"
      ></span>
      {#if tree.metric !== 'per-parent' || colors.maximum > 1}
        <span>{tree.metric === 'per-parent' ? colors.maximum.toLocaleString(undefined, { maximumSignificantDigits: 4 }) : '100%'}</span>
      {/if}
    </div>
  {/if}
  {#if layout.adjusted}
    <p class="text-sm text-amber-200">Child totals exceed their parent; widths are scaled to fit.</p>
  {/if}
  <p id={`${id}-keys`} class="sr-only">Arrows select parent, child or sibling. Enter opens a branch. Escape returns to its parent. Home selects the recording root. Click any ancestor to return to it.</p>
  <div
    bind:this={graph}
    class="flame-graph"
    style:min-height={navigation ? `${navigation.height}px` : undefined}
    role="tree"
    tabindex="0"
    aria-label="Flame graph"
    aria-describedby={`${id}-keys ${id}-detail`}
    aria-activedescendant={layout.path.includes(focused) ? `${id}-path-${focused}` : `${id}-frame`}
    aria-busy={!!navigation}
    on:keydown={keydown}
    on:mousemove={pointer}
    on:mouseleave={() => { if (tooltipMode === 'pointer') hideTooltip(); }}
    on:focus={() => { if (graph.matches(':focus-visible')) keyboardTooltip(); }}
    on:blur={() => { if (tooltipMode === 'keyboard') hideTooltip(); }}
    on:click={(event) => { const index = hit(event); if (index != null) selectRoot(index, 'pointer'); }}
  >
    <div bind:this={path} class="flame-path" class:invisible={!!navigation} style:top={`${pathTop}px`}>
      {#each layout.path as index, depth (index)}
        <button
          id={`${id}-path-${index}`}
          type="button"
          role="treeitem"
          tabindex="-1"
          aria-level={depth + 1}
          aria-selected={focused === index}
          aria-expanded={!!tree.children.get(index)?.length}
          data-flame-frame={index}
          class="path-frame"
          class:past={index !== root}
          class:inspected={index === inspected}
          style:background-color={index === root ? fillColor(index, colors, colored) : '#3c423d'}
        >{tree.nodes[index].label}</button>
      {/each}
    </div>
    <div bind:this={body} class="flame-body" style:height={`${bodyHeight}px`}>
      <canvas
        bind:this={canvas}
        class="absolute left-0 block"
        class:invisible={!!navigation}
        style:top={`${paintTop}px`}
        style:width={`${width}px`}
        style:height={`${paintHeight}px`}
        aria-hidden="true"
      ></canvas>
    </div>
    {#if navigation}
      <canvas bind:this={movingCanvas} class="pointer-events-none absolute left-0 z-20 block" aria-hidden="true"></canvas>
    {/if}
    <span id={`${id}-frame`} role="treeitem" aria-level={(layout.frames[focused]?.depth ?? 0) + 1} aria-selected="true" class="sr-only">{focusedDescription}</span>
  </div>
  <p id={`${id}-detail`} class="sr-only" aria-live="polite">{focusedDescription}</p>
  <div
    bind:this={tooltip}
    id={`${id}-tooltip`}
    class="flame-tooltip"
    class:invisible={!tooltipMode}
    role="tooltip"
    style:left={`${tooltipLeft}px`}
    style:top={`${tooltipTop}px`}
  >
    <code class="block text-neutral-100">{node?.label ?? ''}</code>
    <p class="mt-1 text-neutral-300">{detail}</p>
    <p class="text-neutral-400">{widthDetail}</p>
    {#if colorDetail}<p class="mt-1 text-neutral-200">{colorDetail}</p>{/if}
  </div>
</section>

<style lang="postcss">
  .flame-graph {
    @apply relative min-w-0 rounded-sm bg-neutral-950;
  }
  .flame-graph:focus-visible { @apply outline outline-1 outline-offset-1 outline-neutral-300; }
  .flame-path { @apply sticky z-10; }
  .path-frame {
    @apply block w-full cursor-pointer overflow-hidden whitespace-nowrap border-b border-neutral-950 px-1 text-left text-white;
    height: 18px;
    font: 11px/17px ui-monospace, SFMono-Regular, Consolas, monospace;
    text-overflow: ellipsis;
  }
  .path-frame.past { @apply text-neutral-300; }
  .path-frame.inspected { box-shadow: inset 0 0 0 1px #f5f5f4; }
  .flame-body { @apply relative; }
  .flame-tooltip {
    @apply pointer-events-none fixed z-50 rounded-md border border-neutral-600 bg-neutral-900 px-3 py-2 text-xs shadow-lg;
    width: max-content;
    max-width: min(42rem, calc(100vw - 16px));
    overflow-wrap: anywhere;
  }
  :global(html.ticksight-flame-view),
  :global(html.ticksight-flame-view body) { scrollbar-width: none; }
  :global(html.ticksight-flame-view::-webkit-scrollbar),
  :global(html.ticksight-flame-view body::-webkit-scrollbar) { display: none; }
</style>

