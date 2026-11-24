<script lang="ts">
  export let view: 'tree' | 'flame' = 'tree';
</script>

<div role="group" aria-label="Stack view" class="inline-flex rounded-lg border border-neutral-700 bg-neutral-900 p-1">
  {#each [{ value: 'tree', label: 'Tree' }, { value: 'flame', label: 'Flame graph' }] as option}
    <button
      type="button"
      aria-pressed={view === option.value}
      class="h-8 rounded-md px-3 text-sm font-medium transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-neutral-300 {view === option.value ? 'bg-neutral-600 text-white hover:bg-neutral-500' : 'hover:bg-neutral-700'}"
      on:click={() => (view = option.value === 'flame' ? 'flame' : 'tree')}
    >{option.label}</button>
  {/each}
</div>
