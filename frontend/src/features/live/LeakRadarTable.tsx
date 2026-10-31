import { useVirtualizer } from '@tanstack/react-virtual';
import clsx from 'clsx';
import { ArrowDown, ArrowUp, Flame, Search, X } from 'lucide-react';
import { useDeferredValue, useMemo, useRef, useState } from 'react';
import type { MemoryGroup } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { ScoreBar } from '@/components/common/ScoreBar';
import { buildRadarRows, type RadarRow } from '@/store/radarRows';
import { useInspectorStore } from '@/store/useInspectorStore';
import { AllocationStacksPanel } from './AllocationStacksPanel';
import { palette } from '@/theme/palette';
import { GROUP_COLOR, GROUP_LABEL } from '@/utils/categories';
import { formatBytes, formatGrowthRate, formatNumber, formatTime, splitClassName } from '@/utils/format';

type FilterId = 'all' | 'growing' | 'mine' | 'wildfly' | 'hazelcast';
type SortKey = 'className' | 'group' | 'instances' | 'shallowBytes' | 'growth' | 'score';

const FILTERS: { id: FilterId; label: string; test(row: RadarRow): boolean }[] = [
  { id: 'all', label: 'Все', test: () => true },
  { id: 'growing', label: 'Только растущие 📈', test: (row) => (row.growthBytesPerMin ?? 0) > 0 },
  { id: 'mine', label: 'Мой бизнес-код', test: (row) => row.group === 'USER_BUSINESS_CODE' },
  { id: 'wildfly', label: 'Jakarta / WildFly', test: (row) => row.group === 'JAKARTA_WILDFLY_INTERNAL' },
  { id: 'hazelcast', label: 'Hazelcast Client', test: (row) => row.group === 'HAZELCAST_CLIENT' },
];

const COLUMNS: { key: SortKey; label: string; align: 'left' | 'right' }[] = [
  { key: 'className', label: 'Class Name', align: 'left' },
  { key: 'group', label: 'Category', align: 'left' },
  { key: 'instances', label: 'Instances', align: 'right' },
  { key: 'shallowBytes', label: 'Shallow Size', align: 'right' },
  { key: 'growth', label: 'Growth Rate', align: 'right' },
  { key: 'score', label: 'Leak Probability', align: 'left' },
];

const GRID = 'grid grid-cols-[minmax(320px,1fr)_104px_112px_112px_136px_180px] items-center gap-3';
const ROW_HEIGHT = 34;

function compare(key: SortKey, a: RadarRow, b: RadarRow): number {
  switch (key) {
    case 'className':
      return a.className.localeCompare(b.className);
    case 'group':
      return GROUP_LABEL[a.group].localeCompare(GROUP_LABEL[b.group]);
    case 'instances':
      return a.instances - b.instances;
    case 'shallowBytes':
      return a.shallowBytes - b.shallowBytes;
    case 'growth':
      return (a.growthBytesPerMin ?? Number.NEGATIVE_INFINITY) - (b.growthBytesPerMin ?? Number.NEGATIVE_INFINITY);
    case 'score':
      return a.score - b.score || (a.growthBytesPerMin ?? 0) - (b.growthBytesPerMin ?? 0) || a.shallowBytes - b.shallowBytes;
  }
}

function ClassName({ name, group }: { name: string; group: MemoryGroup }) {
  const [pkg, simple] = splitClassName(name);
  return (
    <span className="flex min-w-0 items-center gap-2" title={name}>
      <span className="h-2 w-2 shrink-0 rounded-full" style={{ backgroundColor: GROUP_COLOR[group] }} />
      <span className="truncate font-mono text-xs">
        <span className="text-kraken-subtle">{pkg}</span>
        <span className="text-kraken-text">{simple}</span>
      </span>
    </span>
  );
}

function Growth({ bytesPerMin }: { bytesPerMin: number | null }) {
  const color = bytesPerMin == null || bytesPerMin === 0 ? palette.subtle : bytesPerMin > 0 ? palette.amber : palette.emerald;
  return (
    <span className="font-mono text-xs tabular-nums" style={{ color }}>
      {formatGrowthRate(bytesPerMin)}
    </span>
  );
}

