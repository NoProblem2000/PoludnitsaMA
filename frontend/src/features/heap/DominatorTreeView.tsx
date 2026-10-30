import { useVirtualizer } from '@tanstack/react-virtual';
import clsx from 'clsx';
import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  Loader2,
  Network,
  Route,
  Search,
  X,
} from 'lucide-react';
import { useDeferredValue, useMemo, useRef, useState } from 'react';
import type { EChartsCoreOption } from 'echarts/core';
import type { DominatorNodeDto } from '@/api/types';
import { EChart, type EChartEvent } from '@/components/charts/EChart';
import { Badge } from '@/components/common/Badge';
import { CopyButton } from '@/components/common/CodeBlock';
import { EmptyState } from '@/components/common/EmptyState';
import { Modal } from '@/components/common/Modal';
import { SnapshotSelect } from '@/components/common/SnapshotSelect';
import { GcRootPathGraph } from '@/components/heap/GcRootPathGraph';
import { useChosenDeepSnapshot, useDeepAnalysis } from '@/store/useSnapshotData';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { GROUP_COLOR, GROUP_LABEL } from '@/utils/categories';
import { classifyRoot, formatObjectId, ROOT_TYPE_COLOR, ROOT_TYPE_LABEL } from '@/utils/gcRoots';
import { formatBytes, formatNumber, formatPercent, splitClassName } from '@/utils/format';
import {
  buildDominatorTree,
  type DomNode,
  groupShares,
  holdersOf,
  ownerClassOf,
  ownerGroupOf,
  type SunburstDatum,
  sunburstData,
  type SunburstFilter,
  walk,
} from './dominatorTree';

type SortKey = 'name' | 'shallow' | 'retained';

interface Row {
  node: DomNode;
  /** indentation; 0 in flat (filtered) lists */
  level: number;
  expandable: boolean;
}

const GRID = 'grid grid-cols-[minmax(300px,1fr)_170px_96px_108px_150px_124px_170px] items-center gap-3';
const ROW_HEIGHT = 34;

function compare(key: SortKey, a: DomNode, b: DomNode): number {
  switch (key) {
    case 'name':
      return a.node.className.localeCompare(b.node.className);
    case 'shallow':
      return a.node.shallowSize - b.node.shallowSize;
    case 'retained':
      return a.node.retainedSize - b.node.retainedSize;
  }
}

function matches(filter: SunburstFilter, n: DominatorNodeDto): boolean {
  switch (filter.type) {
    case 'group':
      return ownerGroupOf(n) === filter.group;
    case 'class':
      return ownerClassOf(n) === filter.className;
    case 'object':
      return n.objectId === filter.objectId;
  }
}

function filterLabel(filter: SunburstFilter, find: (id: number) => DomNode | undefined): string {
  switch (filter.type) {
    case 'group':
      return GROUP_LABEL[filter.group];
    case 'class':
      return `${GROUP_LABEL[filter.group]} › ${splitClassName(filter.className)[1]}`;
    case 'object': {
      const n = find(filter.objectId);
      return n ? `${splitClassName(n.node.className)[1]} ${formatObjectId(n.node.objectId)}` : formatObjectId(filter.objectId);
    }
  }
}

function sunburstOption(data: SunburstDatum[], totalHeapBytes: number): EChartsCoreOption {
  return {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: palette.panel,
      borderColor: palette.border,
      textStyle: { color: palette.text, fontSize: 12 },
      formatter: (params: { name: string; value: number; treePathInfo?: { name: string }[] }) => {
        const path = (params.treePathInfo ?? []).map((p) => p.name).filter(Boolean).join(' › ');
        const share = totalHeapBytes > 0 ? (100 * params.value) / totalHeapBytes : 0;
        return `<div style="font-size:11px;color:${palette.muted}">${path || params.name}</div>`
          + `<b>${formatBytes(params.value)}</b> retained · ${share.toFixed(1)} % кучи`;
      },
    },
    series: [
      {
        type: 'sunburst',
        data,
        radius: ['12%', '96%'],
        center: ['50%', '50%'],
        nodeClick: 'rootToNode',
        emphasis: { focus: 'ancestor' },
        itemStyle: { borderColor: palette.bg, borderWidth: 1.5, borderRadius: 2 },
        label: { color: palette.text, fontSize: 10, textBorderColor: palette.bg, textBorderWidth: 2 },
        levels: [
          {},
          { r0: '12%', r: '34%', label: { rotate: 'tangential', fontSize: 11, fontWeight: 600 }, itemStyle: { borderWidth: 2 } },
          { r0: '34%', r: '62%', label: { rotate: 'radial', minAngle: 7, overflow: 'truncate', width: 110 } },
          { r0: '62%', r: '78%', label: { show: false } },
          { r0: '78%', r: '87%', label: { show: false } },
          { r0: '87%', r: '92%', label: { show: false } },
          { r0: '92%', r: '96%', label: { show: false } },
        ],
      },
    ],
  };
}

