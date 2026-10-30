import type { DominatorNodeDto, MemoryGroup } from '@/api/types';
import { palette, withAlpha } from '@/theme/palette';
import { GROUP_COLOR, GROUP_LABEL } from '@/utils/categories';
import { formatObjectId } from '@/utils/gcRoots';
import { splitClassName } from '@/utils/format';

/** A dominator with the dominators it contains. */
export interface DomNode {
  node: DominatorNodeDto;
  parent: DomNode | null;
  children: DomNode[];
  depth: number;
}

/**
 * Nests the backend's flat list of the biggest objects by retained size.
 *
 * <p>Every path from a GC root to an object passes through each of its dominators, the shortest one included. So the
 * nearest listed object on a node's GC root path that retains more is taken as its parent; a dominator can never be
 * missing from that path. Without that, the top objects overlap (a map and its table both appear) and summing them
 * would count the same bytes twice.
 */
export function buildDominatorTree(dominators: readonly DominatorNodeDto[]): DomNode[] {
  // Biggest first: a parent always comes earlier, which rules out cycles between equal sizes.
  const sorted = [...dominators].sort((a, b) => b.retainedSize - a.retainedSize || a.objectId - b.objectId);
  const rank = new Map<number, number>();
  const nodes = sorted.map((node, index) => {
    rank.set(node.objectId, index);
    return { node, parent: null, children: [], depth: 0 } as DomNode;
  });
  nodes.forEach((child, index) => {
    const path = child.node.gcRootPath;
    for (let i = path.length - 2; i >= 0; i--) {
      const step = path[i];
      const parentIndex = step ? rank.get(step.objectId) : undefined;
      if (parentIndex !== undefined && parentIndex < index) {
        const parent = nodes[parentIndex];
        if (parent) {
          child.parent = parent;
          parent.children.push(child);
        }
        break;
      }
    }
  });
  const roots = nodes.filter((n) => n.parent == null);
  const setDepth = (n: DomNode, depth: number) => {
    n.depth = depth;
    n.children.forEach((c) => setDepth(c, depth + 1));
  };
  roots.forEach((root) => setDepth(root, 0));
  return roots;
}

export function walk(nodes: readonly DomNode[], visit: (node: DomNode) => void): void {
  for (const n of nodes) {
    visit(n);
    walk(n.children, visit);
  }
}

export type SunburstFilter =
  | { type: 'group'; group: MemoryGroup }
  | { type: 'class'; group: MemoryGroup; className: string }
  | { type: 'object'; objectId: number };

export interface SunburstDatum {
  name: string;
  value: number;
  /** what a click on the sector filters by; null for the remainder */
  filter: SunburstFilter | null;
  itemStyle?: { color: string; borderColor?: string };
  children?: SunburstDatum[];
}

const MAX_OBJECT_DEPTH = 3;
const GROUP_ORDER: MemoryGroup[] = [
  'USER_BUSINESS_CODE',
  'JAKARTA_WILDFLY_INTERNAL',
  'HAZELCAST_CLIENT',
  'THIRD_PARTY_LIBRARY',
  'JDK_CORE',
];

/** Subsystem that holds a dominator: its owner on the GC root path; its own class in older summaries. */
export function ownerGroupOf(node: DominatorNodeDto): MemoryGroup {
  return node.ownerGroup ?? node.memoryGroup;
}

export function ownerClassOf(node: DominatorNodeDto): string {
  return node.ownerClassName ?? node.className;
}

const LOADER = /ClassLoader(\$|$)/;

/** A top-level holder of the heap and the bytes attributed to it. */
export interface Holder {
  node: DomNode;
  /** retained bytes, minus the nested dominators lifted out of a class loader */
  value: number;
  /** a class loader shown by its own remainder only */
  unwrapped: boolean;
}

/**
 * Who holds the heap, for the sunburst and the group shares. Every static field hangs below a class loader and its
 * class list ({@code AppClassLoader → ArrayList → Object[] → class X}), so one loader would dominate and hide the
 * whole application. Containers are therefore unwrapped: class loaders and JDK-owned dominators with nested
 * dominators pass those on as holders of their own and keep only the rest.
 */
