import { Lock } from 'lucide-react';
import type { MonitorContentionDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { palette } from '@/theme/palette';
import { formatNumber } from '@/utils/format';
import { shortFrame } from './threadFormat';

/**
 * Monitor Contention Hotspots: lock classes and the lines that wait for them, by blocked time; WildFly / Undertow
 * worker threads are counted separately.
 */
export function ContentionTable({ rows, title = 'Monitor Contention Hotspots' }: { rows: MonitorContentionDto[]; title?: string }) {
  const max = Math.max(1, ...rows.map((r) => r.blockedTimeMs));
  return (
    <section className="overflow-hidden rounded-md border border-kraken-border bg-kraken-panel">
      <header className="flex items-center gap-2 border-b border-kraken-border px-3 py-2 text-xs font-semibold">
        <Lock size={14} className="text-accent-amber" />
        {title}
        {rows.length > 0 && !rows[0]?.timeMeasured && (
          <span className="font-normal text-kraken-subtle">· время оценено по сэмплам (≈), contention monitoring недоступен</span>
        )}
      </header>
      {rows.length === 0 ? (
        <p className="px-3 py-4 text-xs text-kraken-muted">Блокировок на мониторах не замечено.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="text-2xs uppercase tracking-wide text-kraken-subtle">
              <tr>
                <th className="px-3 py-1.5 text-left">Lock (класс)</th>
                <th className="px-2 text-left">Где ждут</th>
                <th className="w-48 px-2 text-left">Blocked time</th>
                <th className="px-2 text-right">Раз</th>
                <th className="px-2 text-right">Потоков</th>
                <th className="px-3 text-left">Держатель</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.lockClass}|${row.blockedAt}|${row.lockKind}`} className="border-t border-kraken-border/60 align-top">
                  <td className="px-3 py-1.5">
                    <div className="font-mono text-kraken-text">{row.lockClass}</div>
                    {row.lockKind && <Badge color={row.lockKind === 'MONITOR' ? palette.amber : palette.violet}>{row.lockKind}</Badge>}
                  </td>
                  <td className="px-2 py-1.5 font-mono text-kraken-muted" title={row.applicationFrame ?? row.blockedAt}>
                    {shortFrame(row.applicationFrame ?? row.blockedAt)}
                    {row.applicationFrame && row.applicationFrame !== row.blockedAt && (
                      <div className="text-2xs text-kraken-subtle">в {shortFrame(row.blockedAt)}</div>
                    )}
                  </td>
                  <td className="px-2 py-1.5">
                    <div className="flex items-center gap-2">
                      <span className="h-1.5 flex-1 overflow-hidden rounded bg-kraken-card">
                        <span className="block h-full bg-accent-coral" style={{ width: `${(row.blockedTimeMs / max) * 100}%` }} />
                      </span>
                      <span className="w-20 text-right font-mono">{row.timeMeasured ? '' : '≈'}{formatNumber(Math.round(row.blockedTimeMs))} ms</span>
                    </div>
                  </td>
                  <td className="px-2 py-1.5 text-right font-mono">{formatNumber(row.blockedCount)}</td>
                  <td className="px-2 py-1.5 text-right font-mono" title={row.topThreads.join('\n')}>
                    {row.threads}
                    {row.serverThreads > 0 && <div className="text-2xs text-accent-cyan">{row.serverThreads} worker</div>}
                  </td>
                  <td className="px-3 py-1.5">
                    <div className="text-kraken-text">{row.topOwners.join(', ') || '—'}</div>
                    {row.ownerFrame && <div className="font-mono text-2xs text-kraken-subtle">{shortFrame(row.ownerFrame)}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
