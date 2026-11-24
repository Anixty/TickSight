<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import ValueControls from '$lib/ValueControls.svelte';
  import TreeViewControls from '$lib/TreeViewControls.svelte';
  import FlameGraph from '$lib/FlameGraph.svelte';
  import { callFlameTree } from '$lib/flamegraph';
  import { instanceCallTicks, readTick } from '$lib/profiling';
  import type { CallMethod, CallTrace, MethodCall } from '$lib/types';
  import type { LayoutData } from '../$types';
  import CallBranch from './CallBranch.svelte';

  export let data: LayoutData;

  type CallSort = 'name' | 'self' | 'total' | 'per-parent';
  type CallMetric = Exclude<CallSort, 'name'>;

  let colorRows = true;
  let sort: CallSort = 'total';
  let heatMetric: CallMetric = 'total';
  let view: 'tree' | 'flame' = 'tree';

  $: trace = data.profile.callTrace;
  $: lastTick = Math.max(1, data.timelineTicks);
  $: requestedTick = $page.url.searchParams.get('tick');
  $: requestedObject = $page.url.searchParams.get('object');
  $: objects = recordedObjects(trace, data.entries);
  $: selectedObject = requestedObject === null
    ? objects[0]
    : objects.find((object) => String(object.id) === requestedObject);
  $: tickTrees = trace && selectedObject ? instanceCallTicks(trace, selectedObject.entry) : undefined;
  $: tick = readTick(requestedTick ?? Object.keys(tickTrees ?? {})[0] ?? null, lastTick);
  $: calls = tickTrees?.[tick] ?? [];
  $: hasTimings = calls[0]?.elapsedNanos != null;
  $: activeSort = hasTimings || sort === 'name' || sort === 'per-parent' ? sort : 'name';
  $: selfTimes = callSelfTimes(calls);
  $: metricValues = callMetricValues(calls, selfTimes, hasTimings ? heatMetric : 'per-parent');
  $: children = groupByParent(calls, trace?.methods ?? [], metricValues, activeSort);
  $: heatScores = siblingHeatScores(calls.length, children, metricValues);
  $: flameTree = view === 'flame' && hasTimings
    ? callFlameTree(calls, trace?.methods ?? [], children, selfTimes, heatMetric)
    : undefined;

  function recordedObjects(trace: CallTrace | undefined, groups: LayoutData['entries']) {
    if (!trace) return [];

    return groups.flatMap(({ name: dimension, entries }) => entries.flatMap((entry) => {
      const id = entry.callTargetId;
      return id != null && instanceCallTicks(trace, entry) ? [{ id, entry, dimension }] : [];
    })).sort((a, b) => b.entry.rate - a.entry.rate);
  }

  function callSelfTimes(calls: MethodCall[]) {
    const times = calls.map((call) => call.untracedCalls ? undefined : call.elapsedNanos);
    for (const { parent, elapsedNanos } of calls) {
      const time = times[parent];
      if (time != null) times[parent] = elapsedNanos == null ? undefined : time - elapsedNanos;
    }
    return times.map((time) => time == null ? undefined : Math.max(0, time));
  }

  function callMetricValues(calls: MethodCall[], selfTimes: (number | undefined)[], metric: CallMetric) {
    if (metric === 'self') return selfTimes;
    if (metric === 'total') return calls.map((call) => call.elapsedNanos);
    // Siblings share a parent, so its count cancels when comparing their ratios.
    return calls.map((call) => call.count);
  }

  function siblingHeatScores(
    count: number,
    children: Map<number, number[]>,
    values: (number | undefined)[]
  ) {
    const scores = new Array<number | undefined>(count);
    if (count === 0) return scores;
    const groups = [[0], ...children.values()];
    for (const siblings of groups) {
      let max = 0;
      for (const index of siblings) max = Math.max(max, values[index] ?? 0);
      for (const index of siblings) {
        const value = values[index];
        if (value != null) scores[index] = max > 0 ? 100 * Math.max(0, value) / max : 0;
      }
    }
    return scores;
  }

  function groupByParent(
    calls: MethodCall[],
    methods: CallMethod[],
    values: (number | undefined)[],
    sort: CallSort
  ) {
    const indices = new Map<number, number[]>();
    for (let index = 1; index < calls.length; index++) {
      const parent = calls[index].parent;
      const children = indices.get(parent);
      if (children) children.push(index);
      else indices.set(parent, [index]);
    }
    const names = calls.map(({ method }) => {
      const m = methods[method];
      return m ? `${m.className}.${m.name}${m.descriptor}` : '';
    });
    for (const siblings of indices.values()) {
      siblings.sort((a, b) => {
        const difference = sort === 'name' ? 0 : (values[b] ?? -1) - (values[a] ?? -1);
        return difference || names[a].localeCompare(names[b]);
      });
    }
    return indices;
  }

  function selectTick(event: CustomEvent<{ tick: number }>) {
    const url = new URL($page.url);
    url.searchParams.set('tick', String(event.detail.tick));
    goto(url, { keepFocus: true, noScroll: true, replaceState: true });
  }

  function selectObject(event: Event) {
    const input = event.currentTarget as HTMLSelectElement;
    const url = new URL($page.url);
    url.searchParams.set('object', input.value);
    goto(url, { keepFocus: true, noScroll: true, replaceState: true });
  }

  function selectSort(event: Event) {
    sort = (event.currentTarget as HTMLSelectElement).value as CallSort;
    if (sort !== 'name') heatMetric = sort;
  }
