import clsx from 'clsx';
import { AlertTriangle, Gauge, Loader2, Radio, Square, Timer, Trash2 } from 'lucide-react';
import { type ReactNode, useCallback, useEffect, useState } from 'react';
import { api } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { GcAnalysisDto, GcAnalysisSummaryDto } from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { EmptyState } from '@/components/common/EmptyState';
import { FileDropZone } from '@/components/common/FileDropZone';
import { ToolbarButton } from '@/components/common/ToolbarButton';
import { useNow } from '@/hooks/useNow';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { SEVERITY_COLOR } from '@/utils/categories';
import { formatDateTime, formatDuration, formatNumber, formatPercent } from '@/utils/format';
import { HeapChart, PauseScatter, RateChart } from './GcCharts';

const POLL_MS = 1000;
const LIVE_DURATIONS = [30, 60, 120, 300, 600];

/**
 * GC & Safepoints: Unified Logging gc.log files (G1, Parallel, ZGC, Shenandoah; plain or .gz), JFR files, or a live
 * JFR recording of the target started over FlightRecorderMXBean. KPIs, pause / heap / rate charts, causes with advice
 * and slowest safepoints (TTSP vs operation).
 */
export function GcLogAnalyzerView() {
  const mode = useInspectorStore((s) => s.mode);
  const offline = useInspectorStore((s) => s.offlineReport?.gcAnalyses);
  if (mode === 'offline') {
    return <OfflineGc analyses={offline ?? []} />;
  }
  return <LiveGc />;
}

