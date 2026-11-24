<script lang="ts">
  import { type Trace } from '$lib/types';
  import { Minus, Plus } from 'lucide-svelte';
  export let data: Trace;
  export let total: number | undefined = undefined;
  export let expanded = true;
  export let depth = 0;
</script>

<div class="w-full min-w-0" style:--trace-depth={depth}>
  <button class="flex w-full min-w-0 items-start gap-2 rounded px-1 py-0.5 text-left hover:bg-neutral-800" aria-expanded={expanded} on:click={() => (expanded = !expanded)}>
    <span class="shrink-0 text-blue-400" class:invisible={data.children.length === 0}>
      <svelte:component this={expanded ? Minus : Plus} size={16} />
    </span>
    <code class="min-w-0 break-all text-sm">{data.className}:{data.methodName}</code>
    <span class="ml-auto shrink-0 text-sm tabular-nums">{((100.0 * data.count) / (total ?? data.count)).toFixed(2)}%</span>
  </button>
  {#if expanded}
    <div class="trace-children border-neutral-700" class:branch={data.children.length > 1}>
      {#each data.children as trace}
        <svelte:self
          data={trace}
          total={total ?? data.count}
          expanded={data.children.length === 1}
          depth={depth + 1}
        />
      {/each}
    </div>
  {/if}
</div>

<style>
  .trace-children {
    margin-left: clamp(0px, calc((12 - var(--trace-depth)) * 7px), 7px);
    padding-left: clamp(0px, calc((12 - var(--trace-depth)) * 16px), 16px);
  }
  .trace-children.branch { border-left-width: clamp(0px, calc((12 - var(--trace-depth)) * 2px), 2px); }
  @media (max-width: 640px) {
    .trace-children {
      margin-left: clamp(0px, calc((4 - var(--trace-depth)) * 4px), 4px);
      padding-left: clamp(0px, calc((4 - var(--trace-depth)) * 8px), 8px);
    }
    .trace-children.branch { border-left-width: clamp(0px, calc((4 - var(--trace-depth)) * 2px), 2px); }
  }
</style>
