import { writable } from 'svelte/store';
import type { DataWithDiagnostics, ReportFileDetails } from './types';

export const notification = writable('');

export const localData = writable<DataWithDiagnostics | null>(null);
export const localReportFile = writable<ReportFileDetails | null>(null);