function LiveGc() {
  const activePid = useInspectorStore((s) => s.activePid);
  const pushToast = useInspectorStore((s) => s.pushToast);
  const now = useNow(1000);
  const [list, setList] = useState<GcAnalysisSummaryDto[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<GcAnalysisDto | null>(null);
  const [duration, setDuration] = useState(60);
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setList(await api.gc.list());
    } catch {
      // the next poll retries
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const running = list.some((a) => a.status === 'RUNNING');
  useEffect(() => {
    if (!running) {
      return undefined;
    }
    const timer = window.setInterval(() => void refresh(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [running, refresh]);

  useEffect(() => {
    if (!selectedId || !list.some((a) => a.analysisId === selectedId)) {
      setSelectedId(list[0]?.analysisId ?? null);
    }
  }, [list, selectedId]);

  const selected = list.find((a) => a.analysisId === selectedId) ?? null;
  const readyId = selected?.status === 'READY' ? selected.analysisId : null;
  useEffect(() => {
    setAnalysis(null);
    if (!readyId) {
      return;
    }
    let cancelled = false;
    api.gc.analysis(readyId).then(
      (a) => !cancelled && setAnalysis(a),
      (error) => !cancelled && pushToast({ tone: 'error', title: 'Не удалось загрузить GC-анализ', message: errorMessage(error) }),
    );
    return () => {
      cancelled = true;
    };
  }, [readyId, pushToast]);

  const upload = async (file: File) => {
    setUploading(true);
    try {
      const summary = await api.gc.upload(file);
      await refresh();
      setSelectedId(summary.analysisId);
      pushToast({ tone: 'success', title: 'GC-лог разобран', message: `${summary.collector ?? 'GC'}: ${formatNumber(summary.pauseCount)} пауз` });
    } catch (error) {
      pushToast({ tone: 'error', title: 'Не удалось разобрать GC-лог', message: errorMessage(error) });
    } finally {
      setUploading(false);
    }
  };

  const startLive = async () => {
    if (activePid == null) {
      return;
    }
    setBusy(true);
    try {
      const started = await api.gc.startLive({ pid: activePid, durationSeconds: duration });
      await refresh();
      setSelectedId(started.analysisId);
      pushToast({ tone: 'info', title: 'Запись GC запущена', message: `JFR в PID ${activePid}, ${duration} с` });
    } catch (error) {
      pushToast({ tone: 'error', title: 'Запись GC не запущена', message: errorMessage(error) });
    } finally {
      setBusy(false);
    }
  };

  const stop = async (id: string) => {
    try {
      await api.gc.stop(id);
      await refresh();
    } catch (error) {
      pushToast({ tone: 'error', title: 'Не удалось остановить запись', message: errorMessage(error) });
    }
  };

  const remove = async (id: string) => {
    try {
      await api.gc.delete(id);
      await refresh();
    } catch (error) {
      pushToast({ tone: 'error', title: 'Не удалось удалить анализ', message: errorMessage(error) });
    }
  };

  const elapsed = selected?.status === 'RUNNING' ? now - new Date(selected.startedAt).getTime() : 0;

  return (
    <div className="space-y-3 p-4">
      <div className="grid gap-3 lg:grid-cols-[3fr_2fr]">
        <FileDropZone
          title="Перетащите gc.log (-Xlog:gc*,safepoint*) или .jfr"
          hint="Java 11/17/21 Unified Logging: G1, Parallel, Serial, ZGC, Shenandoah; можно .gz"
          accept=".log,.txt,.gz,.jfr,.current,text/plain"
          busy={uploading}
          onFile={(file) => void upload(file)}
        />
        <div className="flex flex-col justify-center gap-2 rounded-md border border-kraken-border bg-kraken-panel px-3 py-3 text-xs">
          <div className="flex items-center gap-2 font-semibold">
            <Radio size={14} className="text-accent-emerald" />
            Подключиться live (JFR через FlightRecorderMXBean)
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={duration}
              onChange={(e) => setDuration(Number(e.target.value))}
              className="h-8 rounded border border-kraken-border bg-kraken-bg px-2 text-xs"
            >
              {LIVE_DURATIONS.map((d) => <option key={d} value={d}>{formatDuration(d * 1000)}</option>)}
            </select>
            <ToolbarButton
              icon={<Radio size={14} />}
              label="Записать GC и safepoints"
              tone="primary"
              busy={busy}
              disabled={activePid == null || running}
              onClick={() => void startLive()}
            />
          </div>
          <div className="text-2xs text-kraken-subtle">
            {activePid == null ? 'Выберите процесс JVM.' : `PID ${activePid}: GarbageCollection, GCHeapSummary, Safepoint* события; ничего не логируется в файлы цели.`}
          </div>
        </div>
      </div>

      {list.length > 0 && (
        <div className="flex gap-2 overflow-x-auto pb-1">
          {list.map((a) => (
            <AnalysisCard key={a.analysisId} summary={a} active={a.analysisId === selectedId}
              onSelect={() => setSelectedId(a.analysisId)} onDelete={() => void remove(a.analysisId)} />
          ))}
        </div>
      )}

      {selected?.status === 'RUNNING' && (
        <div className="flex items-center gap-3 rounded-md border border-kraken-border bg-kraken-panel px-3 py-3 text-xs">
          <Loader2 size={14} className="animate-spin text-accent-cyan" />
          <span>{selected.progressMessage ?? 'Запись…'}</span>
          {selected.durationSeconds != null && (
            <span className="font-mono text-kraken-muted">{formatDuration(Math.max(0, elapsed))} / {formatDuration(selected.durationSeconds * 1000)}</span>
          )}
          <ToolbarButton icon={<Square size={13} />} label="Остановить и разобрать" tone="danger" className="ml-auto"
            onClick={() => void stop(selected.analysisId)} />
        </div>
      )}
      {selected?.status === 'FAILED' && (
        <div className="rounded-md border border-accent-coral/60 bg-accent-coral/5 px-3 py-2 text-xs text-accent-coral">
          Анализ не удался: {selected.errorMessage}
        </div>
      )}
      {analysis ? <GcAnalysisPanel analysis={analysis} />
        : list.length === 0 && (
          <EmptyState icon={<Gauge size={22} />} title="Нет GC-анализов">
            Загрузите gc.log, записанный с <code>-Xlog:gc*,safepoint*:file=gc.log:time,uptime,level,tags</code>,
            или запишите GC выбранного процесса через JFR.
          </EmptyState>
        )}
    </div>
  );
}

function AnalysisCard({ summary, active, onSelect, onDelete }: {
  summary: GcAnalysisSummaryDto; active: boolean; onSelect(): void; onDelete?: () => void;
}) {
  return (
    <div className={clsx(
      'min-w-60 rounded-md border px-3 py-2 text-xs',
      active ? 'border-accent-cyan bg-kraken-card' : 'border-kraken-border bg-kraken-panel hover:border-accent-cyan/50',
    )}>
      <button type="button" onClick={onSelect} className="block w-full text-left">
        <div className="flex items-center gap-1.5">
          <Badge color={summary.source === 'LIVE' ? palette.cyan : palette.violet}>{summary.source === 'LIVE' ? `JFR PID ${summary.pid}` : 'файл'}</Badge>
          <Badge color={summary.status === 'READY' ? palette.emerald : summary.status === 'FAILED' ? palette.coral : palette.amber}>{summary.status}</Badge>
          {summary.collector && <span className="truncate text-2xs text-kraken-muted">{summary.collector}</span>}
        </div>
        <div className="mt-1 truncate text-kraken-text" title={summary.name}>{summary.name}</div>
        <div className="text-2xs text-kraken-muted">
          {formatNumber(summary.pauseCount)} пауз · {formatDuration(summary.coveredMs)} · {formatDateTime(summary.startedAt)}
        </div>
      </button>
      {onDelete && summary.status !== 'RUNNING' && (
        <button type="button" onClick={onDelete} title="Удалить анализ" className="mt-1 text-kraken-subtle hover:text-accent-coral">
          <Trash2 size={12} />
        </button>
      )}
    </div>
  );
}

function Kpi({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: string; tone?: string }) {
  return (
    <div className="rounded-md border border-kraken-border bg-kraken-panel px-3 py-2" title={hint}>
      <div className="text-2xs uppercase tracking-wide text-kraken-subtle">{label}</div>
      <div className="mt-0.5 font-mono text-lg font-semibold" style={{ color: tone ?? palette.text }}>{value}</div>
    </div>
  );
}

function Panel({ title, icon, children, extra }: { title: string; icon?: ReactNode; children: ReactNode; extra?: ReactNode }) {
  return (
    <section className="overflow-hidden rounded-md border border-kraken-border bg-kraken-panel">
      <header className="flex items-center gap-2 border-b border-kraken-border px-3 py-2 text-xs font-semibold">
        {icon}
        {title}
        {extra && <span className="ml-auto font-normal">{extra}</span>}
      </header>
      {children}
    </section>
  );
}

function ms(value: number | null | undefined): string {
  if (value == null) {
    return '—';
  }
  return value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${value < 10 ? value.toFixed(2) : value.toFixed(1)} ms`;
}

function rate(value: number | null): string {
  return value == null ? '—' : `${value < 10 ? value.toFixed(2) : value.toFixed(0)} MB/s`;
}

export function GcAnalysisPanel({ analysis }: { analysis: GcAnalysisDto }) {
  const { kpi, safepoints, summary } = analysis;
  const throughputTone = kpi.throughputPercent >= 98 ? palette.emerald : kpi.throughputPercent >= 95 ? palette.amber : palette.coral;
  const pauseTone = kpi.maxPauseMs >= 1000 ? palette.coral : kpi.maxPauseMs >= 200 ? palette.amber : palette.text;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs text-kraken-muted">
        <span className="text-kraken-text">{summary.collector ?? 'Коллектор не определён'}</span>
        {summary.jvmVersion && <span className="font-mono">{summary.jvmVersion}</span>}
        <span>· {formatDuration(summary.coveredMs)} лога</span>
        <span>· {formatNumber(analysis.parsedLines)} строк разобрано{analysis.unparsedLines > 0 && `, ${formatNumber(analysis.unparsedLines)} пропущено`}</span>
        {analysis.concurrentCycles > 0 && <span>· {formatNumber(analysis.concurrentCycles)} concurrent-циклов</span>}
        {analysis.truncated && <Badge color={palette.amber}>обрезано по лимиту событий</Badge>}
      </div>

      <div className="grid grid-cols-2 gap-2 md:grid-cols-4 xl:grid-cols-8">
        <Kpi label="Throughput" value={formatPercent(kpi.throughputPercent, 2)} tone={throughputTone} hint="Доля времени вне STW-пауз GC" />
        <Kpi label="Max pause" value={ms(kpi.maxPauseMs)} tone={pauseTone} />
        <Kpi label="Avg pause" value={ms(kpi.avgPauseMs)} />
        <Kpi label="P99 pause" value={ms(kpi.p99PauseMs)} hint={`P95 ${ms(kpi.p95PauseMs)}`} />
        <Kpi label="Allocation" value={rate(kpi.allocationRateMbPerSec)} tone={palette.emerald} />
        <Kpi label="Promotion" value={rate(kpi.promotionRateMbPerSec)} tone={palette.violet} />
        <Kpi label="Pauses" value={formatNumber(kpi.pauseCount)}
          hint={`young ${kpi.youngCount} · mixed ${kpi.mixedCount} · full ${kpi.fullCount}`}
          tone={kpi.fullCount > 0 ? palette.coral : undefined} />
        <Kpi label="Safepoints" value={ms(kpi.safepointTotalMs)} hint={`${safepoints.count} safepoint-ов`} />
      </div>

      {analysis.findings.length > 0 && (
        <ul className="space-y-1.5">
          {analysis.findings.map((f) => (
            <li key={f.code} className="rounded-md border bg-kraken-panel px-3 py-2 text-xs"
              style={{ borderColor: SEVERITY_COLOR[f.severity] }}>
              <div className="flex items-center gap-2">
                <AlertTriangle size={13} style={{ color: SEVERITY_COLOR[f.severity] }} />
                <span className="font-semibold text-kraken-text">{f.title}</span>
                <Badge color={SEVERITY_COLOR[f.severity]}>{f.severity}</Badge>
              </div>
              <p className="mt-0.5 text-kraken-muted">{f.detail}</p>
              {f.advice && <p className="mt-0.5 text-accent-cyan">→ {f.advice}</p>}
            </li>
          ))}
        </ul>
      )}

      <div className="grid gap-3 xl:grid-cols-2">
        <Panel title="Pause Duration Scatter"><div className="p-2"><PauseScatter pauses={analysis.pauses} /></div></Panel>
        <Panel title="Heap Before vs After GC"><div className="p-2"><HeapChart pauses={analysis.pauses} /></div></Panel>
        <Panel title="Allocation vs Promotion Rate"><div className="p-2"><RateChart analysis={analysis} /></div></Panel>
        <Panel title="Причины GC">
          <div className="max-h-80 overflow-auto">
            <table className="w-full text-xs">
              <thead className="sticky top-0 bg-kraken-panel text-2xs uppercase text-kraken-subtle">
                <tr>
                  <th className="px-3 py-1.5 text-left">Cause</th>
                  <th className="px-2 text-right">Раз</th>
                  <th className="px-2 text-right">Σ pause</th>
                  <th className="px-2 text-right">Max</th>
                  <th className="px-3 text-right">% пауз</th>
                </tr>
              </thead>
              <tbody>
                {analysis.causes.map((c) => (
                  <tr key={c.cause} className="border-t border-kraken-border/60 align-top">
                    <td className="px-3 py-1.5">
                      <div className="flex items-center gap-1.5">
                        <span className="h-2 w-2 rounded-full" style={{ backgroundColor: SEVERITY_COLOR[c.severity] }} />
                        <span className="font-mono text-kraken-text">{c.cause}</span>
                      </div>
                      {c.advice && <div className="mt-0.5 text-2xs text-kraken-muted">{c.advice}</div>}
                    </td>
                    <td className="px-2 py-1.5 text-right font-mono">{formatNumber(c.count)}</td>
                    <td className="px-2 py-1.5 text-right font-mono">{ms(c.totalPauseMs)}</td>
                    <td className="px-2 py-1.5 text-right font-mono">{ms(c.maxPauseMs)}</td>
                    <td className="px-3 py-1.5 text-right font-mono">{c.percentOfPauses.toFixed(1)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Panel>
      </div>

      <div className="grid gap-3 xl:grid-cols-[3fr_2fr]">
        <Panel
          title="Slowest Safepoints"
          icon={<Timer size={14} className="text-accent-amber" />}
          extra={(
            <span className="text-2xs text-kraken-subtle">
              TTSP avg {ms(safepoints.avgTtspMs)} · max {ms(safepoints.maxTtspMs)} · порог {ms(safepoints.ttspWarningMs)}
              {safepoints.slowTtspCount > 0 && <span className="text-accent-coral"> · долгий TTSP: {safepoints.slowTtspCount}</span>}
            </span>
          )}
        >
          {safepoints.slowest.length === 0 ? (
            <p className="px-3 py-3 text-xs text-kraken-muted">
              Safepoint-событий нет: включите <code>-Xlog:safepoint*</code> (или запишите live через JFR).
            </p>
          ) : (
            <div className="max-h-96 overflow-auto">
              <table className="w-full text-xs">
                <thead className="sticky top-0 bg-kraken-panel text-2xs uppercase text-kraken-subtle">
                  <tr>
                    <th className="px-3 py-1.5 text-left">Uptime</th>
                    <th className="px-2 text-left">VM operation</th>
                    <th className="w-56 px-2 text-left">TTSP | операция | cleanup</th>
                    <th className="px-3 text-right">Total</th>
                  </tr>
                </thead>
                <tbody>
                  {safepoints.slowest.map((s) => {
                    const total = Math.max(s.totalMs, s.ttspMs + s.operationMs, 0.001);
                    // JDK 17+ counts cleanup and leaving the safepoint in the total too
                    const rest = Math.max(0, s.totalMs - s.ttspMs - s.operationMs);
                    return (
                      <tr key={`${s.uptimeMs}-${s.operation}`}
                        className={clsx('border-t border-kraken-border/60', s.slowTtsp && 'bg-accent-coral/10')}>
                        <td className="px-3 py-1 font-mono text-kraken-muted">{formatDuration(s.uptimeMs)}</td>
                        <td className="px-2 py-1 font-mono">
                          {s.operation}
                          {s.slowTtsp && <Badge color={palette.coral} className="ml-1">long TTSP</Badge>}
                        </td>
                        <td className="px-2 py-1">
                          <div className="flex h-2 overflow-hidden rounded bg-kraken-card"
                            title={`TTSP ${ms(s.ttspMs)} · операция ${ms(s.operationMs)}${rest >= 0.1 ? ` · cleanup / выход ${ms(rest)}` : ''}`}>
                            <span style={{ width: `${(s.ttspMs / total) * 100}%`, backgroundColor: s.slowTtsp ? palette.coral : palette.amber }} />
                            <span style={{ width: `${(s.operationMs / total) * 100}%`, backgroundColor: palette.cyan }} />
                            <span style={{ width: `${(rest / total) * 100}%`, backgroundColor: palette.subtle }} />
                          </div>
                          <div className="mt-0.5 font-mono text-2xs text-kraken-subtle">
                            {ms(s.ttspMs)} | {ms(s.operationMs)}{rest >= 0.1 && ` | cleanup ${ms(rest)}`}
                          </div>
                        </td>
                        <td className="px-3 py-1 text-right font-mono">{ms(s.totalMs)}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
        <Panel title="Safepoint-операции">
          <table className="w-full text-xs">
            <thead className="text-2xs uppercase text-kraken-subtle">
              <tr>
                <th className="px-3 py-1.5 text-left">Операция</th>
                <th className="px-2 text-right">Раз</th>
                <th className="px-2 text-right">Σ</th>
                <th className="px-2 text-right">Max</th>
                <th className="px-3 text-right">Max TTSP</th>
              </tr>
            </thead>
            <tbody>
              {safepoints.operations.map((o) => (
                <tr key={o.operation} className="border-t border-kraken-border/60">
                  <td className="px-3 py-1 font-mono">{o.operation}</td>
                  <td className="px-2 text-right font-mono">{formatNumber(o.count)}</td>
                  <td className="px-2 text-right font-mono">{ms(o.totalMs)}</td>
                  <td className="px-2 text-right font-mono">{ms(o.maxTotalMs)}</td>
                  <td className={clsx('px-3 text-right font-mono', o.maxTtspMs >= safepoints.ttspWarningMs && 'text-accent-coral')}>
                    {ms(o.maxTtspMs)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      </div>
    </div>
  );
}

function OfflineGc({ analyses }: { analyses: GcAnalysisDto[] }) {
  const [selected, setSelected] = useState(0);
  const current = analyses[Math.min(selected, analyses.length - 1)];
  if (!current) {
    return (
      <EmptyState icon={<Gauge size={22} />} title="В отчёте нет GC-анализов">
        GC-анализы попадают в отчёт, если перед экспортом был загружен gc.log или записан GC через JFR.
      </EmptyState>
    );
  }
  return (
    <div className="space-y-3 p-4">
      <div className="flex gap-2 overflow-x-auto pb-1">
        {analyses.map((a, index) => (
          <AnalysisCard key={a.summary.analysisId} summary={a.summary} active={index === selected} onSelect={() => setSelected(index)} />
        ))}
      </div>
      <GcAnalysisPanel analysis={current} />
    </div>
  );
}
