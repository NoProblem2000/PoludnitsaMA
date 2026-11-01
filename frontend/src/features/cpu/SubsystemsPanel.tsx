import type { SubsystemBreakdownDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { withAlpha } from '@/theme/palette';
import { formatNumber } from '@/utils/format';
import { formatMs, formatValue, SUBSYSTEM_COLOR, SUBSYSTEM_LABEL } from './profileFormat';

interface SubsystemsPanelProps {
  breakdown: SubsystemBreakdownDto;
  onOpenMethod(className: string, methodName: string): void;
}

/** Where the window's time goes: business code vs JDBC / Hibernate / HTTP / I/O / locks / GC, with the precise events. */
export function SubsystemsPanel({ breakdown, onOpenMethod }: SubsystemsPanelProps) {
  const { unit, subsystems } = breakdown;
  return (
    <div className="space-y-3">
      <div className="rounded-md border border-kraken-border bg-kraken-panel p-3">
        <div className="mb-2 flex items-center text-xs font-semibold text-kraken-text">
          Распределение {breakdown.metric === 'ALLOC' ? 'аллокаций' : 'времени'} по подсистемам
          <span className="ml-auto font-mono text-2xs font-normal text-kraken-muted">
            всего {formatValue(breakdown.totalValue, unit)}
          </span>
        </div>
        {/* one stacked bar */}
        <div className="mb-3 flex h-5 overflow-hidden rounded">
          {subsystems.map((s) => (
            <div
              key={s.subsystem}
              title={`${SUBSYSTEM_LABEL[s.subsystem]}: ${s.percent.toFixed(1)} %`}
              style={{ width: `${s.percent}%`, backgroundColor: SUBSYSTEM_COLOR[s.subsystem] }}
            />
          ))}
        </div>
        <div className="grid gap-2 lg:grid-cols-2">
          {subsystems.map((s) => (
            <div key={s.subsystem} className="rounded border border-kraken-border bg-kraken-bg p-2">
              <div className="flex items-center gap-2">
                <span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: SUBSYSTEM_COLOR[s.subsystem] }} />
                <span className="text-xs font-medium text-kraken-text">{s.label || SUBSYSTEM_LABEL[s.subsystem]}</span>
                <span className="ml-auto font-mono text-xs" style={{ color: SUBSYSTEM_COLOR[s.subsystem] }}>
                  {s.percent.toFixed(1)} %
                </span>
                <span className="w-20 text-right font-mono text-2xs text-kraken-muted">{formatValue(s.value, unit)}</span>
              </div>
              <div className="mt-1 h-1.5 overflow-hidden rounded bg-kraken-card">
                <div className="h-full" style={{ width: `${s.percent}%`, backgroundColor: withAlpha(SUBSYSTEM_COLOR[s.subsystem], 0.8) }} />
              </div>
              {s.topMethods.length > 0 && (
                <ul className="mt-1.5 space-y-0.5">
                  {s.topMethods.map((m) => (
                    <li key={`${m.className}.${m.methodName}`} className="flex items-center gap-2 font-mono text-2xs">
                      <button
                        type="button"
                        onClick={() => onOpenMethod(m.className, m.methodName)}
                        className="min-w-0 flex-1 truncate text-left text-kraken-muted hover:text-accent-cyan"
                        title={`${m.className}.${m.methodName} — открыть в Butterfly`}
                      >
                        {m.className}.<span className="text-kraken-text">{m.methodName}</span>
                      </button>
                      <span className="text-kraken-subtle">{formatValue(m.selfValue, unit)}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
          {subsystems.length === 0 && <div className="text-2xs text-kraken-subtle">Нет данных в окне</div>}
        </div>
      </div>

      <div className="grid gap-3 lg:grid-cols-3">
        <div className="rounded-md border border-kraken-border bg-kraken-panel lg:col-span-2">
          <div className="border-b border-kraken-border px-3 py-2 text-xs font-semibold text-kraken-text">
            Tracing: события с точной длительностью (JFR)
          </div>
          <table className="w-full text-2xs">
            <thead className="text-left text-kraken-subtle">
              <tr>
                <th className="px-3 py-1.5 font-medium">Событие</th>
                <th className="px-2 py-1.5 text-right font-medium">Кол-во</th>
                <th className="px-2 py-1.5 text-right font-medium">Сумма</th>
                <th className="px-2 py-1.5 text-right font-medium">Макс.</th>
                <th className="px-3 py-1.5 font-medium">Чаще всего</th>
              </tr>
            </thead>
            <tbody>
              {breakdown.traced.map((t) => (
                <tr key={t.type} className="border-t border-kraken-border/40">
                  <td className="px-3 py-1">
                    <div className="text-kraken-text">{t.label}</div>
                    <div className="font-mono text-kraken-subtle">{t.type}</div>
                  </td>
                  <td className="px-2 py-1 text-right font-mono text-kraken-text">{formatNumber(t.count)}</td>
                  <td className="px-2 py-1 text-right font-mono text-kraken-text">{formatMs(t.totalMs)}</td>
                  <td className="px-2 py-1 text-right font-mono text-kraken-muted">{formatMs(t.maxMs)}</td>
                  <td className="max-w-[260px] truncate px-3 py-1 font-mono text-kraken-muted" title={t.topDetail ?? ''}>
                    {t.topDetail ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {breakdown.traced.length === 0 && (
            <div className="p-3 text-2xs text-kraken-subtle">Нет событий длиннее порога (10 мс, 1 мс в режиме Tracing)</div>
          )}
        </div>
        <div className="rounded-md border border-kraken-border bg-kraken-panel">
          <div className="border-b border-kraken-border px-3 py-2 text-xs font-semibold text-kraken-text">Исключения</div>
          <ul className="max-h-72 overflow-y-auto">
            {breakdown.exceptions.map((e) => (
              <li key={e.className} className="flex items-center gap-2 border-t border-kraken-border/40 px-3 py-1 text-2xs first:border-t-0">
                <span className="min-w-0 flex-1 truncate font-mono text-kraken-text" title={e.className}>{e.className}</span>
                <Badge color="#ff3d71">{formatNumber(e.count)}</Badge>
              </li>
            ))}
          </ul>
          {breakdown.exceptions.length === 0 && (
            <div className="p-3 text-2xs text-kraken-subtle">
              Нет. Все исключения (jdk.JavaExceptionThrow) пишутся только в режиме Tracing; ошибки — всегда.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
