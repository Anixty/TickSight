import type { CallMethod, MethodCall, Trace } from './types';

export interface FlameNode {
  parent: number;
  label: string;
  weight: number;
  count?: number;
  self?: number;
  untracedCalls?: number;
  untracedNanos?: number;
}

export type FlameMetric = 'total' | 'self' | 'per-parent' | 'samples';

export interface FlameTree {
  nodes: FlameNode[];
  children: Map<number, number[]>;
  unit: 'time' | 'samples';
  metric: FlameMetric;
}

export interface FlameFrame {
  index: number;
  depth: number;
  x: number;
  width: number;
  ancestor?: boolean;
}

const positive = (value: number | undefined) =>
  value != null && Number.isFinite(value) && value > 0 ? value : 0;
const decimals = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 });
const perParent = new Intl.NumberFormat(undefined, { maximumSignificantDigits: 4 });

/** The same quiet green-to-amber scale is used by the tree and the flame graph. */
export function heatColor(percent: number, alpha = 1) {
  const stops = [
    { at: 0, rgb: [40, 91, 69] },
    { at: 40, rgb: [94, 109, 60] },
    { at: 70, rgb: [139, 113, 54] },
    { at: 100, rgb: [166, 95, 43] }
  ];
  const value = Math.min(100, Math.max(0, percent));
  const end = stops.findIndex((stop) => stop.at >= value);
  const high = stops[Math.max(1, end)];
  const low = stops[Math.max(0, end - 1)];
  const amount = (value - low.at) / (high.at - low.at);
  const rgb = low.rgb.map((channel, index) => Math.round(channel + (high.rgb[index] - channel) * amount));
  return `rgb(${rgb.join(' ')} / ${alpha})`;
}

export function elapsedLabel(nanos: number) {
  return nanos >= 1_000_000
    ? `${decimals.format(nanos / 1_000_000)} ms`
    : `${decimals.format(nanos / 1_000)} µs`;
}

export function flameNodeDetail(node: FlameNode, unit: FlameTree['unit'], parent?: FlameNode) {
  if (unit === 'samples') return `${node.weight.toLocaleString()} ${node.weight === 1 ? 'sample' : 'samples'}`;
  const count = node.count ?? 0;
  const parentCount = (parent?.count ?? 0) - (parent?.untracedCalls ?? 0);
  return `Total ${elapsedLabel(node.weight)} · Self ${node.self == null ? '—' : elapsedLabel(node.self)} · ${count.toLocaleString()} ${count === 1 ? 'call' : 'calls'}`
    + (parentCount > 0 ? ` · ${perParent.format(count / parentCount)} per ${parent?.untracedCalls ? 'traced parent' : 'parent'}` : '')
    + (node.untracedCalls ? ` · ${node.untracedCalls.toLocaleString()} untraced`
      + (node.untracedNanos != null ? ` (${elapsedLabel(node.untracedNanos)})` : '') : '');
}

export function callFlameTree(
  calls: MethodCall[],
  methods: CallMethod[],
  children: Map<number, number[]>,
  selfTimes: (number | undefined)[],
  metric: Exclude<FlameMetric, 'samples'>
): FlameTree {
  return {
    unit: 'time',
    metric,
    children,
    nodes: calls.map((call, index) => {
      const method = methods[call.method];
      return {
        parent: call.parent,
        label: method ? `${method.className}.${method.name}${method.descriptor}` : 'Target tick',
        weight: positive(call.elapsedNanos),
        count: call.count,
        self: selfTimes[index],
        untracedCalls: call.untracedCalls,
        untracedNanos: call.untracedNanos
      };
    })
  };
}

export function traceFlameTree(trace: Trace): FlameTree {
  const nodes: FlameNode[] = [];
  const children = new Map<number, number[]>();
  const pending = [{ trace, parent: -1 }];
  while (pending.length) {
    const { trace, parent } = pending.pop()!;
    const index = nodes.length;
    nodes.push({
      parent,
      label: `${trace.className}:${trace.methodName}`,
      weight: positive(trace.count)
    });
    if (parent >= 0) {
      const siblings = children.get(parent);
      if (siblings) siblings.push(index);
      else children.set(parent, [index]);
    }
    const sorted = [...trace.children].sort((a, b) => b.count - a.count);
    for (let child = sorted.length - 1; child >= 0; child--) {
      pending.push({ trace: sorted[child], parent: index });
    }
  }
  return { nodes, children, unit: 'samples', metric: 'samples' };
}

/** Follow the data's parent links, including ancestors skipped by a pointer click. */
export function flamePath(tree: FlameTree, root: number) {
  const path: number[] = [];
  const seen = new Set<number>();
  let index = root;
  while (tree.nodes[index] && !seen.has(index)) {
    path.push(index);
    seen.add(index);
    index = tree.nodes[index].parent;
  }
  return path.reverse();
}

