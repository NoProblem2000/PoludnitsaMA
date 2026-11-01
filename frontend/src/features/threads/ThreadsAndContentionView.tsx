import clsx from 'clsx';
import { Activity, Camera, Layers, Loader2, RotateCcw, Skull, Trash2, Users } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type {
  JvmThreadState,
  ThreadLiveDto,
  ThreadSeriesAnalysisDto,
  ThreadSeriesSummaryDto,
} from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { useViewActive } from '@/hooks/useViewActive';
import { EmptyState } from '@/components/common/EmptyState';
import { FileDropZone } from '@/components/common/FileDropZone';
import { ToolbarButton } from '@/components/common/ToolbarButton';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { formatDateTime, formatDuration, formatNumber } from '@/utils/format';
import { ContentionTable } from './ContentionTable';
import { DeadlockGraph } from './DeadlockGraph';
import { ThreadMatrix } from './ThreadMatrix';
import { STATE_ORDER, STUCK_COLOR, STUCK_LABEL, THREAD_STATE_COLOR, frameText, shortFrame } from './threadFormat';

const LIVE_POLL_MS = 2000;
const SERIES_POLL_MS = 1000;

/**
 * Threads & Contention: live contention / deadlock sampling of the target over JMX ThreadMXBean, and series of thread
 * dumps (taken from the target or uploaded as jstack text) with the Thread × Dump matrix, stuck threads and clusters.
 */
export function ThreadsAndContentionView() {
  const mode = useInspectorStore((s) => s.mode);
  const report = useInspectorStore((s) => s.offlineReport);
  if (mode === 'offline') {
    return <OfflineThreads live={report?.threadsLive ?? null} series={report?.threadSeries ?? []} />;
  }
  return <LiveThreads />;
}