function ClassCell({ row, expanded, onToggle }: { row: Row; expanded: boolean; onToggle(): void }) {
  const n = row.node.node;
  const [pkg, simple] = splitClassName(n.className);
  return (
    <span className="flex min-w-0 items-center gap-1.5" style={{ paddingLeft: row.level * 16 }}>
      {row.expandable ? (
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            onToggle();
          }}
          className="shrink-0 text-kraken-muted hover:text-accent-cyan"
          aria-label={expanded ? 'Свернуть' : 'Развернуть'}
        >
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </button>
      ) : (
        <span className="w-3.5 shrink-0" />
      )}
      <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: GROUP_COLOR[n.memoryGroup] }} />
      <span className="truncate font-mono text-xs" title={n.className}>
        <span className="text-kraken-subtle">{pkg}</span>
        <span className="text-kraken-text">{simple}</span>
        <span className="ml-1.5 text-kraken-subtle">{formatObjectId(n.objectId)}</span>
      </span>
      {row.node.children.length > 0 && (
        <span className="shrink-0 text-2xs text-kraken-subtle">· {row.node.children.length}</span>
      )}
    </span>
  );
}

function PathModal({ node, heapBytes, onClose }: { node: DominatorNodeDto | null; heapBytes: number; onClose(): void }) {
  const rootType = node ? classifyRoot(node.gcRootPath) : 'NONE';
  return (
    <Modal
      open={node != null}
      onClose={onClose}
      width="max-w-3xl"
      icon={<Route size={16} className="text-accent-cyan" />}
      title={node ? `Путь до GC Root: ${splitClassName(node.className)[1]} ${formatObjectId(node.objectId)}` : ''}
      footer={node?.gcRootPathText ? <CopyButton text={node.gcRootPathText} label="Копировать путь текстом" /> : undefined}
    >
      {node && (
        <div className="space-y-4">
          <div className="grid grid-cols-4 gap-4 rounded-md border border-kraken-border bg-kraken-card px-4 py-3">
            <div>
              <div className="text-2xs uppercase tracking-wider text-kraken-subtle">Retained</div>
              <div className="font-mono text-lg text-accent-amber">{formatBytes(node.retainedSize)}</div>
            </div>
            <div>
              <div className="text-2xs uppercase tracking-wider text-kraken-subtle">% кучи</div>
              <div className="font-mono text-lg">{formatPercent(heapBytes > 0 ? (100 * node.retainedSize) / heapBytes : node.percentOfHeap)}</div>
            </div>
            <div>
              <div className="text-2xs uppercase tracking-wider text-kraken-subtle">Shallow</div>
              <div className="font-mono text-lg">{formatBytes(node.shallowSize)}</div>
            </div>
            <div>
              <div className="text-2xs uppercase tracking-wider text-kraken-subtle">Корень</div>
              <div className="pt-1"><Badge color={ROOT_TYPE_COLOR[rootType]}>{ROOT_TYPE_LABEL[rootType]}</Badge></div>
            </div>
          </div>
          <GcRootPathGraph path={node.gcRootPath} fallbackText={node.gcRootPathText} targetLabel="Доминатор" />
        </div>
      )}
    </Modal>
  );
}

/**
 * dotMemory-style view of who holds the heap: a sunburst of retained bytes by subsystem, class and dominator object,
 * and the dominators as a virtualized tree table. Clicking a sector zooms into it and filters the table.
 */