function Details({ row, onClose }: { row: RadarRow; onClose(): void }) {
  const live = useInspectorStore((s) => s.mode === 'live');
  return (
    <div className="border-t border-kraken-border bg-kraken-panel px-4 py-3">
      <div className="flex items-center gap-2">
        <ClassName name={row.className} group={row.group} />
        {row.leakCandidate && <Badge color={palette.coral}>leak candidate</Badge>}
        {row.definitionCount > 1 && (
          <Badge color={palette.amber} title="Один класс загружен несколькими ClassLoader'ами: возможна утечка после redeploy">
            ×{row.definitionCount} ClassLoader
          </Badge>
        )}
        <button type="button" onClick={onClose} className="ml-auto text-kraken-subtle hover:text-kraken-text">
          <X size={14} />
        </button>
      </div>
      <div className="mt-2 flex flex-wrap gap-x-8 gap-y-1 text-xs text-kraken-muted">
        <span>
          Выживание после old-GC подряд:{' '}
          <b className="text-kraken-text">{row.oldGcSurvivalStreak ?? '—'}</b>
        </span>
        <span>
          Рост экземпляров: <b className="text-kraken-text">{row.growthInstancesPerMin == null ? '—' : `${row.growthInstancesPerMin.toFixed(1)}/мин`}</b>
        </span>
        {row.windows.map((window) => (
          <span key={window.windowMinutes}>
            {window.windowMinutes} мин ({window.samples} post-GC):{' '}
            <b className="text-kraken-text">{window.samples >= 3 ? formatGrowthRate(window.bytesSlopePerMin) : 'мало данных'}</b>
            {window.samples >= 3 && `, монотонность ${(window.monotonicity * 100).toFixed(0)} %, R² ${window.rSquared.toFixed(2)}`}
          </span>
        ))}
      </div>
      {live && <AllocationStacksPanel className={row.className} />}
    </div>
  );
}

/**
 * Live Leak Radar: every class of the latest histogram merged with the radar's growth trends, virtualized so tens
 * of thousands of rows scroll without lag.
 */
