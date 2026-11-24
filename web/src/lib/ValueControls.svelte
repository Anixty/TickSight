<script context="module" lang="ts">
  let nextTickHint = 0;
</script>

<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import Minus from 'lucide-svelte/icons/minus';
  import Plus from 'lucide-svelte/icons/plus';
  import { tickStartLabel, type ValueMode } from './profiling';
  import type { Profile } from './types';

  export let mode: ValueMode = 'average';
  export let tick = 1;
  export let lastTick = 1;
  export let hasPeaks = false;
  export let hasTicks = false;
  export let tickOnly = false;
  export let tickStarts: Profile['tickStarts'] = undefined;

  const tickHintId = `tick-start-${++nextTickHint}`;
  let tickInput: HTMLInputElement;
  let showTickHint = false;
  let hintLeft = 0, hintTop = 0;
  $: hintTick = tick;
  $: hint = tickStartLabel(tickStarts, hintTick);

  function positionHint() {
    if (!showTickHint || !tickInput) return;
    const rect = tickInput.getBoundingClientRect();
    hintLeft = Math.max(8, Math.min(rect.left, window.innerWidth - 328));
    hintTop = Math.max(8, Math.min(rect.bottom + 6, window.innerHeight - 56));
  }

  function openHint() { showTickHint = true; positionHint(); }

  const dispatch = createEventDispatcher<{ change: { mode: ValueMode; tick: number } }>();
  const modes: { value: ValueMode; label: string }[] = [
    { value: 'average', label: 'Average' },
    { value: 'peak', label: 'Peak' },
    { value: 'tick', label: 'Per tick' }
  ];

  function chooseMode(value: ValueMode) {
    mode = value;
    dispatch('change', { mode, tick });
  }

  function chooseTick(value: number) {
    if (!Number.isFinite(value)) return;
    const next = Math.max(1, Math.min(lastTick, Math.trunc(value)));
    if (next === tick) return;
    tick = next;
    dispatch('change', { mode, tick });
  }

  function commitTick(input: HTMLInputElement) {
    chooseTick(input.valueAsNumber);
    input.value = String(tick);
  }
</script>

<svelte:window on:resize={positionHint} on:scroll={positionHint} />

<div class="flex flex-wrap items-center gap-3">
  {#if !tickOnly}
  <div role="group" aria-label="Values" class="flex rounded-lg border border-neutral-700 bg-neutral-900 p-1">
    {#each modes as option}
      <button
        type="button"
        aria-pressed={mode === option.value}
        disabled={(option.value === 'peak' && !hasPeaks) || (option.value === 'tick' && !hasTicks)}
        class="h-8 rounded-md px-3 text-sm font-medium transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-neutral-300 disabled:opacity-40 {mode === option.value ? 'bg-neutral-600 text-white enabled:hover:bg-neutral-500' : 'enabled:hover:bg-neutral-700'}"
        on:click={() => chooseMode(option.value)}
      >{option.label}</button>
    {/each}
  </div>
  {/if}
  {#if mode === 'tick'}
    <div role="group" aria-label="Tick selection" class="tick-picker">
      <span class="text-sm font-medium text-neutral-300">Tick</span>
      <button
        type="button"
        aria-label="Previous tick"
        class="step-button"
        disabled={tick <= 1}
        on:click={() => chooseTick(tick - 1)}
      ><Minus size={16} aria-hidden="true" /></button>
      <input
        bind:this={tickInput}
        class="tick-input"
        aria-label="Tick number"
        aria-describedby={showTickHint ? tickHintId : undefined}
        type="number"
        min="1"
        max={lastTick}
        step="1"
        value={tick}
        on:mouseenter={openHint}
        on:mouseleave={() => { if (document.activeElement !== tickInput) showTickHint = false; }}
        on:focus={openHint}
        on:blur={() => showTickHint = false}
        on:input={(event) => hintTick = event.currentTarget.valueAsNumber}
        on:change={(event) => commitTick(event.currentTarget)}
        on:keydown={(event) => {
          if (event.key === 'Escape') { showTickHint = false; return; }
          if (event.key === 'Enter') {
            event.preventDefault();
            commitTick(event.currentTarget);
          }
        }}
      />
      <button
        type="button"
        aria-label="Next tick"
        class="step-button"
        disabled={tick >= lastTick}
        on:click={() => chooseTick(tick + 1)}
      ><Plus size={16} aria-hidden="true" /></button>
      <span class="whitespace-nowrap text-xs tabular-nums text-neutral-400">of {lastTick}</span>
    </div>
  {/if}
</div>

{#if showTickHint && mode === 'tick'}
  <div id={tickHintId} role="tooltip" class="tick-start-hint" style:left={`${hintLeft}px`} style:top={`${hintTop}px`}>{hint}</div>
{/if}

<style lang="postcss">
  .tick-picker {
    @apply flex items-center gap-1.5 rounded-lg border border-neutral-700 bg-neutral-900 px-2 py-1;
  }

  .tick-start-hint {
    @apply pointer-events-none fixed z-50 w-max max-w-[calc(100vw-1rem)] rounded-md border
      border-neutral-600 bg-neutral-950 px-2 py-1.5 text-xs text-neutral-100 shadow-lg;
  }

  .step-button {
    @apply inline-flex h-8 w-8 items-center justify-center rounded-md border border-neutral-600
      bg-neutral-800 text-neutral-200 transition-colors enabled:hover:border-neutral-500
      enabled:hover:bg-neutral-700 enabled:active:bg-neutral-600 disabled:opacity-40;
  }

  .tick-input {
    @apply h-8 w-20 rounded-md border border-neutral-600 bg-neutral-950 text-center
      text-sm font-semibold tabular-nums text-white;
    appearance: textfield;
  }

  .step-button:focus-visible,
  .tick-input:focus-visible {
    @apply outline outline-2 outline-offset-1 outline-neutral-300;
  }

  .tick-input::-webkit-inner-spin-button,
  .tick-input::-webkit-outer-spin-button { appearance: none; margin: 0; }
</style>
