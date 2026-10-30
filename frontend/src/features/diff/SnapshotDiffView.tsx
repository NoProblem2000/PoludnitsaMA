import { useVirtualizer } from '@tanstack/react-virtual';
import clsx from 'clsx';
import { AlertTriangle, ArrowDown, ArrowRight, ArrowUp, GitCompare, Loader2, Search, Sparkles, X } from 'lucide-react';
import { type ReactNode, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { ClassDiffDto, MemoryGroup, SnapshotDiffDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { EmptyState } from '@/components/common/EmptyState';
import { ScoreBar } from '@/components/common/ScoreBar';
import { SnapshotSelect, useSnapshotNumber } from '@/components/common/SnapshotSelect';
import { useComparePair, useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { GROUP_COLOR, GROUP_LABEL } from '@/utils/categories';
import { formatBytes, formatNumber, formatSignedBytes, formatTime, splitClassName } from '@/utils/format';
import { computeLocalDiff } from './localDiff';

type ChangeFilter = 'all' | 'grown' | 'freed' | 'new';
type GroupFilter = 'all' | MemoryGroup;
type SortKey = 'className' | 'group' | 'instances' | 'deltaInstances' | 'deltaShallow' | 'deltaRetained' | 'score';

const GRID = 'grid grid-cols-[minmax(300px,1fr)_96px_150px_128px_120px_150px_150px] items-center gap-3';
const ROW_HEIGHT = 34;
/** survival score from which growth is shown as a likely leak (coral) instead of plain growth (amber) */
const LEAK_SCORE = 50;

/** Retained delta when both snapshots have retained sizes, else shallow. */
function deltaBytes(row: ClassDiffDto): number {
  return row.deltaRetainedBytes ?? row.deltaShallowBytes;
}

const CHANGE_FILTERS: { id: ChangeFilter; label: string; test(row: ClassDiffDto): boolean }[] = [
  { id: 'all', label: 'Все изменения', test: () => true },
  { id: 'grown', label: 'Выросли +Δ', test: (row) => deltaBytes(row) > 0 || row.deltaInstances > 0 },
  { id: 'freed', label: 'Освобождены −Δ', test: (row) => deltaBytes(row) < 0 },
  { id: 'new', label: 'Новые классы', test: (row) => row.newClass },
];

const GROUP_FILTERS: GroupFilter[] = ['all', 'USER_BUSINESS_CODE', 'JAKARTA_WILDFLY_INTERNAL', 'HAZELCAST_CLIENT', 'JDK_CORE'];

function compare(key: SortKey, a: ClassDiffDto, b: ClassDiffDto): number {
  switch (key) {
    case 'className':
      return a.className.localeCompare(b.className);
    case 'group':
      return GROUP_LABEL[a.memoryGroup].localeCompare(GROUP_LABEL[b.memoryGroup]);
    case 'instances':
      return a.instancesAfter - b.instancesAfter;
    case 'deltaInstances':
      return a.deltaInstances - b.deltaInstances;
    case 'deltaShallow':
      return a.deltaShallowBytes - b.deltaShallowBytes;
    case 'deltaRetained':
      return deltaBytes(a) - deltaBytes(b) || a.deltaInstances - b.deltaInstances;
    case 'score':
      return a.survivalScore - b.survivalScore || deltaBytes(a) - deltaBytes(b);
  }
}

/** +Δ in coral (likely leak) or amber (growth), −Δ in emerald (freed). */
function DeltaBadge({ value, score, bytes = true }: { value: number; score: number; bytes?: boolean }) {
  if (value === 0) {
    return <span className="font-mono text-2xs text-kraken-subtle">±0</span>;
  }
  const grew = value > 0;
  const color = !grew ? palette.emerald : score >= LEAK_SCORE ? palette.coral : palette.amber;
  const text = bytes ? formatBytes(Math.abs(value)) : formatNumber(Math.abs(value));
  return (
    <span
      className="inline-flex items-center rounded px-1.5 py-px font-mono text-2xs font-semibold tabular-nums"
      style={{ color, backgroundColor: `${color}1f`, border: `1px solid ${color}73` }}
    >
      {grew ? '+Δ' : '−Δ'} {text}
    </span>
  );
}

function Stat({ label, children, tone }: { label: string; children: ReactNode; tone?: string }) {
  return (
    <div className="rounded-md border border-kraken-border bg-kraken-card px-3 py-2">
      <div className="text-2xs uppercase tracking-wider text-kraken-subtle">{label}</div>
      <div className="mt-0.5 font-mono text-sm" style={{ color: tone }}>{children}</div>
    </div>
  );
}

function useDiff(baselineId: string | null, currentId: string | null): { diff: SnapshotDiffDto | null; loading: boolean; error: string | null } {
  const report = useInspectorStore((s) => s.offlineReport);
  const [state, setState] = useState<{ diff: SnapshotDiffDto | null; loading: boolean; error: string | null }>(
    { diff: null, loading: false, error: null });

  const local = useMemo(() => {
    if (!report || !baselineId || !currentId) {
      return null;
    }
    const a = report.snapshots.find((s) => s.summary.snapshotId === baselineId);
    const b = report.snapshots.find((s) => s.summary.snapshotId === currentId);
    return a?.data && b?.data ? computeLocalDiff(a, b) : null;
  }, [report, baselineId, currentId]);

  useEffect(() => {
    if (report || !baselineId || !currentId || baselineId === currentId) {
      return undefined;
    }
    let current = true;
    setState({ diff: null, loading: true, error: null });
    api.snapshots.diff(baselineId, currentId).then(
      (diff) => current && setState({ diff, loading: false, error: null }),
      (error: unknown) => current && setState({ diff: null, loading: false, error: errorMessage(error) }),
    );
    return () => {
      current = false;
    };
  }, [report, baselineId, currentId]);

  if (report) {
    return { diff: local, loading: false, error: local || !baselineId ? null : 'В отчёте нет классов этих снапшотов' };
  }
  return state;
}

/**
 * Two snapshots class by class: what grew since the baseline (objects created after it and still held) and what was
 * freed. Ranked by retained growth; the survival score marks growth that looks like a leak.
 */
export function SnapshotDiffView() {
  const snapshots = useInspectorStore((s) => s.snapshots);
  const compareIds = useInspectorStore((s) => s.compareIds);
  const pair = useComparePair();
  const setCompareIds = useInspectorStore((s) => s.setCompareIds);
  const number = useSnapshotNumber();
  const ready = useMemo(() => snapshots.filter((s) => s.status === 'READY'), [snapshots]);

  // Without a picked pair, compare the two newest ready snapshots.
  useEffect(() => {
    if (pair || ready.length < 2) {
      return;
    }
    const kept = compareIds.find((id) => ready.some((s) => s.snapshotId === id));
    const newestOther = [...ready].reverse().find((s) => s.snapshotId !== kept)?.snapshotId;
    setCompareIds(kept && newestOther ? [kept, newestOther] : ready.slice(-2).map((s) => s.snapshotId));
  }, [pair, ready, compareIds, setCompareIds]);

  const baselineId = pair?.[0].snapshotId ?? null;
  const currentId = pair?.[1].snapshotId ?? null;
  const { diff, loading, error } = useDiff(baselineId, currentId);

  const [change, setChange] = useState<ChangeFilter>('all');
  const [group, setGroup] = useState<GroupFilter>('all');
  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query);
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'deltaRetained', desc: true });
  const scroller = useRef<HTMLDivElement>(null);

  const classes = useMemo(() => diff?.classes ?? [], [diff]);
  const counts = useMemo(() => {
    const result = {} as Record<ChangeFilter, number>;
    for (const item of CHANGE_FILTERS) {
      result[item.id] = classes.filter(item.test).length;
    }
    return result;
  }, [classes]);
  const rows = useMemo(() => {
    const test = CHANGE_FILTERS.find((item) => item.id === change)?.test ?? (() => true);
    const needle = deferredQuery.trim().toLowerCase();
    const direction = sort.desc ? -1 : 1;
    return classes
      .filter((row) => test(row) && (group === 'all' || row.memoryGroup === group)
        && (needle === '' || row.className.toLowerCase().includes(needle)))
      .sort((a, b) => direction * compare(sort.key, a, b) || a.className.localeCompare(b.className));
  }, [classes, change, group, deferredQuery, sort]);

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  });

  if (ready.length < 2) {
    return (
      <EmptyState icon={<GitCompare size={22} />} title="Snapshot Comparison">
        Нужно хотя бы два готовых снапшота одного процесса: Quick Histogram (быстро, shallow size) или Deep Heap Dump
        (retained size). Снимите один, дайте сервису поработать под нагрузкой и снимите второй.
      </EmptyState>
    );
  }

  const retained = diff?.retainedDeltaAvailable ?? false;
  const notes: string[] = [];
  if (diff && !diff.samePid) {
    notes.push('Снапшоты разных процессов: дельта показывает разницу сервисов, а не рост.');
  }
  if (diff && !retained) {
    notes.push('Хотя бы один снапшот — гистограмма: сравниваются shallow size и число объектов, retained недоступен.');
  } else if (diff && !(diff.baseline.liveObjectsOnly && diff.current.liveObjectsOnly)) {
    notes.push('Safe-дамп содержит и недостижимые объекты: часть дельты — мусор, который уйдёт при следующем GC. '
      + 'Для точного diff снимайте оба дампа в режиме Exact.');
  }
  const columns: { key: SortKey; label: string; align: 'left' | 'right' }[] = [
    { key: 'className', label: 'Class Name', align: 'left' },
    { key: 'group', label: 'Category', align: 'left' },
    { key: 'instances', label: 'Instances #1 → #2', align: 'right' },
    { key: 'deltaInstances', label: '+New Instances', align: 'right' },
    { key: 'deltaShallow', label: 'Δ Shallow', align: 'right' },
    { key: 'deltaRetained', label: retained ? '+New Retained Bytes' : '+New Bytes (shallow)', align: 'right' },
    { key: 'score', label: 'Survival', align: 'left' },
  ];

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-3 border-b border-kraken-border px-4 py-2.5">
        <h1 className="flex items-center gap-2 text-sm font-semibold">
          <GitCompare size={16} className="text-accent-violet" />
          Snapshot Comparison
        </h1>
        <SnapshotSelect
          label="Baseline Snapshot (#1)"
          value={baselineId}
          options={ready}
          disabledId={currentId}
          onChange={(id) => setCompareIds([id, currentId ?? id])}
        />
        <ArrowRight size={14} className="text-kraken-subtle" />
        <SnapshotSelect
          label="Target Snapshot (#2)"
          value={currentId}
          options={ready}
          disabledId={baselineId}
          onChange={(id) => setCompareIds([baselineId ?? id, id])}
        />
        <span className="text-2xs text-kraken-subtle">старший снапшот всегда baseline</span>
      </div>

      {diff && (
        <div className="space-y-2 border-b border-kraken-border px-4 py-3">
          <div className="grid grid-cols-6 gap-2">
            <Stat label={`Куча #${number(diff.baseline.snapshotId)} → #${number(diff.current.snapshotId)}`} tone={diff.deltaTotalBytes > 0 ? palette.amber : palette.emerald}>
              {formatSignedBytes(diff.deltaTotalBytes)}
            </Stat>
            <Stat label="Δ объектов">{diff.deltaTotalInstances > 0 ? '+' : ''}{formatNumber(diff.deltaTotalInstances)}</Stat>
            <Stat label="Выросли классов" tone={palette.amber}>{formatNumber(diff.growingClasses)}</Stat>
            <Stat label="Новых классов" tone={palette.violet}>{formatNumber(diff.newClasses)}</Stat>
            <Stat label="Исчезли классов" tone={palette.emerald}>{formatNumber(diff.removedClasses)}</Stat>
            <Stat label="Между снапшотами">
              {formatTime(diff.baseline.createdAt)} → {formatTime(diff.current.createdAt)}
            </Stat>
          </div>
          {notes.length > 0 && (
            <div className="flex items-start gap-2 rounded border border-accent-amber/40 bg-accent-amber/10 px-3 py-1.5 text-2xs text-accent-amber">
              <AlertTriangle size={12} className="mt-0.5 shrink-0" />
              <span>{notes.join(' ')}</span>
            </div>
          )}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-2 border-b border-kraken-border px-4 py-2">
        {CHANGE_FILTERS.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => setChange(item.id)}
            className={clsx(
              'h-7 rounded-full border px-3 text-xs transition-colors',
              change === item.id
                ? 'border-accent-cyan bg-accent-cyan/15 text-accent-cyan'
                : 'border-kraken-border text-kraken-muted hover:border-accent-cyan/50 hover:text-kraken-text',
            )}
          >
            {item.label}
            <span className="ml-1.5 font-mono text-2xs opacity-70">{formatNumber(counts[item.id])}</span>
          </button>
        ))}
        <span className="mx-1 h-5 w-px bg-kraken-border" />
        {GROUP_FILTERS.map((value) => (
          <button
            key={value}
            type="button"
            onClick={() => setGroup(value)}
            className={clsx(
              'flex h-7 items-center gap-1.5 rounded-full border px-2.5 text-xs transition-colors',
              group === value ? 'border-accent-violet bg-accent-violet/15 text-accent-violet'
                : 'border-kraken-border text-kraken-muted hover:text-kraken-text',
            )}
          >
            {value !== 'all' && <span className="h-2 w-2 rounded-full" style={{ backgroundColor: GROUP_COLOR[value] }} />}
            {value === 'all' ? 'Все группы' : GROUP_LABEL[value]}
          </button>
        ))}
        <label className="ml-auto flex h-7 w-64 items-center gap-2 rounded-md border border-kraken-border bg-kraken-bg px-2 focus-within:border-accent-cyan/70">
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
        {columns.map((column) => (
          <button
            key={column.key}
            type="button"
            onClick={() => setSort((current) => ({ key: column.key, desc: current.key === column.key ? !current.desc : column.key !== 'className' }))}
            className={clsx('flex items-center gap-1 uppercase hover:text-kraken-text', column.align === 'right' && 'justify-end',
              sort.key === column.key && 'text-accent-cyan')}
          >
            {column.label}
            {sort.key === column.key && (sort.desc ? <ArrowDown size={11} /> : <ArrowUp size={11} />)}
          </button>
        ))}
      </div>

      <div ref={scroller} className="min-h-[160px] flex-1 overflow-y-auto">
        {loading ? (
          <div className="flex h-full items-center justify-center gap-2 text-xs text-kraken-muted">
            <Loader2 size={14} className="animate-spin" /> Сравниваем снапшоты…
          </div>
        ) : error ? (
          <div className="flex h-full items-center justify-center text-xs text-accent-coral">{error}</div>
        ) : rows.length === 0 ? (
          <div className="flex h-full items-center justify-center text-xs text-kraken-subtle">
            {classes.length === 0 ? 'Между снапшотами нет изменений.' : 'Нет классов под этот фильтр.'}
          </div>
        ) : (
          <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
            {virtualizer.getVirtualItems().map((item) => {
              const row = rows[item.index];
              if (!row) {
                return null;
              }
              const [pkg, simple] = splitClassName(row.className);
              return (
                <div
                  key={row.className}
                  role="row"
                  className={clsx(GRID, 'absolute left-0 top-0 w-full border-b border-kraken-border/50 px-4 hover:bg-kraken-card',
                    item.index % 2 === 1 && 'bg-kraken-panel/40')}
                  style={{ height: ROW_HEIGHT, transform: `translateY(${item.start}px)` }}
                >
                  <span className="flex min-w-0 items-center gap-2" title={row.className}>
                    <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: GROUP_COLOR[row.memoryGroup] }} />
                    <span className="truncate font-mono text-xs">
                      <span className="text-kraken-subtle">{pkg}</span>
                      <span className="text-kraken-text">{simple}</span>
                    </span>
                    {row.newClass && (
                      <span title="Класса не было в baseline"><Sparkles size={12} className="shrink-0 text-accent-violet" /></span>
                    )}
                  </span>
                  <span><Badge color={GROUP_COLOR[row.memoryGroup]}>{GROUP_LABEL[row.memoryGroup]}</Badge></span>
                  <span className="text-right font-mono text-2xs tabular-nums text-kraken-muted">
                    {formatNumber(row.instancesBefore)} → <span className="text-kraken-text">{formatNumber(row.instancesAfter)}</span>
                  </span>
                  <span className="text-right"><DeltaBadge value={row.deltaInstances} score={row.survivalScore} bytes={false} /></span>
                  <span className="text-right font-mono text-2xs tabular-nums" style={{ color: row.deltaShallowBytes > 0 ? palette.amber : row.deltaShallowBytes < 0 ? palette.emerald : palette.subtle }}>
                    {formatSignedBytes(row.deltaShallowBytes)}
                  </span>
                  <span className="text-right"><DeltaBadge value={deltaBytes(row)} score={row.survivalScore} /></span>
                  <ScoreBar score={row.survivalScore} />
                </div>
              );
            })}
          </div>
        )}
      </div>
      <div className="flex gap-4 border-t border-kraken-border px-4 py-1.5 text-2xs text-kraken-subtle">
        <span>Показано {formatNumber(rows.length)} из {formatNumber(classes.length)} изменившихся классов</span>
        <span>
          <span className="text-accent-coral">+Δ</span> вероятная утечка (survival ≥ {LEAK_SCORE}) ·{' '}
          <span className="text-accent-amber">+Δ</span> рост · <span className="text-accent-emerald">−Δ</span> освобождено
        </span>
      </div>
    </div>
  );
}