export function LeakRadarTable() {
  const mode = useInspectorStore((s) => s.mode);
  const latest = useInspectorStore((s) => s.latest);
  const classes = useInspectorStore((s) => s.radarClasses);
  const classesSnapshotId = useInspectorStore((s) => s.radarClassesSnapshotId);
  const snapshots = useInspectorStore((s) => s.snapshots);
  const report = useInspectorStore((s) => s.offlineReport);
  const [filter, setFilter] = useState<FilterId>('all');
  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query);
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'score', desc: true });
  const [selected, setSelected] = useState<string | null>(null);
  const scroller = useRef<HTMLDivElement>(null);

  const suspects = mode === 'live' ? latest?.topGrowingClasses ?? [] : [];
  const diff = mode === 'offline' ? report?.diffs[0] ?? null : null;
  const rows = useMemo(() => buildRadarRows({ suspects, classes, diff }), [suspects, classes, diff]);

  const counts = useMemo(() => {
    const result = {} as Record<FilterId, number>;
    for (const item of FILTERS) {
      result[item.id] = rows.filter(item.test).length;
    }
    return result;
  }, [rows]);

  const visible = useMemo(() => {
    const test = FILTERS.find((item) => item.id === filter)?.test ?? (() => true);
    const needle = deferredQuery.trim().toLowerCase();
    const filtered = rows.filter((row) => test(row) && (needle === '' || row.className.toLowerCase().includes(needle)));
    const direction = sort.desc ? -1 : 1;
    return filtered.sort((a, b) => direction * compare(sort.key, a, b));
  }, [rows, filter, deferredQuery, sort]);

  const virtualizer = useVirtualizer({
    count: visible.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  });

  const selectedRow = selected ? rows.find((row) => row.className === selected) : undefined;
  const histogram = snapshots.find((snapshot) => snapshot.snapshotId === classesSnapshotId);
  const candidates = rows.filter((row) => row.leakCandidate).length;

  return (
    <div className="flex flex-col">
      <div className="flex flex-wrap items-center gap-2 border-b border-kraken-border px-4 py-3">
        <h2 className="mr-2 flex items-center gap-2 text-sm font-semibold">
          <Flame size={16} className="text-accent-coral" />
          Live Leak Radar
          {candidates > 0 && <Badge color={palette.coral} solid>{candidates} кандидат(ов)</Badge>}
        </h2>
        {FILTERS.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => setFilter(item.id)}
            className={clsx(
              'h-7 rounded-full border px-3 text-xs transition-colors',
              filter === item.id
                ? 'border-accent-cyan bg-accent-cyan/15 text-accent-cyan'
                : 'border-kraken-border text-kraken-muted hover:border-accent-cyan/50 hover:text-kraken-text',
            )}
          >
            {item.label}
            <span className="ml-1.5 font-mono text-2xs opacity-70">{formatNumber(counts[item.id])}</span>
          </button>
        ))}
        <label className="ml-auto flex h-7 w-72 items-center gap-2 rounded-md border border-kraken-border bg-kraken-bg px-2 focus-within:border-accent-cyan/70">
          <Search size={13} className="text-kraken-subtle" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Поиск по имени класса…"
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
        {COLUMNS.map((column) => (
          <button
            key={column.key}
            type="button"
            onClick={() => setSort((current) => ({ key: column.key, desc: current.key === column.key ? !current.desc : true }))}
            className={clsx(
              'flex items-center gap-1 uppercase hover:text-kraken-text',
              column.align === 'right' && 'justify-end',
              sort.key === column.key && 'text-accent-cyan',
            )}
          >
            {column.label}
            {sort.key === column.key && (sort.desc ? <ArrowDown size={11} /> : <ArrowUp size={11} />)}
          </button>
        ))}
      </div>

      <div ref={scroller} className="h-[28rem] overflow-y-auto">
        {visible.length === 0 ? (
          <div className="flex h-full items-center justify-center px-8 text-center text-xs leading-relaxed text-kraken-subtle">
            {rows.length === 0
              ? mode === 'live'
                ? 'Leak Radar копит данные: тренды появляются после трёх гистограмм, снятых после сборок мусора (~30 с). Нажмите Quick Histogram, чтобы сразу увидеть все классы.'
                : 'В отчёте нет данных по классам.'
              : 'Нет классов под этот фильтр.'}
          </div>
        ) : (
          <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
            {virtualizer.getVirtualItems().map((item) => {
              const row = visible[item.index];
              if (!row) {
                return null;
              }
              return (
                <div
                  key={row.className}
                  role="row"
                  onClick={() => setSelected(row.className === selected ? null : row.className)}
                  className={clsx(
                    GRID,
                    'absolute left-0 top-0 w-full cursor-pointer border-b border-kraken-border/50 px-4',
                    row.className === selected ? 'bg-accent-cyan/10' : item.index % 2 === 1 ? 'bg-kraken-panel/40' : '',
                    'hover:bg-kraken-card',
                  )}
                  style={{ height: ROW_HEIGHT, transform: `translateY(${item.start}px)` }}
                >
                  <span className="flex min-w-0 items-center gap-1.5">
                    <ClassName name={row.className} group={row.group} />
                    {row.leakCandidate && <Flame size={12} className="shrink-0 text-accent-coral" />}
                  </span>
                  <span>
                    <Badge color={GROUP_COLOR[row.group]}>{GROUP_LABEL[row.group]}</Badge>
                  </span>
                  <span className="text-right font-mono text-xs tabular-nums">{formatNumber(row.instances)}</span>
                  <span className="text-right font-mono text-xs tabular-nums">{formatBytes(row.shallowBytes)}</span>
                  <span className="text-right">
                    <Growth bytesPerMin={row.growthBytesPerMin} />
                  </span>
                  <ScoreBar score={row.score} />
                </div>
              );
            })}
          </div>
        )}
      </div>

      {selectedRow && <Details row={selectedRow} onClose={() => setSelected(null)} />}

      <div className="flex items-center gap-4 border-t border-kraken-border px-4 py-2 text-2xs text-kraken-subtle">
        <span>
          Показано {formatNumber(visible.length)} из {formatNumber(rows.length)} классов
        </span>
        {mode === 'live' && <span>Leak Radar: {suspects.length} растущих классов</span>}
        {histogram && (
          <span>
            Все классы: {histogram.kind === 'DEEP' ? 'deep snapshot' : 'гистограмма'} {formatTime(histogram.createdAt)} (
            {formatNumber(classes.length)})
          </span>
        )}
        {diff && <span>Рост: diff {formatTime(diff.baseline.createdAt)} → {formatTime(diff.current.createdAt)}</span>}
      </div>
    </div>
  );
}