export function DominatorTreeView() {
  const { choices, chosen } = useChosenDeepSnapshot();
  const selectSnapshot = useInspectorStore((s) => s.selectSnapshot);
  const { data, loading, error } = useDeepAnalysis(chosen);
  const [filter, setFilter] = useState<SunburstFilter | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'retained', desc: true });
  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query);
  const [pathNode, setPathNode] = useState<DominatorNodeDto | null>(null);
  const scroller = useRef<HTMLDivElement>(null);

  const heapBytes = chosen?.totalHeapBytes ?? 0;
  const roots = useMemo(() => buildDominatorTree(data?.dominators ?? []), [data]);
  const byId = useMemo(() => {
    const map = new Map<number, DomNode>();
    walk(roots, (n) => map.set(n.node.objectId, n));
    return map;
  }, [roots]);
  const option = useMemo(() => sunburstOption(sunburstData(roots, heapBytes), heapBytes), [roots, heapBytes]);
  const shares = useMemo(() => groupShares(roots), [roots]);
  const covered = shares.reduce((sum, s) => sum + s.retained, 0);

  const rows = useMemo<Row[]>(() => {
    const direction = sort.desc ? -1 : 1;
    const ordered = (list: readonly DomNode[]) => [...list].sort((a, b) => direction * compare(sort.key, a, b));
    const needle = deferredQuery.trim().toLowerCase();
    // Search and class / group filters list matching dominators at any depth.
    if (needle !== '' || filter?.type === 'class') {
      const flat: DomNode[] = [];
      walk(roots, (n) => {
        if ((needle === '' || n.node.className.toLowerCase().includes(needle)) && (!filter || matches(filter, n.node))) {
          flat.push(n);
        }
      });
      return ordered(flat).map((node) => ({ node, level: 0, expandable: false }));
    }
    let top: readonly DomNode[] = roots;
    if (filter?.type === 'group') {
      top = holdersOf(roots).map((h) => h.node).filter((n) => matches(filter, n.node));
    } else if (filter?.type === 'object') {
      const n = byId.get(filter.objectId);
      top = n ? [n] : [];
    }
    const out: Row[] = [];
    const add = (list: readonly DomNode[], level: number) => {
      for (const n of ordered(list)) {
        out.push({ node: n, level, expandable: n.children.length > 0 });
        const open = expanded.has(n.node.objectId) || (filter?.type === 'object' && level === 0);
        if (open) {
          add(n.children, level + 1);
        }
      }
    };
    add(top, 0);
    return out;
  }, [roots, byId, filter, expanded, sort, deferredQuery]);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  });

  const onChartClick = (event: EChartEvent) => {
    const datum = event.data as SunburstDatum | undefined;
    // A click on the center goes back up one level: ECharts reports the parent node then, or nothing at the top.
    setFilter(datum?.filter ?? null);
    scroller.current?.scrollTo({ top: 0 });
  };

  const toggle = (id: number) => setExpanded((current) => {
    const next = new Set(current);
    if (!next.delete(id)) {
      next.add(id);
    }
    return next;
  });

  if (choices.length === 0 || !chosen) {
    return (
      <EmptyState icon={<Network size={22} />} title="Dominator Tree & Sunburst">
        Нужен хотя бы один Deep Heap Dump: retained size, дерево доминаторов и пути до GC Root считаются при его анализе.
        Quick Histogram даёт только shallow size по классам.
      </EmptyState>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-kraken-border px-4 py-2.5">
        <h1 className="flex items-center gap-2 text-sm font-semibold">
          <Network size={16} className="text-accent-cyan" />
          Dominator Tree & Sunburst
        </h1>
        <SnapshotSelect label="Снапшот" value={chosen.snapshotId} options={choices} onChange={(id) => {
          setFilter(null);
          setExpanded(new Set());
          selectSnapshot(id);
        }} />
        <span className="ml-auto text-2xs text-kraken-subtle">
          Куча {formatBytes(heapBytes)} · {formatNumber(data?.dominators.length ?? 0)} крупнейших объектов держат{' '}
          {formatBytes(covered)} ({formatPercent(heapBytes > 0 ? (100 * covered) / heapBytes : null)})
        </span>
      </div>

      {loading ? (
        <div className="flex flex-1 items-center justify-center gap-2 text-xs text-kraken-muted">
          <Loader2 size={14} className="animate-spin" /> Загружаем дерево доминаторов…
        </div>
      ) : error ? (
        <EmptyState icon={<AlertTriangle size={22} />} title="Не удалось загрузить доминаторы">{error}</EmptyState>
      ) : !chosen.retainedSizesAvailable || roots.length === 0 ? (
        <EmptyState icon={<Network size={22} />} title="Нет данных о retained size">
          Для этого снапшота retained size не посчитан (анализ остановлен по времени или куча пуста).
        </EmptyState>
      ) : (
        <>
          <div className="flex h-[46%] min-h-[300px] shrink-0 border-b border-kraken-border">
            <EChart option={option} onEvents={{ click: onChartClick }} className="h-full min-w-0 flex-1" />
            <aside className="w-80 shrink-0 space-y-3 overflow-y-auto border-l border-kraken-border p-4">
              <div className="text-2xs font-semibold uppercase tracking-wider text-kraken-muted">Кто держит память</div>
              {shares.map((share) => {
                const percent = heapBytes > 0 ? (100 * share.retained) / heapBytes : 0;
                const active = filter?.type === 'group' && filter.group === share.group;
                return (
                  <button
                    key={share.group}
                    type="button"
                    onClick={() => setFilter(active ? null : { type: 'group', group: share.group })}
                    className={clsx(
                      'block w-full rounded-md border px-3 py-2 text-left transition-colors',
                      active ? 'border-accent-cyan bg-accent-cyan/10' : 'border-kraken-border hover:bg-kraken-card',
                    )}
                  >
                    <span className="flex items-center gap-2 text-xs">
                      <span className="h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: GROUP_COLOR[share.group] }} />
                      {GROUP_LABEL[share.group]}
                      <span className="ml-auto font-mono">{formatBytes(share.retained)}</span>
                    </span>
                    <span className="mt-1.5 block h-1 overflow-hidden rounded-full bg-kraken-border">
                      <span className="block h-full rounded-full" style={{ width: `${Math.max(1, percent)}%`, backgroundColor: GROUP_COLOR[share.group] }} />
                    </span>
                    <span className="mt-1 flex justify-between text-2xs text-kraken-subtle">
                      <span>{share.objects} объектов верхнего уровня</span>
                      <span className="font-mono">{formatPercent(percent)}</span>
                    </span>
                  </button>
                );
              })}
              <p className="text-2xs leading-relaxed text-kraken-subtle">
                Кольца от центра: подсистема → класс → объект-доминатор → вложенные доминаторы. Клик по сектору
                приближает его и фильтрует таблицу; клик по центру — на уровень выше.
              </p>
            </aside>
          </div>

          <div className="flex items-center gap-2 border-b border-kraken-border px-4 py-2">
            <span className="text-xs font-semibold">Доминаторы</span>
            {filter && (
              <span className="flex items-center gap-1 rounded-full border border-accent-cyan/60 bg-accent-cyan/10 px-2 py-0.5 text-2xs text-accent-cyan">
                {filterLabel(filter, (id) => byId.get(id))}
                <button type="button" onClick={() => setFilter(null)} aria-label="Сбросить фильтр">
                  <X size={11} />
                </button>
              </span>
            )}
            <button
              type="button"
              onClick={() => {
                const all = new Set<number>();
                walk(roots, (n) => n.children.length > 0 && all.add(n.node.objectId));
                setExpanded((current) => (current.size > 0 ? new Set() : all));
              }}
              className="h-6 rounded border border-kraken-border px-2 text-2xs text-kraken-muted hover:text-kraken-text"
            >
              {expanded.size > 0 ? 'Свернуть всё' : 'Развернуть всё'}
            </button>
            <label className="ml-auto flex h-7 w-72 items-center gap-2 rounded-md border border-kraken-border bg-kraken-bg px-2 focus-within:border-accent-cyan/70">
              <Search size={13} className="text-kraken-subtle" />
              <input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Поиск по классу…"
                className="w-full bg-transparent text-xs text-kraken-text outline-none placeholder:text-kraken-subtle"
              />
              {query && (
                <button type="button" onClick={() => setQuery('')} className="text-kraken-subtle hover:text-kraken-text">
                  <X size={12} />
                </button>
              )}
            </label>
          </div>

          <div className={clsx(GRID, 'border-b border-kraken-border bg-kraken-panel px-4 py-2 text-2xs font-semibold uppercase tracking-wider text-kraken-muted')}>
            {([
              ['name', 'Object / Class', 'left'],
              [null, 'Held by', 'left'],
              ['shallow', 'Shallow Size', 'right'],
              ['retained', 'Retained Size', 'right'],
              ['retained', '% of Heap', 'left'],
              [null, 'GC Root', 'left'],
              [null, '', 'left'],
            ] as const).map(([key, label, align], index) => (
              key ? (
                <button
                  key={index}
                  type="button"
                  onClick={() => setSort((current) => ({ key, desc: current.key === key ? !current.desc : key !== 'name' }))}
                  className={clsx('flex items-center gap-1 uppercase hover:text-kraken-text', align === 'right' && 'justify-end',
                    sort.key === key && 'text-accent-cyan')}
                >
                  {label}
                  {sort.key === key && (sort.desc ? <ArrowDown size={11} /> : <ArrowUp size={11} />)}
                </button>
              ) : (
                <span key={index}>{label}</span>
              )
            ))}
          </div>

          <div ref={scroller} className="min-h-[160px] flex-1 overflow-y-auto">
            {rows.length === 0 ? (
              <div className="flex h-full items-center justify-center text-xs text-kraken-subtle">Нет доминаторов под этот фильтр.</div>
            ) : (
              <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
                {virtualizer.getVirtualItems().map((item) => {
                  const row = rows[item.index];
                  if (!row) {
                    return null;
                  }
                  const n = row.node.node;
                  const percent = heapBytes > 0 ? (100 * n.retainedSize) / heapBytes : n.percentOfHeap;
                  const rootType = classifyRoot(n.gcRootPath);
                  return (
                    <div
                      key={`${n.objectId}-${row.level}`}
                      role="row"
                      onClick={() => row.expandable && toggle(n.objectId)}
                      className={clsx(
                        GRID,
                        'absolute left-0 top-0 w-full border-b border-kraken-border/50 px-4 hover:bg-kraken-card',
                        row.expandable && 'cursor-pointer',
                        item.index % 2 === 1 && 'bg-kraken-panel/40',
                      )}
                      style={{ height: ROW_HEIGHT, transform: `translateY(${item.start}px)` }}
                    >
                      <ClassCell row={row} expanded={expanded.has(n.objectId) || (filter?.type === 'object' && row.level === 0)} onToggle={() => toggle(n.objectId)} />
                      <span className="flex min-w-0 items-center gap-1.5" title={`Держит: ${ownerClassOf(n)}`}>
                        <Badge color={GROUP_COLOR[ownerGroupOf(n)]}>{GROUP_LABEL[ownerGroupOf(n)]}</Badge>
                        <span className="truncate font-mono text-2xs text-kraken-muted">{splitClassName(ownerClassOf(n))[1]}</span>
                      </span>
                      <span className="text-right font-mono text-xs tabular-nums text-kraken-muted">{formatBytes(n.shallowSize)}</span>
                      <span className="text-right font-mono text-xs tabular-nums">{formatBytes(n.retainedSize)}</span>
                      <span className="flex items-center gap-2">
                        <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-kraken-border">
                          <span
                            className="block h-full rounded-full"
                            style={{ width: `${Math.min(100, Math.max(1, percent))}%`, backgroundColor: percent >= 20 ? palette.coral : percent >= 5 ? palette.amber : palette.cyan }}
                          />
                        </span>
                        <span className="w-12 text-right font-mono text-2xs tabular-nums">{formatPercent(percent)}</span>
                      </span>
                      <span className="truncate"><Badge color={ROOT_TYPE_COLOR[rootType]}>{ROOT_TYPE_LABEL[rootType]}</Badge></span>
                      <span>
                        <button
                          type="button"
                          onClick={(event) => {
                            event.stopPropagation();
                            setPathNode(n);
                          }}
                          className="inline-flex h-6 items-center gap-1 rounded border border-kraken-border px-2 text-2xs text-kraken-muted hover:border-accent-cyan/60 hover:text-accent-cyan"
                        >
                          <Route size={11} />
                          Показать путь до GC Root
                        </button>
                      </span>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
          <div className="border-t border-kraken-border px-4 py-1.5 text-2xs text-kraken-subtle">
            Показано {formatNumber(rows.length)} строк · вложенность по путям до GC Root: доминатор объекта всегда лежит на
            каждом пути к нему
          </div>
        </>
      )}
      <PathModal node={pathNode} heapBytes={heapBytes} onClose={() => setPathNode(null)} />
    </div>
  );
}
