<script lang="ts">
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import ValueControls from '$lib/ValueControls.svelte';
  import TreeViewControls from '$lib/TreeViewControls.svelte';
  import FlameGraph from '$lib/FlameGraph.svelte';
  import { traceFlameTree } from '$lib/flamegraph';
  import {
    individualUrl,
    instanceTiming,
    instanceTraceUrl,
    peakTick,
    readMode,
    readTick,
    type ValueMode
  } from '$lib/profiling';
  import type { LayoutData } from '../$types';
  import Trace from './Trace.svelte';

  export let data: LayoutData;
  let view: 'tree' | 'flame' = 'tree';

  $: params = $page.url.searchParams;
  $: basePath = $page.params.id ? `/p/${$page.params.id}` : '/p';
  $: dimension = params.get('dimension') ?? '';
  $: source = params.get('source');
  $: index = params.get('index');
  $: hasInstance = params.has('dimension') || params.has('source') || params.has('index');
  $: selected = index !== null && /^\d+$/.test(index)
    ? data.entries.find((group) => group.name === dimension)?.entries.find(
      (entry) => entry.source === source && entry.index === Number(index)
    )
    : undefined;
  $: hasHistory = !!selected?.tickHistory?.times.length;
  $: mode = hasHistory ? readMode(params.get('mode'), data.profile.peakMode) : 'average';
  $: peak = selected ? peakTick(selected) : undefined;
  $: tick = mode === 'peak' ? peak?.tick ?? 1 : readTick(params.get('tick'), data.timelineTicks);
  $: timing = selected && mode !== 'average'
    ? mode === 'peak' ? peak : instanceTiming(selected, tick)
    : undefined;
  $: traces = hasInstance
    ? (mode === 'average' ? selected?.traces : timing?.traces)
    : data.profile.traces;
  $: flameTree = view === 'flame' && traces ? traceFlameTree(traces) : undefined;

  function changeValues(event: CustomEvent<{ mode: ValueMode; tick: number }>) {
    if (!selected) return;
    goto(instanceTraceUrl(basePath, dimension, selected, event.detail.mode, event.detail.tick), {
      keepFocus: true,
      noScroll: true,
      replaceState: true
    });
  }
</script>

<div class="py-2"><TreeViewControls bind:view /></div>

{#if hasInstance}
  {#if selected}
    <div class="flex flex-wrap items-start justify-between gap-3 py-2">
      <div>
        <h1 class="text-lg font-semibold">{selected.type}</h1>
        <p class="text-neutral-300">
          {dimension} · ({selected.position.x}, {selected.position.y}, {selected.position.z})
          {#if selected.entityId != null} · Entity {selected.entityId}{/if}
        </p>
      </div>
      <div class="flex flex-wrap gap-3">
        <a class="text-blue-400" href={individualUrl(basePath, mode, tick)}>Back to Individual</a>
        <a class="text-blue-400" href={`${basePath}/traces`}>All server traces</a>
      </div>
    </div>
    <ValueControls
      {mode} {tick} lastTick={data.timelineTicks} tickStarts={data.profile.tickStarts}
      hasPeaks={peak !== undefined} hasTicks={hasHistory && data.timelineTicks > 0}
      on:change={changeValues}
    />
    {#if mode === 'average'}
      <p class="py-2 text-neutral-300">Stack samples for this instance across the profile.</p>
    {:else if timing}
      <p class="py-2 text-neutral-300">
        {mode === 'peak' ? 'Peak tick' : 'Tick'} {tick} · {(timing.time / 1_000_000).toFixed(3)} ms
        · {timing.calls} {timing.calls === 1 ? 'call' : 'calls'}
      </p>
    {/if}
  {/if}
{/if}

{#if hasInstance && !selected}
  <p class="py-2">Instance not found in this profile.</p>
  <a class="text-blue-400" href={individualUrl(basePath, mode, tick)}>Back to Individual</a>
{:else if hasInstance && mode !== 'average' && data.timelineTicks === 0}
  <p class="py-2">Per-tick stacks require a report with tick history.</p>
  {#if selected?.traces?.count}
    <p class="text-neutral-300">Choose Average to see the instance's samples for the full profile.</p>
  {/if}
{:else if hasInstance && mode === 'tick' && !timing}
  <p class="py-2">This instance did not run during tick {tick}.</p>
{:else if traces && traces.count > 0}
  {#key `${$page.params.id ?? ''}/${dimension}/${source}/${index}/${mode}/${tick}`}
    {#if view === 'flame' && flameTree}
      <FlameGraph tree={flameTree} id="traces-flame" />
    {:else}
      <Trace data={traces} />
    {/if}
  {/key}
{:else}
  <p class="py-2">No stack samples were captured {hasInstance && mode !== 'average' ? 'for this tick' : 'for this selection'}.</p>
  {#if !data.profile.traces?.count}
    <p class="text-neutral-300">Enable the sampler when recording a new profile to collect stacks.</p>
  {:else}
    <p class="text-neutral-300">
      The sampling rate may have been too low to capture this stack. To sample more often,
      lower <code>traceInterval</code> in the TickSight server settings. For example, run
      <code>/ticksight set traceInterval 0.5</code> before recording another profile.
    </p>
  {/if}
{/if}
