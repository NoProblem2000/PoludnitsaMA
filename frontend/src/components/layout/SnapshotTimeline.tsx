import clsx from 'clsx';
import { BarChart3, GitCompare, Loader2, Microscope, ShieldAlert, XCircle } from 'lucide-react';
import { useEffect, useRef } from 'react';
import type { SnapshotSummaryDto } from '@/api/types';
import { useComparePair, useInspectorStore } from '@/store/useInspectorStore';
import { formatBytes, formatSignedBytes, formatTime } from '@/utils/format';

const PHASE: Record<SnapshotSummaryDto['status'], string> = {
  DUMPING: 'Запись дампа',
  INDEXING: 'Индексация',
  ANALYZING: 'Анализ',
  READY: 'Готов',
  FAILED: 'Ошибка',
};

interface CardProps {
  snapshot: SnapshotSummaryDto;
  index: number;
  previous: SnapshotSummaryDto | undefined;
  selected: boolean;
  compared: boolean;
  findings: number | null;
  onSelect(): void;
  onCompare(): void;
}

function SnapshotCard({ snapshot, index, previous, selected, compared, findings, onSelect, onCompare }: CardProps) {
  const running = snapshot.status !== 'READY' && snapshot.status !== 'FAILED';
  const failed = snapshot.status === 'FAILED';
  const delta = previous && previous.status === 'READY' && snapshot.status === 'READY'
    ? snapshot.totalHeapBytes - previous.totalHeapBytes
    : null;
  const Icon = snapshot.kind === 'DEEP' ? Microscope : BarChart3;

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          onSelect();
        }
      }}
      className={clsx(
        'group relative flex w-56 shrink-0 cursor-pointer flex-col justify-between rounded-md border bg-kraken-card px-3 py-2',
        'transition-colors hover:border-accent-cyan/50',
        compared ? 'border-accent-violet shadow-[0_0_0_1px_#b388ff55]'
          : selected ? 'border-accent-cyan shadow-glow-cyan' : 'border-kraken-border',
        failed && 'border-accent-coral/60',
      )}
      title={failed ? snapshot.errorMessage ?? 'Ошибка' : snapshot.progressMessage}
    >
      <div className="flex items-center gap-1.5">
        <Icon size={14} className={snapshot.kind === 'DEEP' ? 'text-accent-cyan' : 'text-kraken-muted'} />
        <span className="text-xs font-semibold">Snapshot #{index + 1}</span>
        <span className="font-mono text-2xs text-kraken-muted">{formatTime(snapshot.createdAt)}</span>
        {snapshot.imported && <span className="text-2xs text-accent-violet">imported</span>}
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation();
            onCompare();
          }}
          disabled={snapshot.status !== 'READY'}
          title={compared ? 'Убрать из сравнения' : 'Выбрать для сравнения'}
          className={clsx(
            'ml-auto rounded p-0.5 disabled:opacity-30',
            compared ? 'text-accent-violet' : 'text-kraken-subtle opacity-0 group-hover:opacity-100 hover:text-accent-violet',
          )}
        >
          <GitCompare size={13} />
        </button>
      </div>

      {running ? (
        <div className="space-y-1">
          <div className="flex items-center gap-1.5 text-2xs text-accent-amber">
            <Loader2 size={11} className="animate-spin" />
            <span className="truncate">{PHASE[snapshot.status]}: {snapshot.progressMessage}</span>
          </div>
          <div className="h-1 overflow-hidden rounded-full bg-kraken-border">
            <div className="h-full bg-accent-amber transition-[width]" style={{ width: `${Math.max(4, snapshot.progressPercent)}%` }} />
          </div>
        </div>
      ) : failed ? (
        <div className="flex items-center gap-1.5 text-2xs text-accent-coral">
          <XCircle size={12} />
          <span className="truncate">{snapshot.errorMessage ?? 'Снапшот не удался'}</span>
        </div>
      ) : (
        <div className="flex items-baseline gap-2">
          <span className="font-mono text-sm">{formatBytes(snapshot.totalHeapBytes)}</span>
          {delta != null && (
            <span className={clsx('font-mono text-2xs', delta > 0 ? 'text-accent-amber' : 'text-accent-emerald')}>
              {formatSignedBytes(delta)}
            </span>
          )}
          <span className="ml-auto text-2xs uppercase tracking-wide text-kraken-subtle">
            {snapshot.kind === 'DEEP' ? (snapshot.liveObjectsOnly ? 'exact' : 'safe') : 'histogram'}
          </span>
          {findings != null && findings > 0 && (
            <span className="flex items-center gap-0.5 text-2xs text-accent-coral" title="Диагнозы">
              <ShieldAlert size={11} />
              {findings}
            </span>
          )}
        </div>
      )}
    </div>
  );
}