/** Inclusive weights leave self/untraced space empty, rather than stretching children. */
export function layoutFlame(tree: FlameTree, root = 0) {
  const rows: FlameFrame[][] = [];
  const frames: (FlameFrame | undefined)[] = new Array(tree.nodes.length);
  const visited = new Uint8Array(tree.nodes.length);
  const path = flamePath(tree, root);
  for (let depth = 0; depth < path.length - 1; depth++) {
    const index = path[depth];
    const frame = { index, depth, x: 0, width: 1, ancestor: true };
    rows[depth] = [frame];
    frames[index] = frame;
    visited[index] = 1;
  }
  const pending: FlameFrame[] = tree.nodes[root]
    ? [{ index: root, depth: path.length - 1, x: 0, width: 1 }] : [];
  let adjusted = false;
  while (pending.length) {
    const frame = pending.pop()!;
    const { index, depth, x, width } = frame;
    if (visited[index]) continue;
    visited[index] = 1;
    frames[index] = frame;
    (rows[depth] ??= []).push(frame);
    const siblings = tree.children.get(index) ?? [];
    let sum = 0;
    for (const child of siblings) sum += positive(tree.nodes[child]?.weight);
    const weight = positive(tree.nodes[index].weight);
    // Malformed/rounded totals may exceed the parent; never draw outside its bounds.
    if (sum > weight && sum - weight > Math.max(1, weight * 1e-9)) adjusted = true;
    const denominator = Math.max(weight, sum);
    if (denominator === 0) continue;
    let right = x + width * sum / denominator;
    for (let slot = siblings.length - 1; slot >= 0; slot--) {
      const child = siblings[slot];
      const childWidth = width * positive(tree.nodes[child]?.weight) / denominator;
      right -= childWidth;
      if (childWidth > 0) pending.push({ index: child, depth: depth + 1, x: right, width: childWidth });
    }
  }
  return { rows, frames, path, adjusted };
}

export function callsPerParent(tree: FlameTree, index: number) {
  const node = tree.nodes[index];
  const parent = tree.nodes[node.parent];
  const count = (parent?.count ?? 0) - (parent?.untracedCalls ?? 0);
  return count > 0 && node.count != null ? node.count / count : undefined;
}

/** Width always means inclusive weight; color measures the explicitly chosen metric. */
export function flameColoring(tree: FlameTree, graph: ReturnType<typeof layoutFlame>) {
  const root = tree.nodes[graph.path[graph.path.length - 1]];
  const denominator = positive(root?.weight);
  const scores = new Array<number | undefined>(tree.nodes.length);
  let maximum = 1;
  if (tree.metric === 'per-parent') {
    for (const frame of graph.frames) {
      if (frame && !frame.ancestor) maximum = Math.max(maximum, callsPerParent(tree, frame.index) ?? 0);
    }
  }
  for (const frame of graph.frames) {
    if (!frame || frame.ancestor) continue;
    const node = tree.nodes[frame.index];
    if (tree.metric === 'per-parent') {
      const ratio = callsPerParent(tree, frame.index);
      if (ratio != null) scores[frame.index] = maximum > 1 ? 100 * Math.max(0, ratio - 1) / (maximum - 1) : 0;
    } else {
      const value = tree.metric === 'self' ? node.self : node.weight;
      if (value != null && denominator > 0) scores[frame.index] = 100 * Math.max(0, value) / denominator;
    }
  }
  return { scores, denominator, maximum };
}

export function flameColorDetail(tree: FlameTree, index: number, colors: ReturnType<typeof flameColoring>) {
  const node = tree.nodes[index];
  if (tree.metric === 'per-parent') {
    const parent = tree.nodes[node.parent];
    const denominator = (parent?.count ?? 0) - (parent?.untracedCalls ?? 0);
    const ratio = callsPerParent(tree, index);
    return ratio == null ? 'Calls per parent —'
      : `Calls per ${parent?.untracedCalls ? 'traced ' : ''}parent · ${node.count?.toLocaleString()} / ${denominator.toLocaleString()} = ${perParent.format(ratio)}`
        + (colors.maximum > 1 ? ` · view max ${perParent.format(colors.maximum)}` : '');
  }
  const value = tree.metric === 'self' ? node.self : node.weight;
  const metric = tree.metric === 'self' ? 'Self time' : tree.metric === 'samples' ? 'Samples' : 'Total time';
  if (value == null || !colors.denominator) return `${metric} —`;
  const format = tree.unit === 'time' ? elapsedLabel : (value: number) => value.toLocaleString();
  return `${metric} · ${format(value)} / ${format(colors.denominator)} in view (${(100 * value / colors.denominator).toFixed(2)}%)`;
}
