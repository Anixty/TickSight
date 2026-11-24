import { notification } from './stores';
import { profileCommand } from './profiling';
import type { Entry, Position } from './types';

export async function copyProfileCommand(dimension: string, entry: Entry) {
  const command = profileCommand(dimension, entry);
  try {
    await window.navigator.clipboard.writeText(command);
    notification.set(`Copied ${command} to clipboard`);
  } catch {
    notification.set(`Could not copy the command to clipboard: ${command}`);
  }
}

export async function copyTPCommand(dimension: string, entry: number | Position | (Omit<Position, 'y'> & { y: '~' })) {
  let cmd = `/ticksight tp ${dimension} `;
  if (typeof entry === 'number') {
    cmd += `entity ${entry}`;
  } else {
    let { x, y, z } = entry;
    cmd += `position ${x} ${y} ${z}`;
  }
  try {
    await window.navigator.clipboard.writeText(cmd);
    notification.set(`Copied ${cmd} to clipboard`);
  } catch {
    notification.set(`Could not copy the command to clipboard: ${cmd}`);
  }
}

export function download(filename: string, data: string) {
  let a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([data], { type: 'text/plain' }));
  a.download = filename;
  a.click();
}