export function holdersOf(roots: readonly DomNode[]): Holder[] {
  const result: Holder[] = [];
  const visit = (n: DomNode) => {
    const container = LOADER.test(n.node.className) || ownerGroupOf(n.node) === 'JDK_CORE';
    if (container && n.children.length > 0) {
      const nested = n.children.reduce((sum, c) => sum + c.node.retainedSize, 0);
      result.push({ node: n, value: Math.max(0, n.node.retainedSize - nested), unwrapped: true });
      n.children.forEach(visit);
    } else {
      result.push({ node: n, value: n.node.retainedSize, unwrapped: false });
    }
  };
  roots.forEach(visit);
  return result.filter((h) => h.value > 0);
}

function objectDatum(n: DomNode, value: number, withChildren: boolean, color: string, level: number): SunburstDatum {
  const [, simple] = splitClassName(n.node.className);
  return {
    name: `${simple} ${formatObjectId(n.node.objectId)}`,
    value,
    filter: { type: 'object', objectId: n.node.objectId },
    itemStyle: { color: withAlpha(color, Math.max(0.35, 0.8 - level * 0.12)) },
    children: withChildren && level < MAX_OBJECT_DEPTH && n.children.length > 0
      ? n.children.map((c) => objectDatum(c, c.node.retainedSize, true, color, level + 1))
      : undefined,
  };
}

/**
 * Rings from the center: subsystem that holds the memory (owner on the GC root path), owner class, dominator
 * objects, the dominators they contain. Sector angles are retained bytes; the rest of the heap is one grey sector.
 */
export function sunburstData(roots: readonly DomNode[], totalHeapBytes: number): SunburstDatum[] {
  const byGroup = new Map<MemoryGroup, Map<string, Holder[]>>();
  for (const holder of holdersOf(roots)) {
    const group = ownerGroupOf(holder.node.node);
    const owners = byGroup.get(group) ?? new Map<string, Holder[]>();
    const owner = ownerClassOf(holder.node.node);
    owners.set(owner, [...(owners.get(owner) ?? []), holder]);
    byGroup.set(group, owners);
  }
  const result: SunburstDatum[] = [];
  let covered = 0;
  for (const group of GROUP_ORDER) {
    const owners = byGroup.get(group);
    if (!owners) {
      continue;
    }
    const color = GROUP_COLOR[group];
    const ownerData = [...owners.entries()]
      .map(([className, members]) => ({
        className,
        members,
        value: members.reduce((sum, m) => sum + m.value, 0),
      }))
      .sort((a, b) => b.value - a.value)
      .map(({ className, members, value }, index) => ({
        name: splitClassName(className)[1],
        value,
        filter: { type: 'class', group, className } as SunburstFilter,
        itemStyle: { color: withAlpha(color, Math.max(0.45, 0.92 - index * 0.07)) },
        children: members.map((m) => objectDatum(m.node, m.value, !m.unwrapped, color, 0)),
      }));
    const value = ownerData.reduce((sum, c) => sum + c.value, 0);
    covered += value;
    result.push({ name: GROUP_LABEL[group], value, filter: { type: 'group', group }, itemStyle: { color }, children: ownerData });
  }
  const rest = totalHeapBytes - covered;
  if (rest > 0) {
    result.push({ name: 'Вне топ-доминаторов', value: rest, filter: null, itemStyle: { color: palette.border } });
  }
  return result;
}

export interface GroupShare {
  group: MemoryGroup;
  retained: number;
  objects: number;
}

export function groupShares(roots: readonly DomNode[]): GroupShare[] {
  const shares = new Map<MemoryGroup, GroupShare>();
  for (const holder of holdersOf(roots)) {
    const group = ownerGroupOf(holder.node.node);
    const share = shares.get(group) ?? { group, retained: 0, objects: 0 };
    share.retained += holder.value;
    share.objects++;
    shares.set(group, share);
  }
  return [...shares.values()].sort((a, b) => b.retained - a.retained);
}