function LiveThreads() {
  const activePid = useInspectorStore((s) => s.activePid);
  const pushToast = useInspectorStore((s) => s.pushToast);
  const [live, setLive] = useState<ThreadLiveDto | null>(null);
  const [liveError, setLiveError] = useState<string | null>(null);
  const [series, setSeries] = useState<ThreadSeriesSummaryDto[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<ThreadSeriesAnalysisDto | null>(null);
  const [dumps, setDumps] = useState(5);
  const [intervalMs, setIntervalMs] = useState(2000);
  const [starting, setStarting] = useState(false);
  const [uploading, setUploading] = useState(false);

  // live sampling: every poll keeps the backend sampler of this pid alive. Paused while the view is hidden, so the
  // sampler idles out and switches contention monitoring in the target off again; the last picture stays shown.
  const active = useViewActive();
  const polledPid = useRef(activePid);
  useEffect(() => {
    if (polledPid.current !== activePid) {
      polledPid.current = activePid;
      setLive(null);
      setLiveError(null);
    }
    if (activePid == null || !active) {
      return undefined;
    }
    let cancelled = false;
    const poll = async () => {
      try {
        const next = await api.threads.live(activePid);
        if (!cancelled) {
          setLive(next);
          setLiveError(null);
        }
      } catch (error) {
        if (!cancelled) {
          setLiveError(errorMessage(error));
        }
      }
    };
    void poll();
    const timer = window.setInterval(() => void poll(), LIVE_POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [activePid, active]);

  const refreshSeries = useCallback(async () => {
    try {
      setSeries(await api.threads.series());
    } catch {
      // the next poll retries
    }
  }, []);

  useEffect(() => {
    void refreshSeries();
  }, [refreshSeries]);

  const running = series.some((s) => s.status === 'RUNNING');
  useEffect(() => {
    if (!running) {
      return undefined;
    }
    const timer = window.setInterval(() => void refreshSeries(), SERIES_POLL_MS);
    return () => window.clearInterval(timer);
  }, [running, refreshSeries]);

  // newest series when nothing is selected
  useEffect(() => {
    if (!selectedId || !series.some((s) => s.seriesId === selectedId)) {
      setSelectedId(series[0]?.seriesId ?? null);
    }
  }, [series, selectedId]);

  const selected = series.find((s) => s.seriesId === selectedId) ?? null;
  const readyId = selected?.status === 'READY' ? selected.seriesId : null;
  useEffect(() => {
    setAnalysis(null);
    if (!readyId) {
      return;
    }
    let cancelled = false;
    api.threads.analysis(readyId).then(
      (a) => !cancelled && setAnalysis(a),
      (error) => !cancelled && pushToast({ tone: 'error', title: 'Не удалось загрузить серию', message: errorMessage(error) }),
    );
    return () => {
      cancelled = true;
    };
  }, [readyId, pushToast]);

  const startSeries = async () => {
    if (activePid == null) {
      return;
    }
    setStarting(true);
    try {
      const started = await api.threads.startSeries({ pid: activePid, dumps, intervalMs });
      await refreshSeries();
      setSelectedId(started.seriesId);
    } catch (error) {
      pushToast({ tone: 'error', title: 'Серия не запущена', message: errorMessage(error) });
    } finally {
      setStarting(false);
    }
  };

  const upload = async (file: File) => {
    setUploading(true);
    try {
      const uploaded = await api.threads.upload(file);
      await refreshSeries();
      setSelectedId(uploaded.seriesId);
      pushToast({ tone: 'success', title: 'Thread dump загружен', message: `${uploaded.dumpsTaken} дамп(ов), ${uploaded.threadCount} потоков` });
    } catch (error) {
      pushToast({ tone: 'error', title: 'Не удалось разобрать thread dump', message: errorMessage(error) });
    } finally {
      setUploading(false);
    }
  };

  const remove = async (id: string) => {
    try {
      await api.threads.delete(id);
      await refreshSeries();
    } catch (error) {
      pushToast({ tone: 'error', title: 'Не удалось удалить серию', message: errorMessage(error) });
    }
  };

  const resetLive = async () => {
    if (activePid == null) {
      return;
    }
    try {
      await api.threads.resetLive(activePid);
      setLive(await api.threads.live(activePid));
    } catch (error) {
      pushToast({ tone: 'error', title: 'Не удалось сбросить статистику', message: errorMessage(error) });
    }
  };

  return (
    <div className="space-y-3 p-4">
      {activePid == null ? (
        <div className="rounded-md border border-kraken-border bg-kraken-panel px-3 py-2 text-xs text-kraken-muted">
          Выберите процесс JVM, чтобы смотреть контеншн и deadlock-и в реальном времени и снимать серии дампов.
          Без процесса можно загрузить jstack-файл.
        </div>
      ) : (
        <LivePanel live={live} error={liveError} onReset={resetLive} />
      )}

      <section className="rounded-md border border-kraken-border bg-kraken-panel p-3">
        <div className="mb-2 flex items-center gap-2 text-xs font-semibold">
          <Layers size={14} className="text-accent-violet" />
          Thread Dump Series Analyzer
        </div>
        <div className="grid gap-3 lg:grid-cols-[1fr_1fr]">
          <div className="flex flex-wrap items-end gap-2">
            <label className="text-2xs text-kraken-muted">
              Дампов
              <input
                type="number"
                min={2}
                max={30}
                value={dumps}
                onChange={(e) => setDumps(Math.max(2, Math.min(30, Number(e.target.value) || 2)))}
                className="mt-0.5 block h-8 w-20 rounded border border-kraken-border bg-kraken-bg px-2 text-xs"
              />
            </label>
            <label className="text-2xs text-kraken-muted">
              Интервал, мс
              <input
                type="number"
                min={200}
                max={60000}
                step={500}
                value={intervalMs}
                onChange={(e) => setIntervalMs(Math.max(200, Math.min(60_000, Number(e.target.value) || 200)))}
                className="mt-0.5 block h-8 w-24 rounded border border-kraken-border bg-kraken-bg px-2 text-xs"
              />
            </label>
            <ToolbarButton
              icon={<Camera size={14} />}
              label={`📸 Снять серию Thread Dumps (${dumps} × ${intervalMs / 1000} с)`}
              tone="primary"
              busy={starting}
              disabled={activePid == null || running}
              onClick={() => void startSeries()}
            />
          </div>
          <FileDropZone
            title="…или перетащите jstack / jcmd Thread.print"
            hint="Один файл может содержать несколько дампов подряд"
            accept=".txt,.tdump,.log,.jstack,text/plain"
            busy={uploading}
            onFile={(file) => void upload(file)}
          />
        </div>
        {series.length > 0 && (
          <div className="mt-3 flex gap-2 overflow-x-auto pb-1">
            {series.map((s) => (
              <SeriesCard
                key={s.seriesId}
                series={s}
                active={s.seriesId === selectedId}
                onSelect={() => setSelectedId(s.seriesId)}
                onDelete={() => void remove(s.seriesId)}
              />
            ))}
          </div>
        )}
      </section>

      {selected?.status === 'RUNNING' && (
        <div className="flex items-center gap-2 rounded-md border border-kraken-border bg-kraken-panel px-3 py-3 text-xs">
          <Loader2 size={14} className="animate-spin text-accent-cyan" />
          {selected.progressMessage ?? 'Снимаю дампы…'} ({selected.dumpsTaken}/{selected.dumpsRequested})
        </div>
      )}
      {selected?.status === 'FAILED' && (
        <div className="rounded-md border border-accent-coral/60 bg-accent-coral/5 px-3 py-2 text-xs text-accent-coral">
          Серия не удалась: {selected.errorMessage}
        </div>
      )}
      {analysis && <SeriesAnalysis analysis={analysis} />}
    </div>
  );
}

function StateCounts({ states }: { states: Partial<Record<JvmThreadState, number>> }) {
  return (
    <span className="flex flex-wrap gap-1">
      {STATE_ORDER.filter((s) => (states[s] ?? 0) > 0).map((s) => (
        <Badge key={s} color={THREAD_STATE_COLOR[s]}>{s} {states[s]}</Badge>
      ))}
    </span>
  );
}

function LivePanel({ live, error, onReset }: { live: ThreadLiveDto | null; error: string | null; onReset(): void }) {
  const [showAll, setShowAll] = useState(false);
  if (!live) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-kraken-border bg-kraken-panel px-3 py-3 text-xs text-kraken-muted">
        {error ? <span className="text-accent-coral">ThreadMXBean недоступен: {error}</span>
          : <><Loader2 size={14} className="animate-spin" /> Подключаюсь к ThreadMXBean цели…</>}
      </div>
    );
  }
  const threads = showAll ? live.threads : live.threads.filter((t) => t.state === 'BLOCKED' || t.state === 'RUNNABLE' || t.serverThread);
  return (
    <div className="space-y-3">
      {live.deadlocks.map((d) => (
        <DeadlockGraph key={`${d.kind}-${d.cycle.map((n) => n.threadId).join('-')}`} deadlock={d} />
      ))}

      <div className="flex flex-wrap items-center gap-3 rounded-md border border-kraken-border bg-kraken-panel px-3 py-2 text-xs">
        <Activity size={14} className="text-accent-emerald" />
        <span className="font-semibold">Live · ThreadMXBean</span>
        <span className="font-mono">{live.threadCount} потоков</span>
        <span className="text-kraken-muted">daemon {live.daemonCount} · peak {live.peakThreadCount}</span>
        <StateCounts states={live.states} />
        <Badge color={live.contentionMonitoring ? palette.emerald : palette.amber}
          title="ThreadContentionMonitoringEnabled: точное blocked/waited time вместо оценки по сэмплам">
          contention monitoring {live.contentionMonitoring ? 'on' : 'off'}
        </Badge>
        <Badge color={live.cpuTimeMonitoring ? palette.emerald : palette.subtle}>CPU time {live.cpuTimeMonitoring ? 'on' : 'off'}</Badge>
        {live.deadlocks.length === 0 && <Badge color={palette.emerald}>deadlock-ов нет</Badge>}
        <span className="ml-auto text-2xs text-kraken-subtle">
          {formatNumber(live.samples)} сэмплов · каждые {live.sampleIntervalMs} мс · с {formatDateTime(live.samplingSince)}
        </span>
        <ToolbarButton icon={<RotateCcw size={13} />} label="Сбросить" onClick={onReset} />
      </div>

      <ContentionTable rows={live.contention} />

      <section className="overflow-hidden rounded-md border border-kraken-border bg-kraken-panel">
        <header className="flex items-center gap-2 border-b border-kraken-border px-3 py-2 text-xs font-semibold">
          <Users size={14} className="text-accent-cyan" />
          Потоки ({threads.length}{showAll ? '' : ` из ${live.threads.length}`})
          <button type="button" onClick={() => setShowAll(!showAll)} className="ml-auto text-2xs font-normal text-accent-cyan">
            {showAll ? 'Только активные / worker' : 'Показать все'}
          </button>
        </header>
        <div className="max-h-80 overflow-auto">
          <table className="w-full text-xs">
            <thead className="sticky top-0 bg-kraken-panel text-2xs uppercase text-kraken-subtle">
              <tr>
                <th className="px-3 py-1.5 text-left">Поток</th>
                <th className="px-2 text-left">State</th>
                <th className="px-2 text-right">CPU %</th>
                <th className="px-2 text-right">Blocked</th>
                <th className="px-2 text-left">Lock / owner</th>
                <th className="px-3 text-left">Где</th>
              </tr>
            </thead>
            <tbody>
              {threads.map((t) => (
                <tr key={t.threadId} className="border-t border-kraken-border/50">
                  <td className="max-w-64 truncate px-3 py-1" title={t.name}>
                    {t.name}
                    {t.serverThread && <span className="ml-1 text-2xs text-accent-cyan">worker</span>}
                  </td>
                  <td className="px-2"><span style={{ color: THREAD_STATE_COLOR[t.state] }}>{t.state}</span></td>
                  <td className="px-2 text-right font-mono">{t.cpuPercent == null ? '—' : t.cpuPercent.toFixed(1)}</td>
                  <td className="px-2 text-right font-mono" title={`${t.blockedCount} раз`}>
                    {t.blockedTimeMs != null ? `${formatNumber(t.blockedTimeMs)} ms` : formatNumber(t.blockedCount)}
                  </td>
                  <td className="max-w-56 truncate px-2 font-mono text-2xs" title={t.lockName ?? undefined}>
                    {t.lockName ?? ''}{t.lockOwnerName && <span className="text-accent-coral"> ← {t.lockOwnerName}</span>}
                  </td>
                  <td className="px-3 font-mono text-2xs text-kraken-muted" title={t.topFrame ?? undefined}>{shortFrame(t.topFrame)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function SeriesCard({ series, active, onSelect, onDelete }: {
  series: ThreadSeriesSummaryDto; active: boolean; onSelect(): void; onDelete?: () => void;
}) {
  return (
    <div
      className={clsx(
        'min-w-60 rounded-md border px-3 py-2 text-xs',
        active ? 'border-accent-cyan bg-kraken-card' : 'border-kraken-border bg-kraken-panel hover:border-accent-cyan/50',
      )}
    >
      <button type="button" onClick={onSelect} className="block w-full text-left">
        <div className="flex items-center gap-1.5">
          <Badge color={series.source === 'LIVE' ? palette.cyan : palette.violet}>{series.source === 'LIVE' ? `PID ${series.pid}` : 'jstack'}</Badge>
          <Badge color={series.status === 'READY' ? palette.emerald : series.status === 'FAILED' ? palette.coral : palette.amber}>{series.status}</Badge>
        </div>
        <div className="mt-1 truncate text-kraken-text" title={series.name}>{series.name}</div>
        <div className="text-2xs text-kraken-muted">
          {series.dumpsTaken} дамп(ов) · {series.threadCount} потоков · {formatDateTime(series.startedAt)}
        </div>
        {series.status === 'READY' && (
          <div className="mt-0.5 text-2xs">
            <span className={series.stuckCount ? 'text-accent-coral' : 'text-kraken-subtle'}>stuck {series.stuckCount}</span>
            <span className={series.deadlockCount ? 'ml-2 text-accent-coral' : 'ml-2 text-kraken-subtle'}>deadlock {series.deadlockCount}</span>
            <span className="ml-2 text-kraken-subtle">кластеров {series.clusterCount}</span>
          </div>
        )}
      </button>
      {onDelete && series.status !== 'RUNNING' && (
        <button type="button" onClick={onDelete} title="Удалить серию" className="mt-1 text-kraken-subtle hover:text-accent-coral">
          <Trash2 size={12} />
        </button>
      )}
    </div>
  );
}

function SeriesAnalysis({ analysis }: { analysis: ThreadSeriesAnalysisDto }) {
  const clusters = useMemo(() => analysis.clusters.slice(0, 30), [analysis.clusters]);
  return (
    <div className="space-y-3">
      {analysis.deadlocks.map((d) => (
        <DeadlockGraph key={`series-${d.kind}-${d.cycle.map((n) => n.threadId).join('-')}`} deadlock={d} />
      ))}

      <div className="flex flex-wrap gap-2">
        {analysis.dumps.map((d) => (
          <div key={d.index} className="rounded-md border border-kraken-border bg-kraken-panel px-2.5 py-1.5 text-2xs">
            <div className="font-mono text-kraken-text">
              #{d.index + 1}{d.offsetMs != null && <span className="text-kraken-subtle"> +{formatDuration(d.offsetMs)}</span>}
              <span className="ml-1 text-kraken-muted">{d.threadCount} потоков</span>
            </div>
            <StateCounts states={d.states} />
          </div>
        ))}
      </div>

      <section className="rounded-md border border-kraken-border bg-kraken-panel">
        <header className="flex items-center gap-2 border-b border-kraken-border px-3 py-2 text-xs font-semibold">
          <Skull size={14} className="text-accent-coral" />
          Зависшие потоки ({analysis.stuck.length})
          <span className="font-normal text-kraken-subtle">один и тот же стек в 3+ дампах подряд, RUNNABLE или BLOCKED</span>
        </header>
        {analysis.stuck.length === 0 ? (
          <p className="px-3 py-3 text-xs text-kraken-muted">Зависших потоков не найдено.</p>
        ) : (
          <ul className="divide-y divide-kraken-border/60">
            {analysis.stuck.map((s) => (
              <li key={`${s.name}-${s.fromDump}`} className="px-3 py-2 text-xs">
                <details>
                  <summary className="flex cursor-pointer flex-wrap items-center gap-2">
                    <Badge color={STUCK_COLOR[s.kind]} solid>{STUCK_LABEL[s.kind]}</Badge>
                    <span className="font-semibold text-kraken-text">{s.name}</span>
                    {s.serverThread && <span className="text-2xs text-accent-cyan">worker</span>}
                    <span style={{ color: THREAD_STATE_COLOR[s.state] }}>{s.state}</span>
                    <span className="text-kraken-muted">
                      дампы #{s.fromDump + 1}–#{s.toDump + 1} ({s.dumps}){s.durationMs != null && ` · ≥ ${formatDuration(s.durationMs)}`}
                      {s.cpuDeltaMs != null && ` · CPU +${formatNumber(s.cpuDeltaMs)} ms`}
                    </span>
                  </summary>
                  <p className="mt-1 text-kraken-muted">{s.reason}</p>
                  {s.lockName && <p className="text-accent-amber">lock {s.lockName}</p>}
                  <pre className="mt-1 max-h-48 overflow-auto font-mono text-2xs leading-relaxed text-kraken-muted">
                    {s.stack.map((f) => `at ${frameText(f)}`).join('\n')}
                  </pre>
                </details>
              </li>
            ))}
          </ul>
        )}
      </section>

      <ThreadMatrix analysis={analysis} />

      <div className="grid gap-3 xl:grid-cols-2">
        <section className="overflow-hidden rounded-md border border-kraken-border bg-kraken-panel">
          <header className="border-b border-kraken-border px-3 py-2 text-xs font-semibold">
            Кластеры одинаковых стеков ({analysis.clusters.length})
          </header>
          {clusters.length === 0 ? (
            <p className="px-3 py-3 text-xs text-kraken-muted">Нет групп потоков с одинаковым стеком.</p>
          ) : (
            <ul className="max-h-96 divide-y divide-kraken-border/60 overflow-auto">
              {clusters.map((c) => (
                <li key={c.clusterId} className="px-3 py-1.5 text-xs">
                  <details>
                    <summary className="flex cursor-pointer items-center gap-2">
                      <span className="w-10 font-mono text-accent-violet">C{c.clusterId}</span>
                      <span className="w-10 text-right font-mono font-semibold">{c.threadCount}×</span>
                      <span style={{ color: THREAD_STATE_COLOR[c.state] }}>{c.state}</span>
                      <span className="min-w-0 flex-1 truncate font-mono text-kraken-muted" title={c.topFrame ?? undefined}>
                        {shortFrame(c.topFrame)}
                      </span>
                      <span className="text-2xs text-kraken-subtle">дамп #{c.dumpIndex + 1}</span>
                    </summary>
                    {c.lockName && <div className="mt-1 text-accent-amber">lock {c.lockName}</div>}
                    <div className="mt-1 text-2xs text-kraken-muted">{c.threads.join(', ')}</div>
                    <pre className="mt-1 max-h-40 overflow-auto font-mono text-2xs text-kraken-subtle">
                      {(analysis.stacks[c.stackId] ?? []).map((f) => `at ${frameText(f)}`).join('\n')}
                    </pre>
                  </details>
                </li>
              ))}
            </ul>
          )}
        </section>
        <ContentionTable
          rows={analysis.contention}
          title={analysis.contentionTimed ? 'Monitor Contention за время серии' : 'Monitor Contention по дампам'}
        />
      </div>
    </div>
  );
}

function OfflineThreads({ live, series }: { live: ThreadLiveDto | null; series: ThreadSeriesAnalysisDto[] }) {
  const [selected, setSelected] = useState(0);
  if (!live && series.length === 0) {
    return (
      <EmptyState icon={<Users size={22} />} title="В отчёте нет данных о потоках">
        Серии thread dump-ов и live-контеншн попадают в отчёт, если их сняли перед экспортом.
      </EmptyState>
    );
  }
  const current = series[Math.min(selected, series.length - 1)];
  return (
    <div className="space-y-3 p-4">
      {live && (
        <>
          <div className="text-2xs text-kraken-subtle">Live-снимок PID {live.pid} на {formatDateTime(live.capturedAt)}</div>
          {live.deadlocks.map((d) => (
            <DeadlockGraph key={`offline-${d.cycle.map((n) => n.threadId).join('-')}`} deadlock={d} />
          ))}
          <ContentionTable rows={live.contention} />
        </>
      )}
      {series.length > 0 && (
        <div className="flex gap-2 overflow-x-auto pb-1">
          {series.map((s, index) => (
            <SeriesCard key={s.summary.seriesId} series={s.summary} active={index === selected} onSelect={() => setSelected(index)} />
          ))}
        </div>
      )}
      {current && <SeriesAnalysis analysis={current} />}
    </div>
  );
}
