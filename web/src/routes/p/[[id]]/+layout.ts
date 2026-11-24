import { localData, localReportFile } from '$lib/stores';
import { get } from 'svelte/store';
import type { DataWithDiagnostics, InstanceEntry, Profile, ReportFileDetails } from '../../../lib/types';
import { error } from '@sveltejs/kit';

export const ssr = false;

const getEntries = (profile: Profile) => {
  if (!profile) return [];
  let entityMap = new Map(Object.entries(profile.entities));
  let globalTicks = profile.ticks;
  let result: {
    name: string;
    rate: number;
    entries: InstanceEntry[];
  }[] = [];
  const dimensions = new Set([...Object.keys(profile.blocks), ...Object.keys(profile.entities)]);
  for (let name of dimensions) {
    let combined = [
      ...(entityMap.get(name) ?? []).map((entry, index) => ({
        ...entry, source: 'entities' as const, index
      })),
      ...(profile.blocks[name] ?? []).map((entry, index) => ({
        ...entry, source: 'blocks' as const, index
      }))
    ].map((entry) => ({
      ...entry,
      rate: globalTicks > 0 ? entry.rate * entry.ticks / globalTicks : 0
    }));
    let rate = 0;
    for (let e of combined) {
      rate += e.rate;
    }
    combined.sort((a, b) => b.rate - a.rate);
    result.push({
      name,
      rate,
      entries: combined
    });
  }
  return result.sort((a, b) => b.rate - a.rate);
};

export async function load({ params, fetch }) {
  let data: DataWithDiagnostics;
  let reportFile: ReportFileDetails | null;
  if (!params.id) {
    const local = get(localData);
    if (!local) error(404, 'No data');
    data = local;
    reportFile = get(localReportFile);
  } else {
    const res = await fetch(`/v1/get/${params.id}`);
    if (!res.ok) error(res.status, res.status === 404 ? 'Profile not found' : 'Could not load the profile');
    const bytes = await res.arrayBuffer();
    data = JSON.parse(new TextDecoder().decode(bytes));
    const encoding = res.headers.get('content-encoding');
    const transferBytes = Number(res.headers.get('content-length'));
    reportFile = {
      bytes: bytes.byteLength,
      source: 'http',
      ...(encoding && encoding !== 'identity' && Number.isSafeInteger(transferBytes) && transferBytes > 0
        ? { transferBytes, encoding } : {})
    };
  }
  const profile = data?.data;
  const diagnostics = data?.diagnostics;
  if (!profile?.entities || !profile.blocks || !Number.isInteger(profile.timelineTicks) || profile.timelineTicks < 0 ||
      typeof diagnostics?.owner?.name !== 'string' || (profile.callTrace && !profile.callTrace.objectTicks)) {
    error(400, 'Expected a TickSight report');
  }
  const entries = getEntries(profile);
  const hasHistory = entries.some(({ entries }) => entries.some((entry) => entry.tickHistory?.times.length));
  return {
    profile,
    diagnostics,
    reportFile,
    entries,
    timelineTicks: profile.timelineTicks,
    hasHistory
  };
}
