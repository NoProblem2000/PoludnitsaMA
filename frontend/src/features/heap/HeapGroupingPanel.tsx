import { useVirtualizer } from '@tanstack/react-virtual';
import clsx from 'clsx';
import { ChevronDown, ChevronRight, Loader2 } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import type { ClassLoaderKind, ClassLoaderStatsDto, ClassStatsDto, SnapshotSummaryDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { useSnapshotClasses } from '@/store/useSnapshotData';
import { palette } from '@/theme/palette';
import { formatBytes, formatNumber, formatPercent, splitClassName } from '@/utils/format';

export type Grouping = 'package' | 'classloader' | 'module';

const ROW_HEIGHT = 32;
const GRID = 'grid grid-cols-[minmax(320px,1fr)_88px_112px_120px_132px_150px] items-center gap-3';

const KIND_COLOR: Record<ClassLoaderKind, string> = {
  BOOTSTRAP: palette.cyan,
  PLATFORM: palette.cyan,
  APPLICATION: palette.emerald,
  JBOSS_MODULE: palette.violet,
  OTHER: palette.muted,
};

const KIND_LABEL: Record<ClassLoaderKind, string> = {
  BOOTSTRAP: 'bootstrap',
  PLATFORM: 'platform',
  APPLICATION: 'class path',
  JBOSS_MODULE: 'JBoss module',
  OTHER: 'other',
};

interface TreeRow {
  key: string;
  level: number;
  label: string;
  detail?: string;
  badge?: { text: string; color: string };
  expandable: boolean;
  classes: number;
  instances: number;
  shallow: number;
  /** package: sum of class retained sizes (they overlap, so an upper bound); loader: retained size of the loader */
  retained: number | null;
  mono: boolean;
}

// ------------------------------------------------------------------ package tree

interface PackageNode {
  name: string;
  path: string;
  children: Map<string, PackageNode>;
  classList: ClassStatsDto[];
  classes: number;
  instances: number;
  shallow: number;
  retained: number;
  hasRetained: boolean;
}

function newNode(name: string, path: string): PackageNode {
  return { name, path, children: new Map(), classList: [], classes: 0, instances: 0, shallow: 0, retained: 0, hasRetained: false };
}

/** Package hierarchy of all classes; arrays and primitives are grouped under their element type's package. */
function buildPackages(classes: ClassStatsDto[]): PackageNode {
  const root = newNode('', '');
  for (const stats of classes) {
    const element = stats.className.replace(/(\[\])+$/, '');
    const dot = element.lastIndexOf('.');
    const segments = dot < 0 ? ['(примитивы и массивы)'] : element.slice(0, dot).split('.');
    let node = root;
    const nodes = [root];
    let path = '';
    for (const segment of segments) {
      path = path ? `${path}.${segment}` : segment;
      let child = node.children.get(segment);
      if (!child) {
        child = newNode(segment, path);
        node.children.set(segment, child);
      }
      node = child;
      nodes.push(node);
    }
    node.classList.push(stats);
    for (const n of nodes) {
      n.classes++;
      n.instances += stats.instances;
      n.shallow += stats.shallowBytes;
      if (stats.retainedBytes != null) {
        n.retained += stats.retainedBytes;
        n.hasRetained = true;
      }
    }
  }
  return root;
}

/** Single-child chains (com → acme → orders) are shown as one row "com.acme.orders". */
function collapse(node: PackageNode): PackageNode {
  let current = node;
  while (current.children.size === 1 && current.classList.length === 0) {
    const only = [...current.children.values()][0] as PackageNode;
    current = { ...only, name: `${current.name}.${only.name}` };
  }
  return current;
}

function packageRows(root: PackageNode, expanded: Set<string>, heapBytes: number): TreeRow[] {
  const rows: TreeRow[] = [];
  const visit = (node: PackageNode, level: number) => {
    const children = [...node.children.values()].map(collapse).sort((a, b) => b.shallow - a.shallow);
    for (const child of children) {
      const open = expanded.has(child.path);
      rows.push({
        key: `p:${child.path}`,
        level,
        label: child.name,
        detail: heapBytes > 0 ? formatPercent((100 * child.shallow) / heapBytes) : undefined,
        expandable: child.children.size > 0 || child.classList.length > 0,
        classes: child.classes,
        instances: child.instances,
        shallow: child.shallow,
        // retained sizes of classes overlap: the sum is an upper bound, and never more than the heap
        retained: child.hasRetained ? Math.min(child.retained, heapBytes || child.retained) : null,
        mono: true,
      });
      if (open) {
        visit(child, level + 1);
        for (const stats of [...child.classList].sort((a, b) => b.shallowBytes - a.shallowBytes)) {
          rows.push({
            key: `c:${stats.className}`,
            level: level + 1,
            label: splitClassName(stats.className)[1],
            expandable: false,
            classes: 1,
            instances: stats.instances,
            shallow: stats.shallowBytes,
            retained: stats.retainedBytes,
            mono: true,
          });
        }
      }
    }
  };
  visit(root, 0);
  return rows;
}

// ------------------------------------------------------------------ class loaders

function loaderRows(loaders: ClassLoaderStatsDto[], expanded: Set<string>, modulesOnly: boolean): TreeRow[] {
  const rows: TreeRow[] = [];
  const list = modulesOnly ? loaders.filter((l) => l.kind === 'JBOSS_MODULE') : loaders;
  for (const loader of list) {
    const key = `l:${loader.objectId ?? 'bootstrap'}`;
    const deployment = loader.moduleName?.startsWith('deployment.') ?? false;
    rows.push({
      key,
      level: 0,
      label: loader.displayName,
      detail: loader.kind === 'JBOSS_MODULE' ? undefined : loader.loaderClassName,
      badge: deployment ? { text: 'deployment', color: palette.emerald }
        : { text: KIND_LABEL[loader.kind], color: KIND_COLOR[loader.kind] },
      expandable: loader.topClasses.length > 0,
      classes: loader.classCount,
      instances: loader.instances,
      shallow: loader.shallowBytes,
      retained: loader.retainedBytes,
      mono: loader.kind === 'JBOSS_MODULE',
    });
    if (expanded.has(key)) {
      for (const top of loader.topClasses) {
        rows.push({
          key: `${key}:${top.className}`,
          level: 1,
          label: top.className,
          expandable: false,
          classes: 1,
          instances: top.instances,
          shallow: top.shallowBytes,
          retained: null,
          mono: true,
        });
      }
    }
  }
  return rows;
}

/**
 * Memory of a deep snapshot grouped three ways: by package (all classes, a tree), by class loader, and by WildFly
 * JBoss module (ModuleClassLoaders with their module name, deployments marked).
 */
export function HeapGroupingPanel({ grouping, snapshot, classLoaders }: {
  grouping: Grouping;
  snapshot: SnapshotSummaryDto;
  classLoaders: ClassLoaderStatsDto[];
}) {
  const { classes, loading, error } = useSnapshotClasses(grouping === 'package' ? snapshot : null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const scroller = useRef<HTMLDivElement>(null);
  const heapBytes = snapshot.totalHeapBytes;

  const packages = useMemo(() => (grouping === 'package' ? buildPackages(classes) : null), [grouping, classes]);
  const rows = useMemo(() => {
    if (grouping === 'package') {
      return packages ? packageRows(packages, expanded, heapBytes) : [];
    }
    return loaderRows(classLoaders, expanded, grouping === 'module');
  }, [grouping, packages, classLoaders, expanded, heapBytes]);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  });

  const toggle = (key: string) => setExpanded((current) => {
    const next = new Set(current);
    if (!next.delete(key)) {
      next.add(key);
    }
    return next;
  });
  const toggleKey = (row: TreeRow) => (row.key.startsWith('p:') ? row.key.slice(2) : row.key);
  const isOpen = (row: TreeRow) => expanded.has(toggleKey(row));

  const retainedLabel = grouping === 'package' ? '≈ Retained (Σ классов)' : 'Retained загрузчика';
  const empty = grouping === 'package'
    ? 'В снапшоте нет классов.'
    : grouping === 'module'
      ? 'В дампе нет JBoss ModuleClassLoader: это не WildFly, или снапшот снят до появления группировки.'
      : 'Нет данных о загрузчиках: снапшот снят до появления группировки, снимите новый Deep Heap Dump.';

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className={clsx(GRID, 'border-b border-kraken-border bg-kraken-panel px-4 py-2 text-2xs font-semibold uppercase tracking-wider text-kraken-muted')}>
        <span>{grouping === 'package' ? 'Package / Class' : grouping === 'module' ? 'JBoss Module' : 'Class Loader'}</span>
        <span className="text-right">Classes</span>
        <span className="text-right">Instances</span>
        <span className="text-right">Shallow Size</span>
        <span className="text-right" title={grouping === 'package'
          ? 'Сумма retained size классов пакета: экземпляры разных классов могут удерживать одни и те же объекты, это верхняя оценка'
          : 'Retained size самого экземпляра загрузчика: HPROF не связывает загрузчик с его классами, статика классов не входит'}>
          {retainedLabel}
        </span>
        <span>% of Heap (shallow)</span>
      </div>
      <div ref={scroller} className="min-h-[160px] flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex h-full items-center justify-center gap-2 text-xs text-kraken-muted">
            <Loader2 size={14} className="animate-spin" /> Загружаем классы снапшота…
          </div>
        ) : error ? (
          <div className="flex h-full items-center justify-center text-xs text-accent-coral">{error}</div>
        ) : rows.length === 0 ? (
          <div className="flex h-full items-center justify-center px-8 text-center text-xs text-kraken-subtle">{empty}</div>
        ) : (
          <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
            {virtualizer.getVirtualItems().map((item) => {
              const row = rows[item.index];
              if (!row) {
                return null;
              }
              const share = heapBytes > 0 ? (100 * row.shallow) / heapBytes : 0;
              return (
                <div
                  key={row.key}
                  role="row"
                  onClick={() => row.expandable && toggle(toggleKey(row))}
                  className={clsx(GRID, 'absolute left-0 top-0 w-full border-b border-kraken-border/50 px-4 hover:bg-kraken-card',
                    row.expandable && 'cursor-pointer', item.index % 2 === 1 && 'bg-kraken-panel/40')}
                  style={{ height: ROW_HEIGHT, transform: `translateY(${item.start}px)` }}
                >
                  <span className="flex min-w-0 items-center gap-1.5" style={{ paddingLeft: row.level * 16 }}>
                    {row.expandable ? (isOpen(row) ? <ChevronDown size={14} className="shrink-0 text-kraken-muted" />
                      : <ChevronRight size={14} className="shrink-0 text-kraken-muted" />) : <span className="w-3.5 shrink-0" />}
                    <span className={clsx('truncate text-xs text-kraken-text', row.mono && 'font-mono')} title={row.label}>{row.label}</span>
                    {row.badge && <Badge color={row.badge.color}>{row.badge.text}</Badge>}
                    {row.detail && <span className="truncate font-mono text-2xs text-kraken-subtle">{row.detail}</span>}
                  </span>
                  <span className="text-right font-mono text-xs tabular-nums text-kraken-muted">{formatNumber(row.classes)}</span>
                  <span className="text-right font-mono text-xs tabular-nums">{formatNumber(row.instances)}</span>
                  <span className="text-right font-mono text-xs tabular-nums">{formatBytes(row.shallow)}</span>
                  <span className="text-right font-mono text-xs tabular-nums text-kraken-muted">{formatBytes(row.retained)}</span>
                  <span className="flex items-center gap-2">
                    <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-kraken-border">
                      <span className="block h-full rounded-full" style={{ width: `${Math.min(100, Math.max(1, share))}%`,
                        backgroundColor: share >= 20 ? palette.coral : share >= 5 ? palette.amber : palette.cyan }} />
                    </span>
                    <span className="w-12 text-right font-mono text-2xs tabular-nums">{formatPercent(share)}</span>
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
