import clsx from 'clsx';
import { Cpu } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { ProfileMetric, ProfileReportDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { EmptyState } from '@/components/common/EmptyState';
import { palette } from '@/theme/palette';
import { formatDateTime, formatDuration, formatNumber } from '@/utils/format';
import { FlameGraphView, fromFlame } from './FlameGraphView';
import { METRIC_HINT, METRIC_LABEL, SUBSYSTEM_COLOR, frameLabel } from './profileFormat';
import { SubsystemsPanel } from './SubsystemsPanel';

/**
 * CPU &amp; Timeline profiles of an offline report: the whole-profile flame graph, hotspots and subsystems per metric
 * as exported (no raw samples, so no time-window cross-filtering).
 */
export function OfflineProfilesView({ profiles }: { profiles: ProfileReportDto[] }) {
  const [selected, setSelected] = useState(0);
  const [metric, setMetric] = useState<ProfileMetric | null>(null);
  const profile = profiles[Math.min(selected, profiles.length - 1)];
  const metricReport = profile?.metrics.find((m) => m.metric === metric) ?? profile?.metrics[0];
  const root = useMemo(() => (metricReport?.flame ? fromFlame(metricReport.flame.root) : null), [metricReport]);

  if (!profile) {
    return (
      <EmptyState icon={<Cpu size={22} />} title="В отчёте нет CPU-профилей">
        Профили попадают в отчёт, если перед экспортом был записан хотя бы один профиль (JFR или async-profiler).
      </EmptyState>
    );
  }
  const s = profile.summary;
  return (
    <div className="space-y-3 p-4">
      <div className="flex gap-2 overflow-x-auto pb-1">
        {profiles.map((p, index) => (
          <button
            key={p.summary.profileId}
            type="button"
            onClick={() => { setSelected(index); setMetric(null); }}
            className={clsx(
              'min-w-56 rounded-md border px-3 py-2 text-left text-xs',
              index === selected ? 'border-accent-cyan bg-kraken-card' : 'border-kraken-border bg-kraken-panel hover:border-accent-cyan/50',
            )}
          >
            <div className="flex items-center gap-2">
              <Badge color={palette.violet}>{p.summary.source}</Badge>
              <span className="font-mono text-kraken-text">{p.summary.profileId}</span>
            </div>
            <div className="mt-1 text-2xs text-kraken-muted">
              {formatDateTime(p.summary.startedAt)} · {formatDuration(p.summary.recordedMs)} · {formatNumber(p.summary.sampleCount)} сэмплов
            </div>
          </button>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-2 rounded-md border border-kraken-border bg-kraken-panel px-3 py-2 text-xs">
        <span className="text-kraken-text">{s.displayName}</span>
        <span className="font-mono text-kraken-subtle">PID {s.pid}</span>
        <span className="text-kraken-subtle">· {s.threadCount} потоков{s.tracing ? ' · tracing' : ''}</span>
        <span className="ml-auto flex gap-1">
          {profile.metrics.map((m) => (
            <button
              key={m.metric}
              type="button"
              title={METRIC_HINT[m.metric]}
              onClick={() => setMetric(m.metric)}
              className={clsx(
                'rounded-md border px-2 py-1 text-2xs font-semibold',
                m.metric === metricReport?.metric ? 'border-accent-cyan text-accent-cyan' : 'border-kraken-border text-kraken-muted',
              )}
            >
              {METRIC_LABEL[m.metric]}
            </button>
          ))}
        </span>
      </div>

      {metricReport?.flame && root && (
        <div className="rounded-md border border-kraken-border bg-kraken-panel p-3">
          <FlameGraphView root={root} unit={metricReport.flame.unit} />
        </div>
      )}
      <div className="grid gap-3 xl:grid-cols-2">
        <div className="overflow-hidden rounded-md border border-kraken-border bg-kraken-panel">
          <div className="border-b border-kraken-border px-3 py-2 text-xs font-semibold">Hotspots (self)</div>
          <table className="w-full text-xs">
            <thead className="text-2xs uppercase text-kraken-subtle">
              <tr><th className="px-3 py-1.5 text-left">Метод</th><th className="px-2 text-right">Self %</th><th className="px-3 text-right">Total %</th></tr>
            </thead>
            <tbody>
              {metricReport?.hotspots.slice(0, 40).map((h) => (
                <tr key={`${h.className}.${h.methodName}`} className="border-t border-kraken-border/60">
                  <td className="px-3 py-1 font-mono">
                    <span className="mr-2 inline-block h-2 w-2 rounded-sm" style={{ backgroundColor: SUBSYSTEM_COLOR[h.subsystem] }} />
                    {frameLabel(h.className, h.methodName)}
                  </td>
                  <td className="px-2 text-right font-mono">{h.selfPercent.toFixed(1)}</td>
                  <td className="px-3 text-right font-mono text-kraken-muted">{h.totalPercent.toFixed(1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {metricReport?.subsystems && <SubsystemsPanel breakdown={metricReport.subsystems} onOpenMethod={() => undefined} />}
      </div>
    </div>
  );
}
