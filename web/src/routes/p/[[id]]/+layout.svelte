<script lang="ts">
  import { page } from '$app/stores';
  import type { LayoutData } from './$types';
  import Clock from 'lucide-svelte/icons/clock';
  import Download from 'lucide-svelte/icons/download';
  import Hourglass from 'lucide-svelte/icons/hourglass';
  import User from 'lucide-svelte/icons/user';
  import logo from '../../../assets/logo.svg';

  export let data: LayoutData;

  $: basePath = $page.params.id ? `/p/${$page.params.id}` : '/p';
  $: heatmapPage = $page.url.pathname.endsWith('/heatmap');
  $: tabs = [
    { name: 'Individual', path: `${basePath}` },
    { name: 'Heatmap', path: `${basePath}/heatmap` },
    { name: 'Chunks', path: `${basePath}/chunks` },
    { name: 'Aggregate', path: `${basePath}/aggregated` },
    { name: 'Traces', path: `${basePath}/traces`, hidden: !!data.profile.callTrace || !data.profile.traces },
    { name: 'Calls', path: `${basePath}/calls`, hidden: !data.profile.callTrace },
    { name: 'Info', path: `${basePath}/info` }
  ];

  $: info = data.diagnostics;
  $: ownerName = info.owner.name;
  $: ownerUuid = info.owner.uuid;
  $: ownerTitle = ownerUuid ? `${ownerName} (${ownerUuid})` : ownerName;
  $: duration = info?.duration ? Math.floor(info.duration / 1000.0).toString() : null;
</script>

<div class="min-h-screen min-w-0 w-full" class:heatmap-shell={heatmapPage}>
  <nav aria-label="Profile navigation" class="flex w-full shrink-0 flex-wrap items-end gap-x-4 bg-neutral-800 px-2 pt-1">
    <div class="flex max-w-full flex-wrap items-end gap-1">
      <a
        href="/"
        aria-label="TickSight"
        title="TickSight"
        class="flex h-9 w-9 shrink-0 items-center justify-center rounded-t-md hover:bg-neutral-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-neutral-300"
      >
        <img src={logo} alt="" width="30" height="30" />
      </a>
      {#each tabs as { name, path, hidden }}
        {@const focused = $page.url.pathname === path}
        <a
          href={path}
          aria-current={focused ? 'page' : undefined}
          class="rounded-t-md px-2 py-2 text-sm transition-colors sm:text-base {focused
            ? 'bg-neutral-900'
            : 'bg-neutral-700 hover:bg-neutral-600'}"
          class:hidden
        >
          {name}
        </a>
      {/each}
    </div>

    <div class="flex min-w-0 flex-auto flex-wrap items-center gap-x-3 gap-y-1 self-center py-2 text-sm max-sm:basis-full">
      <span class="inline-flex min-w-0 items-center gap-1" title={ownerTitle}>
        <User size={16} class="shrink-0" aria-hidden="true" />
        <span class="truncate">{ownerName}</span>
      </span>
      {#if info?.start}
        {@const date_str = new Date(info.start).toLocaleString()}
        <span class="inline-flex items-center gap-1 whitespace-nowrap"><Clock size={16} aria-hidden="true" />{date_str}</span>
      {/if}
      {#if duration}
        <span class="inline-flex items-center gap-1"><Hourglass size={16} aria-hidden="true" />{duration}s</span>
      {/if}
      {#if $page.params.id}
        <a
          href="/v1/get/{$page.params.id}"
          download="{$page.params.id}.json"
          aria-label="Download profile JSON"
          title="Download profile JSON"
          class="ml-auto rounded-md bg-neutral-700 p-1.5 transition-colors hover:bg-neutral-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-neutral-300"
        >
          <Download size={18} aria-hidden="true" />
        </a>
      {/if}
    </div>
  </nav>

  <div class="mx-2 min-w-0 pt-1" class:heatmap-content={heatmapPage}>
    <slot />
  </div>
</div>

<style>
  .heatmap-shell { display: flex; flex-direction: column; height: 100dvh; min-height: 0; overflow: hidden; }
  .heatmap-content { display: flex; flex: 1; min-height: 0; }
</style>
