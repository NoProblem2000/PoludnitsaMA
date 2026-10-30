import type { SnapshotSummaryDto } from '@/api/types';
import { useInspectorStore } from '@/store/useInspectorStore';
import { formatBytes, formatTime } from '@/utils/format';

/** Number shown on the timeline cards: position among all snapshots, 1-based. */
export function useSnapshotNumber(): (id: string) => number {
  const snapshots = useInspectorStore((s) => s.snapshots);
  return (id) => snapshots.findIndex((s) => s.snapshotId === id) + 1;
}

export function snapshotKindLabel(snapshot: SnapshotSummaryDto): string {
  if (snapshot.kind === 'LIVE_HISTOGRAM') {
    return 'histogram';
  }
  return snapshot.liveObjectsOnly ? 'deep exact' : 'deep safe';
}

interface SnapshotSelectProps {
  label: string;
  value: string | null;
  options: SnapshotSummaryDto[];
  onChange(id: string): void;
  disabledId?: string | null;
}

/** Dropdown of snapshots labelled like the timeline cards. */
export function SnapshotSelect({ label, value, options, onChange, disabledId }: SnapshotSelectProps) {
  const number = useSnapshotNumber();
  return (
    <label className="flex items-center gap-2 text-xs text-kraken-muted">
      <span className="whitespace-nowrap">{label}</span>
      <select
        value={value ?? ''}
        onChange={(event) => onChange(event.target.value)}
        className="h-8 min-w-64 rounded-md border border-kraken-border bg-kraken-bg px-2 font-mono text-xs text-kraken-text outline-none focus:border-accent-cyan/70"
      >
        {value == null && <option value="">—</option>}
        {options.map((snapshot) => (
          <option key={snapshot.snapshotId} value={snapshot.snapshotId} disabled={snapshot.snapshotId === disabledId}>
            #{number(snapshot.snapshotId)} · {formatTime(snapshot.createdAt)} · {snapshotKindLabel(snapshot)} ·{' '}
            {formatBytes(snapshot.totalHeapBytes)}
          </option>
        ))}
      </select>
    </label>
  );
}
