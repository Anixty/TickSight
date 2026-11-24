<script lang="ts">
  import { copyProfileCommand, copyTPCommand } from '$lib/utils';
  import { page } from '$app/stores';
  import ValueControls from '$lib/ValueControls.svelte';
  import { entryValue, instanceTiming, instanceCallTicks, instanceCallsUrl, instanceTraceUrl, readMode, readTick } from '$lib/profiling';
  import type { PageData } from './$types';
  import { parseMapRects, inRects } from '$lib/heatmap';

  export let data: PageData;

  const WINDOW_SIZE = 100;

  let query = '';
  $: requestedMode = $page.url.searchParams.get('mode');
  $: mode = readMode(requestedMode === 'tick' && !data.hasHistory ? null : requestedMode, data.profile.peakMode);
  $: tick = readTick($page.url.searchParams.get('tick'), data.timelineTicks);
  $: basePath = $page.params.id ? `/p/${$page.params.id}` : '/p';

  $: hasPeaks = data.entries.some(({ entries }) => entries.length > 0);
  $: mapRects = parseMapRects($page.url.searchParams);
  $: mapDimension = $page.url.searchParams.get('dimension');
  $: mapSource = $page.url.searchParams.get('source');
  $: filtered = data.entries.filter(group => !mapDimension || group.name === mapDimension).map(group => ({
    ...group,
    entries: group.entries.filter(entry => (!mapSource || mapSource === 'all' || entry.source === mapSource)
      && (!mapRects.length || inRects(entry.position.x, entry.position.z, mapRects))
      && (!mapRects.length || mode !== 'tick' || instanceTiming(entry, tick) !== undefined))
  }));

  $: dim_map = filtered.map(({ name: dim, entries }) => {
    const values = entries.map((entry) => ({ ...entry, rate: entryValue(entry, mode, tick) }));
    return {
      dim,
      rate: values.reduce((total, entry) => total + entry.rate, 0),
      enabled: true,
      offset: 0,
      entries: values
        .filter((i) => i.type.includes(query))
        .sort((a, b) => b.rate - a.rate)
    };
  });
</script>

{#if mapRects.length}
  <div class="flex flex-wrap items-center gap-3 py-2 text-sm text-neutral-300">
    <span>Map selection · {mapRects.length} {mapRects.length === 1 ? 'rectangle' : 'rectangles'} · {mapSource ?? 'all'}</span>
    <a class="text-blue-400" href={basePath}>Clear filter</a>
  </div>
{/if}

<div class="w-full flex flex-wrap gap-3 py-2">
  <ValueControls
    {mode} {tick} {hasPeaks} hasTicks={data.hasHistory && data.timelineTicks > 0} lastTick={data.timelineTicks} tickStarts={data.profile.tickStarts}
    on:change={(event) => { mode = event.detail.mode; tick = event.detail.tick; }}
  />
  <input
    bind:value={query}
    placeholder="Search entries..."
    class="min-w-0 flex-1 bg-neutral-900 focus:outline-none"
  />
</div>
<table class="border-b w-full">
  {#each dim_map as { dim, rate, enabled, entries, offset }}
    {@const slice = entries.slice(offset, Math.min(offset + WINDOW_SIZE, entries.length))}
    <tr class="border-b h-min [&>*]:font-bold">
      <td on:click={(_) => (enabled = !enabled)} class="cursor-pointer">
        <span>{enabled ? '-' : '+'} {dim}</span>
        <span>&mdash; {entries.length} entries</span>
      </td>
      <td>
        {#if mode === 'peak'}Peak μs/t
        {:else if mode === 'tick'}{Math.round(rate / 1000)} μs · tick {tick}
        {:else}{Math.round(rate / 1000)} μs/t average{/if}
      </td>
      <td>Position</td>
    </tr>
    <tbody hidden={!enabled}>
      <tr
        class="cursor-pointer text-blue-400"
        on:click={(_) => (offset -= WINDOW_SIZE)}
        hidden={offset == 0}
      >
        <span class="pl-4 select-none">&minus;</span>
      </tr>
      {#each slice as entry}
        {@const { x, y, z } = entry.position}
        <tr class="border-b border-b-neutral-600">
          <td class="pl-4">{entry.type}</td>
          <td>{Math.round(entry.rate / 1000)} {mode === 'tick' ? 'μs' : 'μs/t'}</td>
          <td class="w-[30%]">
            <div class="min-w-[30%] inline-block">
              ({x}, {y}, {z})
            </div>

            <button
              type="button"
              class="text-blue-400"
              on:click={(_) => {
                copyTPCommand(dim, entry.entityId ?? entry.position);
              }}
            >
              Visit
            </button>
            {#if !data.profile.callTrace || instanceCallTicks(data.profile.callTrace, entry)}
              <a
                class="ml-3 text-blue-400"
                href={data.profile.callTrace
                  ? instanceCallsUrl(basePath, entry, mode, tick)
                  : instanceTraceUrl(basePath, dim, entry, mode, tick)}
              >{data.profile.callTrace ? 'Calls' : 'Stack trace'}</a>
            {/if}
            <button
              type="button"
              class="ml-3 text-blue-400"
              on:click={() => copyProfileCommand(dim, entry)}
            >Profile</button>
          </td>
        </tr>
      {/each}
      <tr
        class="cursor-pointer text-blue-400"
        on:click={(_) => (offset += WINDOW_SIZE)}
        hidden={offset + WINDOW_SIZE > entries.length}
      >
        <span class="pl-4 select-none">&plus;</span>
      </tr>
    </tbody>
  {/each}
</table>
