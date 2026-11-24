<script lang="ts" context="module">
  const decimals = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
  const perParent = new Intl.NumberFormat(undefined, { maximumSignificantDigits: 4 });

  function formatElapsed(nanos: number) {
    return nanos >= 1_000_000
      ? `${decimals.format(nanos / 1_000_000)} ms`
      : `${decimals.format(nanos / 1_000)} µs`;
  }
</script>

<script lang="ts">
  import ChevronDown from 'lucide-svelte/icons/chevron-down';
  import ChevronRight from 'lucide-svelte/icons/chevron-right';
  import type { CallMethod, MethodCall } from '$lib/types';
  import { heatColor } from '$lib/flamegraph';

  export let index: number;
  export let calls: MethodCall[];
  export let methods: CallMethod[];
  export let children: Map<number, number[]>;
  export let selfTimes: (number | undefined)[];
  export let heatScores: (number | undefined)[];
  export let colorRows: boolean;
  export let depth = 0;

  let expanded = index === 0;
  $: call = calls[index];
  $: method = methods[call.method];
  $: childIndices = children.get(index) ?? [];
  $: parent = calls[call.parent];
  $: rootTime = calls[0].elapsedNanos ?? 0;
  $: totalPercent = call.elapsedNanos != null && rootTime > 0 ? 100 * call.elapsedNanos / rootTime : undefined;
  $: ownTime = selfTimes[index];
  $: heatScore = heatScores[index];
  $: tracedParentCalls = parent ? parent.count - (parent.untracedCalls ?? 0) : 0;
</script>

<div class="min-w-0" style:--call-depth={depth}>
  <div
    class="grid min-h-8 grid-cols-[auto_minmax(0,1fr)] items-center gap-x-2 gap-y-1 rounded px-1 py-1 hover:bg-neutral-800 lg:flex"
    style:background-color={colorRows && heatScore != null ? heatColor(heatScore, 0.45) : null}
  >
    {#if childIndices.length}
      <button
        type="button"
        aria-expanded={expanded}
        aria-label={expanded ? 'Collapse calls' : 'Expand calls'}
        class="shrink-0 rounded p-1 text-blue-400 hover:bg-neutral-700"
        on:click={() => (expanded = !expanded)}
      >
        <svelte:component this={expanded ? ChevronDown : ChevronRight} size={16} />
      </button>
    {:else}
      <span class="w-6 shrink-0"></span>
    {/if}
    {#if method}
      <code class="min-w-0 break-all text-sm" title={method.descriptor}>{method.className}.{method.name}</code>
    {:else}
      <span class="font-medium">Target tick</span>
    {/if}
    <div class="col-start-2 flex flex-wrap gap-x-3 text-sm tabular-nums lg:ml-auto lg:shrink-0 lg:justify-end">
      {#if call.elapsedNanos != null}
        <span class="whitespace-nowrap text-neutral-200">
          <span class="text-neutral-300">Total</span> {formatElapsed(call.elapsedNanos)}
          {#if totalPercent != null}
            <span class="text-blue-200">· {decimals.format(totalPercent)}%</span>
          {/if}
        </span>
      {/if}
      {#if ownTime != null}
        <span class="whitespace-nowrap text-neutral-200">
          <span class="text-neutral-300">Self</span> {formatElapsed(ownTime)}
          {#if rootTime > 0}
            <span class="text-blue-200">· {decimals.format(100 * ownTime / rootTime)}%</span>
          {/if}
        </span>
      {:else if call.elapsedNanos != null}
        <span class="whitespace-nowrap text-neutral-400">Self —</span>
      {/if}
      <span>
        {call.count.toLocaleString()} {call.count === 1 ? 'call' : 'calls'}
        {#if tracedParentCalls > 0}
          <span class="text-neutral-400">
            · {perParent.format(call.count / tracedParentCalls)}
            {parent?.untracedCalls ? 'per traced parent' : 'per parent'}
          </span>
        {/if}
      </span>
      {#if call.untracedCalls}
        <span class="whitespace-nowrap text-amber-200">
          {call.untracedCalls.toLocaleString()} untraced
          {#if call.untracedNanos != null}· {formatElapsed(call.untracedNanos)}{/if}
        </span>
      {/if}
    </div>
  </div>
  {#if expanded && childIndices.length}
    <div class="call-children border-neutral-700">
      {#each childIndices as child (child)}
        <svelte:self index={child} {calls} {methods} {children} {selfTimes} {heatScores} {colorRows} depth={depth + 1} />
      {/each}
    </div>
  {/if}
</div>

<style>
  .call-children {
    margin-left: clamp(0px, calc((12 - var(--call-depth)) * 12px), 12px);
    padding-left: clamp(0px, calc((12 - var(--call-depth)) * 8px), 8px);
    border-left-width: clamp(0px, calc((12 - var(--call-depth)) * 1px), 1px);
  }
  @media (max-width: 640px) {
    .call-children {
      margin-left: clamp(0px, calc((4 - var(--call-depth)) * 8px), 8px);
      padding-left: clamp(0px, calc((4 - var(--call-depth)) * 4px), 4px);
      border-left-width: clamp(0px, calc((4 - var(--call-depth)) * 1px), 1px);
    }
  }
</style>
