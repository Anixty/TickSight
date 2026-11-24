<script lang="ts">
  import { onMount } from 'svelte';
  import type { PageData } from './$types';
  import { recordedLayers } from '$lib/chunkTelemetry';

  export let data: PageData;
  type Field = { label: string; value: string | undefined };
  const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
  const precise = new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 });
  const present = (value: number | undefined) => value != null && Number.isFinite(value);
  const count = (value: number | undefined) => present(value) ? value!.toLocaleString() : undefined;
  const unit = (value: number | undefined, suffix: string) => present(value) ? `${number.format(value!)} ${suffix}` : undefined;
  const flag = (value: boolean | undefined) => value == null ? undefined : value ? 'Enabled' : 'Disabled';
  const date = (value: number | undefined) => present(value) ? new Date(value!).toLocaleString() : undefined;
  const repeats = (value: number | undefined) => value === -1 ? 'Unlimited' : count(value);
  const known = (rows: Field[]) => rows.filter((field) => field.value !== undefined);
  const nanos = (value: number | undefined) => !present(value) ? undefined : value! >= 1_000_000
    ? `${precise.format(value! / 1_000_000)} ms` : value! >= 1000
      ? `${number.format(value! / 1000)} µs` : `${number.format(value!)} ns`;
  const stopLabels: Record<string, string> = {
    duration: 'Requested duration reached', stopped: 'Stopped manually', limit: 'Recording limit',
    tick_time: 'Tick time limit', hard_tick_time: 'Watchdog limit', memory: 'Memory budget',
    heap: 'Heap reserve', report_size: 'Report size limit'
  };

  function bytes(value: number | undefined) {
    if (!present(value)) return undefined;
    const amount = value! >= 1_048_576 ? `${number.format(value! / 1_048_576)} MiB`
      : value! >= 1024 ? `${number.format(value! / 1024)} KiB` : `${value} B`;
    return `${amount} · ${value!.toLocaleString()} bytes`;
  }

  $: info = data.diagnostics;
  $: recording = info.recording;
  $: telemetry = data.profile.chunkTelemetry;
  $: recorded = recordedLayers(telemetry, recording?.layers);
  const recordedLabels = { runtime: 'Runtime zones', generation: 'Generation stages', ticketLevels: 'Ticket levels',
    ticketSources: 'Ticket sources', visibility: 'Server visibility', players: 'Players' };
  $: disabledLayers = Object.entries(recorded).filter(([, enabled]) => !enabled).map(([key]) => recordedLabels[key as keyof typeof recordedLabels]);
  $: calls = data.profile.callTrace;
  $: limits = recording?.calls;
  $: settings = info.settings;
  $: server = info.server;
  $: runtime = server?.runtime;
  $: sampleCount = data.profile.traces?.count;
  $: status = recording?.completed === true ? 'Completed'
    : recording?.completed === false || calls?.cutoff ? 'Partial recording' : undefined;
  $: summary = known([
    { label: 'Report JSON', value: bytes(data.reportFile?.bytes) },
    { label: 'Transferred', value: data.reportFile?.transferBytes != null
      ? `${bytes(data.reportFile.transferBytes)} · ${data.reportFile.encoding}` : undefined },
    { label: 'File', value: data.reportFile?.fileName },
    { label: 'Content', value: info.content === 'overlay' ? 'Overlay summary' : info.content === 'full' ? 'Full report' : undefined },
    { label: 'Recorded on', value: info.source === 'server' ? 'Minecraft server' : info.source === 'client' ? 'Client' : undefined },
    { label: 'Saved by', value: info.exportedBy === 'client' ? 'Client' : undefined },
    { label: 'Recorded duration', value: info.duration != null ? unit(info.duration / 1000, 's') : undefined },
    { label: 'Recorded ticks', value: count(data.profile.ticks) },
    { label: 'Timeline ticks', value: data.profile.timelineTicks > 0 ? count(data.profile.timelineTicks) : undefined },
    { label: 'Started', value: date(info.start) },
    { label: 'Owner', value: info.owner.name },
    { label: 'Recorded data', value: calls ? 'Object timings · method calls'
      : sampleCount ? 'Object timings · stack samples' : data.entries.some((group) => group.entries.length) ? 'Object timings' : undefined },
    { label: 'Stack samples', value: count(sampleCount) },
    { label: 'Objects', value: data.entries.reduce((sum, group) => sum + group.entries.length, 0).toLocaleString() },
    { label: 'Status', value: status }
  ]);
  $: recordingFields = known([
    { label: 'Mode', value: recording?.mode === 'calls' ? 'Exact method calls' : recording?.mode === 'sample' ? 'Stack sampling'
      : recording?.mode === 'timings' ? 'Object timings' : undefined },
    { label: 'Requested duration', value: recording?.requestedDuration
      ? unit(recording.requestedDuration.amount, recording.requestedDuration.unit === 'ticks' ? 'ticks' : 's') : undefined },
    { label: 'Initial view', value: recording?.initialView ?? (typeof data.profile.peakMode === 'boolean' ? data.profile.peakMode ? 'Peak' : 'Average' : undefined) },
    { label: 'Target', value: recording?.target ?? calls?.target },
    { label: 'Scope', value: recording?.scope },
    { label: 'Disabled layers', value: telemetry || recording?.layers ? disabledLayers.length ? disabledLayers.join(' · ') : 'None' : undefined },
    { label: 'Sampler delay', value: nanos(recording?.samplingIntervalNanos) },
    { label: 'Sampler jitter', value: recording?.samplingDeviationNanos != null ? `±${nanos(recording.samplingDeviationNanos)}` : undefined },
    { label: 'Samples / elapsed s', value: sampleCount != null && info.duration != null && info.duration > 0
      ? number.format(sampleCount * 1000 / info.duration) : undefined },
    { label: 'Stop reason', value: recording?.stopReason ? stopLabels[recording.stopReason] ?? recording.stopReason
      : calls?.cutoff?.reason ? stopLabels[calls.cutoff.reason] ?? calls.cutoff.reason : undefined },
    { label: 'Stopped at tick', value: count(calls?.cutoff?.tick) },
    { label: 'Tick limit', value: unit(limits?.tickLimitMs ?? calls?.limitMs, 'ms') },
    { label: 'Watchdog limit', value: unit(limits?.hardTickLimitMs ?? calls?.hardLimitMs, 'ms') },
    { label: 'Repeat limit', value: repeats(limits?.maxRepeats ?? calls?.maxRepeats) },
    { label: 'Memory budget', value: unit(limits?.memoryLimitMiB ?? calls?.memoryLimitMiB, 'MiB') },
    { label: 'Heap reserve', value: unit(limits?.heapReserveMiB ?? calls?.heapReserveMiB, 'MiB') },
    { label: 'Recorder reservation', value: bytes(calls?.reservedBytes) },
    { label: 'Skipped classes', value: count(calls?.skippedClasses) },
    { label: 'Skipped constructors', value: count(calls?.skippedMethods) },
    { label: 'Recording started', value: recording?.started === false ? 'No' : undefined }
  ]);
  $: telemetryFields = known([
    { label: 'Format', value: telemetry ? `v${telemetry.version}` : undefined },
    { label: 'Snapshots', value: count(telemetry?.frames.length) },
    { label: 'Complete through tick', value: count(telemetry?.completeThroughTick) },
    { label: 'Cut off at tick', value: count(telemetry?.truncated?.tick) },
    { label: 'Cutoff reason', value: telemetry?.truncated?.reason }
  ]);
  $: settingFields = known([
    { label: 'Sampling interval', value: settings?.traceIntervalMs != null ? nanos(settings.traceIntervalMs * 1_000_000) : undefined },
    { label: 'Sampling deviation', value: settings?.deviationMs != null ? nanos(settings.deviationMs * 1_000_000) : undefined },
    { label: 'JVM flags', value: settings?.includeJvmArgs && info.jvmFlagsSanitized ? 'Safe options only' : flag(settings?.includeJvmArgs) },
    { label: 'Tick limit', value: unit(settings?.callTraceTickLimitMs, 'ms') },
    { label: 'Repeat limit', value: repeats(settings?.callTraceMaxRepeats) },
    { label: 'Watchdog limit', value: unit(settings?.callTraceHardTickLimitMs, 'ms') },
    { label: 'Memory budget', value: unit(settings?.callTraceMemoryLimitMiB, 'MiB') },
    { label: 'Heap reserve', value: unit(settings?.callTraceHeapReserveMiB, 'MiB') },
    { label: 'Captured', value: settings ? date(info.capturedAt) : undefined }
  ]);
  $: serverFields = known([
    { label: 'Type', value: server?.type === 'dedicated' ? 'Dedicated' : server?.type === 'integrated' ? 'Integrated' : undefined },
    { label: 'Minecraft', value: server?.minecraftVersion },
    { label: 'Loader', value: server?.modLoader ? [server.modLoader, server.loaderVersion].filter(Boolean).join(' ') : undefined },
    { label: 'TickSight', value: server?.tickSightVersion },
    { label: 'Players', value: server?.players != null ? `${count(server.players)}${server.maxPlayers != null ? ` / ${count(server.maxPlayers)}` : ''}` : undefined },
    { label: 'View distance', value: unit(server?.viewDistance, 'chunks') },
    { label: 'Simulation distance', value: unit(server?.simulationDistance, 'chunks') },
    { label: 'Game mode', value: server?.gameMode },
    { label: 'Java', value: runtime?.javaVersion ? [runtime.javaVersion, runtime.javaVendor].filter(Boolean).join(' · ') : undefined },
    { label: 'JVM', value: runtime?.javaVm },
    { label: 'OS', value: runtime?.os ? [runtime.os, runtime.osVersion, runtime.architecture].filter(Boolean).join(' · ') : undefined },
    { label: 'Processors', value: count(runtime?.processors) },
    { label: 'Max heap', value: bytes(runtime?.maxHeapBytes) }
  ]);
  $: environmentFields = known([
    { label: 'Source', value: info.source === 'server' ? 'Server' : info.source === 'client' ? 'Client' : 'Not recorded' },
    { label: 'Minecraft', value: info.minecraftVersion },
    { label: 'Loader', value: info.modLoader },
    { label: info.tickSightVersion ? 'TickSight' : 'Producer version', value: info.tickSightVersion ?? info.observableVersion }
  ]);
  $: sections = [
    { title: 'Report', fields: summary, missing: '' },
    { title: 'Recording', fields: recordingFields, missing: 'Recording parameters were not saved.' },
    ...(telemetry ? [{ title: 'Chunk state history', fields: telemetryFields, missing: '' }] : []),
    { title: 'TickSight settings at start', fields: settingFields, missing: 'Not recorded.' },
    { title: 'Minecraft server', fields: serverFields, missing: 'Server details were not saved.' },
    ...(!server ? [{ title: 'Recorded environment', fields: environmentFields, missing: '' }] : [])
  ];
  $: rawReport = info.additionalDiagnostics?.['System Report'];
  $: systemReport = typeof rawReport === 'string'
    ? info.jvmFlagsSanitized ? rawReport : rawReport.replace(/^.*JVM Flags\s*:.*$/gmi, 'JVM Flags: Not shown')
    : undefined;
  $: rawMods = info.additionalDiagnostics?.Mods;
  $: mods = typeof rawMods === 'string' ? rawMods : undefined;
  $: detailSource = info.source === 'server' ? 'Server' : info.source === 'client' ? 'Client' : undefined;

  onMount(() => {
    document.documentElement.classList.add('ticksight-info-view');
    return () => document.documentElement.classList.remove('ticksight-info-view');
  });
