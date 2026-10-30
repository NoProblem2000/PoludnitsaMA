import clsx from 'clsx';
import { useInspectorStore } from '@/store/useInspectorStore';
import { formatBytes, formatDuration, formatNumber, formatPercent, formatRate } from '@/utils/format';

type Tone = 'healthy' | 'suspect' | 'critical' | 'info' | 'meta';

const TONE: Record<Tone, string> = {
  healthy: 'border-t-accent-emerald',
  suspect: 'border-t-accent-amber',
  critical: 'border-t-accent-coral',
  info: 'border-t-accent-cyan',
  meta: 'border-t-accent-violet',
};

function Card({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone: Tone }) {
  return (
    <div className={clsx('rounded-md border border-t-2 border-kraken-border bg-kraken-card px-3 py-2', TONE[tone])}>
      <div className="text-2xs uppercase tracking-wider text-kraken-muted">{label}</div>
      <div className="mt-0.5 font-mono text-lg tabular-nums text-kraken-text">{value}</div>
      {sub && <div className="truncate text-2xs text-kraken-subtle">{sub}</div>}
    </div>
  );
}

/** Headline numbers of the last sample (live) or of the last history point (offline). */
export function StatCards() {
  const latest = useInspectorStore((s) => s.latest);
  const last = useInspectorStore((s) => s.points[s.points.length - 1]);

  const heapUsed = latest?.heap.used ?? last?.heapUsed ?? -1;
  const heapMax = latest ? (latest.heap.max > 0 ? latest.heap.max : latest.heap.committed) : last?.heapMax ?? -1;
  const heapShare = heapMax > 0 && heapUsed >= 0 ? (100 * heapUsed) / heapMax : null;
  const oldAfterGc = last?.oldAfterGc ?? null;
  const metaspace = last?.metaspace ?? null;
  const overhead = latest?.gcOverheadPercent ?? last?.gcOverheadPercent ?? null;
  const collections = latest?.gcCollectionsPerMin ?? null;
  const allocation = latest?.allocationRateBytesPerSec ?? last?.allocationRate ?? null;
  const histogramMs = latest?.histogramDurationMs ?? null;
  const intervalMs = latest?.histogramIntervalMs ?? null;

  return (
    <div className="grid grid-cols-2 gap-2 md:grid-cols-4 2xl:grid-cols-7">
      <Card
        label="Heap used"
        value={formatBytes(heapUsed)}
        sub={heapShare != null ? `${heapShare.toFixed(0)} % от ${formatBytes(heapMax)}` : undefined}
        tone={heapShare == null ? 'info' : heapShare >= 90 ? 'critical' : heapShare >= 75 ? 'suspect' : 'healthy'}
      />
      <Card label="Old Gen после GC" value={formatBytes(oldAfterGc)} sub="живой набор: рост = утечка" tone="suspect" />
      <Card label="Metaspace" value={formatBytes(metaspace)} sub="классы; растёт при утечке ClassLoader" tone="meta" />
      <Card
        label="GC overhead (1 мин)"
        value={formatPercent(overhead)}
        sub="доля времени в STW-паузах"
        tone={overhead == null ? 'info' : overhead >= 10 ? 'critical' : overhead >= 3 ? 'suspect' : 'healthy'}
      />
      <Card label="Сборок в минуту" value={formatNumber(collections)} sub="stop-the-world" tone="info" />
      <Card label="Allocation rate" value={formatRate(allocation)} sub="TotalThreadAllocatedBytes" tone="info" />
      <Card
        label="Leak Radar"
        value={intervalMs != null ? `каждые ${formatDuration(intervalMs)}` : '—'}
        sub={histogramMs != null ? `пауза гистограммы ${formatDuration(histogramMs)}` : 'GC.class_histogram -all'}
        tone={histogramMs != null && histogramMs > 500 ? 'suspect' : 'meta'}
      />
    </div>
  );
}