/** dotMemory-style strip of snapshots: click to select, the compare toggle picks two for a diff. */
export function SnapshotTimeline() {
  const snapshots = useInspectorStore((s) => s.snapshots);
  const selectedId = useInspectorStore((s) => s.selectedSnapshotId);
  const compareIds = useInspectorStore((s) => s.compareIds);
  const findings = useInspectorStore((s) => s.findings);
  const pair = useComparePair();
  const selectSnapshot = useInspectorStore((s) => s.selectSnapshot);
  const toggleCompare = useInspectorStore((s) => s.toggleCompare);
  const setView = useInspectorStore((s) => s.setView);
  const strip = useRef<HTMLDivElement>(null);

  // Keep the newest snapshot in view.
  useEffect(() => {
    strip.current?.scrollTo({ left: strip.current.scrollWidth, behavior: 'smooth' });
  }, [snapshots.length]);

  const newestDeep = [...snapshots].reverse().find((s) => s.kind === 'DEEP' && s.status === 'READY');
  const number = (id: string) => snapshots.findIndex((s) => s.snapshotId === id) + 1;

  return (
    <footer className="flex h-24 shrink-0 items-stretch gap-3 border-t border-kraken-border bg-kraken-panel px-3 py-2">
      <div className="flex w-36 shrink-0 flex-col justify-center gap-1">
        <span className="text-2xs font-semibold uppercase tracking-wider text-kraken-muted">Снапшоты</span>
        <span className="text-xs text-kraken-subtle">{snapshots.length === 0 ? 'пока нет' : `${snapshots.length} шт.`}</span>
        {pair ? (
          <button
            type="button"
            onClick={() => setView('diff')}
            className="flex items-center gap-1 rounded border border-accent-violet/60 px-1.5 py-0.5 text-2xs text-accent-violet hover:bg-accent-violet/10"
          >
            <GitCompare size={11} />
            Сравнить #{number(pair[0].snapshotId)} → #{number(pair[1].snapshotId)}
          </button>
        ) : (
          compareIds.length === 1 && <span className="text-2xs text-accent-violet">выберите второй</span>
        )}
      </div>
      <div ref={strip} className="flex flex-1 items-stretch gap-2 overflow-x-auto pb-1">
        {snapshots.length === 0 && (
          <div className="flex items-center text-xs text-kraken-subtle">
            Quick Histogram или Deep Heap Dump появятся здесь. Отметьте два снапшота значком сравнения, чтобы
            увидеть diff.
          </div>
        )}
        {snapshots.map((snapshot, index) => (
          <SnapshotCard
            key={snapshot.snapshotId}
            snapshot={snapshot}
            index={index}
            previous={index > 0 ? snapshots[index - 1] : undefined}
            selected={snapshot.snapshotId === selectedId}
            compared={compareIds.includes(snapshot.snapshotId)}
            findings={newestDeep?.snapshotId === snapshot.snapshotId ? findings.length : null}
            onSelect={() => selectSnapshot(snapshot.snapshotId)}
            onCompare={() => toggleCompare(snapshot.snapshotId)}
          />
        ))}
      </div>
    </footer>
  );
}