</script>

{#if trace}
  <div class="min-w-0 space-y-3 py-2">
    <h1 class="break-words text-lg font-semibold">{trace.target}</h1>
    <div class="flex flex-wrap items-center gap-3">
      {#if objects.length}
        <label class="flex min-w-0 max-w-full items-center gap-2 text-sm">
          Object
          <select
            class="min-w-0 max-w-full flex-1 rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
            value={selectedObject?.id ?? ''}
            on:change={selectObject}
          >
            {#if !selectedObject}<option value="" disabled>Choose an object</option>{/if}
            {#each objects as { id, entry, dimension } (id)}
              <option value={id}>
                {entry.type}{entry.entityId != null ? ` #${entry.entityId}` : ''}
                · ({entry.position.x}, {entry.position.y}, {entry.position.z}) · {dimension}
              </option>
            {/each}
          </select>
        </label>
      {/if}
      <ValueControls
        mode="tick" {tick} {lastTick} tickOnly hasTicks={data.timelineTicks > 0} tickStarts={data.profile.tickStarts}
        on:change={selectTick}
      />
      <TreeViewControls bind:view />
      {#if calls.length}
        <label class="flex items-center gap-2 text-sm">
          Sort
          <select
            class="rounded-md border border-neutral-700 bg-neutral-900 px-2 py-1.5"
            value={activeSort}
            on:change={selectSort}
          >
            <option value="name">Name</option>
            {#if hasTimings}
              <option value="self">Self time</option>
              <option value="total">Total time</option>
            {/if}
            <option value="per-parent">Calls per parent</option>
          </select>
        </label>
      {/if}
      {#if hasTimings}
        <button
          type="button"
          role="switch"
          aria-checked={colorRows}
          class="inline-flex items-center gap-2 rounded-md border border-neutral-700 bg-neutral-900 px-2.5 py-1.5 text-sm hover:border-neutral-500"
          on:click={() => (colorRows = !colorRows)}
        >
          <span
            aria-hidden="true"
            class="relative h-4 w-7 rounded-full transition-colors {colorRows ? 'bg-blue-600' : 'bg-neutral-700'}"
          >
            <span class="absolute left-0.5 top-0.5 h-3 w-3 rounded-full bg-white transition-transform {colorRows ? 'translate-x-3' : ''}"></span>
          </span>
          Heatmap
        </button>
      {/if}
    </div>
    {#if selectedObject}
      {@const pos = selectedObject.entry.position}
      <p class="text-sm text-neutral-400">{selectedObject.dimension} · ({pos.x}, {pos.y}, {pos.z})</p>
    {/if}

    {#if trace.cutoff}
      <p class="rounded-md border border-amber-700 bg-amber-950/40 px-3 py-2 text-sm text-amber-100">
        {#if trace.cutoff.reason === 'hard_tick_time'}
          Recording stopped by the {trace.hardLimitMs ?? 5000} ms watchdog on tick {trace.cutoff.tick}.
        {:else if trace.cutoff.reason === 'memory'}
          Recording stopped at the {trace.memoryLimitMiB ?? 128} MiB recording/export budget on tick {trace.cutoff.tick}.
        {:else if trace.cutoff.reason === 'heap'}
          Recording stopped to preserve {trace.heapReserveMiB ?? 256} MiB of JVM heap headroom on tick {trace.cutoff.tick}.
        {:else if trace.cutoff.reason === 'report_size'}
          Recording stopped at the 128 MiB report budget on tick {trace.cutoff.tick}.
        {:else}
          Recording stopped on tick {trace.cutoff.tick}. The server tick took
          {(trace.cutoff.elapsedNanos / 1_000_000).toFixed(3)} ms with a {trace.limitMs} ms limit.
        {/if}
        Partial report.
      </p>
    {/if}
    {#if trace.skippedClasses || trace.skippedMethods}
      <p class="text-sm text-amber-200">
        The agent skipped {trace.skippedClasses ?? 0} classes and {trace.skippedMethods ?? 0}
        constructors during instrumentation.
      </p>
    {/if}
    {#if calls.length}
      {#key `${$page.params.id ?? ''}/${selectedObject?.id ?? ''}/${tick}`}
        {#if view === 'flame'}
          {#if flameTree}
            <FlameGraph tree={flameTree} id="calls-flame" colored={colorRows} />
          {:else}
            <p>Flame graphs require recorded method timings.</p>
          {/if}
        {:else}
          <CallBranch index={0} {calls} methods={trace.methods} {children} {selfTimes} {heatScores} {colorRows} />
        {/if}
      {/key}
    {:else if !selectedObject}
      <p>{objects.length ? 'Object not found in this report.' : 'No object calls were recorded.'}</p>
    {:else}
      <p>No calls were recorded for tick {tick}.</p>
    {/if}
  </div>
{:else}
  <p class="py-2">This profile has no call recording.</p>
{/if}