</script>

<article class="info-page min-w-0 space-y-5 py-3" aria-label="Report information">
  <h1 class="sr-only">Report information</h1>
  {#each sections as section}
    <section class="min-w-0 space-y-2" aria-label={section.title}>
      <h2 class="text-base font-semibold">{section.title}</h2>
      {#if section.fields.length}
        <dl class="info-grid text-sm">
          {#each section.fields as field}
            <div class="info-field">
              <dt class="text-neutral-400">{field.label}</dt>
              <dd class="min-w-0 text-neutral-100">{field.value}</dd>
            </div>
          {/each}
        </dl>
      {:else if section.missing}
        <p class="text-sm text-neutral-400">{section.missing}</p>
      {/if}
    </section>
  {/each}
  {#if systemReport || mods}
    <section class="min-w-0 space-y-2" aria-label="Detailed diagnostics">
      {#if systemReport}
        <details class="min-w-0">
          <summary class="cursor-pointer rounded py-1 text-sm text-blue-300 focus-visible:outline focus-visible:outline-1 focus-visible:outline-neutral-300">
            {detailSource ? `${detailSource} system report` : 'System report · source not recorded'}
          </summary>
          <pre class="diagnostic-text mt-2 text-xs leading-relaxed text-neutral-300">{systemReport}</pre>
        </details>
      {/if}
      {#if mods}
        <details class="min-w-0">
          <summary class="cursor-pointer rounded py-1 text-sm text-blue-300 focus-visible:outline focus-visible:outline-1 focus-visible:outline-neutral-300">
            {detailSource ? `${detailSource} mods` : 'Mods · source not recorded'}
          </summary>
          <pre class="diagnostic-text mt-2 text-xs leading-relaxed text-neutral-300">{mods}</pre>
        </details>
      {/if}
    </section>
  {/if}
</article>

<style>
  .info-grid { display: flex; flex-direction: column; gap: 0.375rem; }
  .info-field { display: grid; min-width: 0; grid-template-columns: minmax(0, 8.5rem) minmax(0, 1fr); gap: 0.75rem; overflow-wrap: anywhere; }
  .diagnostic-text { white-space: pre-wrap; overflow-wrap: anywhere; }
  :global(html.ticksight-info-view),
  :global(html.ticksight-info-view body) { scrollbar-width: none; }
  :global(html.ticksight-info-view::-webkit-scrollbar),
  :global(html.ticksight-info-view body::-webkit-scrollbar) { display: none; }
</style>

