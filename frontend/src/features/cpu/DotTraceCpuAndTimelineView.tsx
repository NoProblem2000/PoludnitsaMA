import clsx from 'clsx';
import { AlertTriangle, Cpu, Flame, GitCompare, Layers, Loader2, Play, Split, Trash2, X } from 'lucide-react';
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { api, type ProfileWindow } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type {
  ButterflyDto,
  DiffFlameGraphDto,
  FlameGraphDto,
  HotspotDto,
  ProfileMetric,
  ProfilerCapabilitiesDto,
  ProfileSource,
  ProfileSummaryDto,
  SubsystemBreakdownDto,
  TimelineDto,
} from '@/api/types';
import { Badge } from '@/components/common/Badge';
import { EmptyState } from '@/components/common/EmptyState';
import { ToolbarButton } from '@/components/common/ToolbarButton';
import { useNow } from '@/hooks/useNow';
import { useViewActive } from '@/hooks/useViewActive';
import { useInspectorStore } from '@/store/useInspectorStore';
import { palette } from '@/theme/palette';
import { formatDuration, formatNumber, formatTime } from '@/utils/format';
import { FlameGraphView, fromDiff, fromFlame } from './FlameGraphView';
import { HotspotsButterflyPanel, type MethodRef } from './HotspotsButterflyPanel';
import { OfflineProfilesView } from './OfflineProfilesView';
import { ProfileTimeline, type TimeWindow } from './ProfileTimeline';
import { formatMs, formatValue, METRIC_HINT, METRIC_LABEL } from './profileFormat';
import { SubsystemsPanel } from './SubsystemsPanel';

type Tab = 'flame' | 'diff' | 'hotspots' | 'subsystems';

const TABS: { id: Tab; label: string; icon: ReactNode }[] = [
  { id: 'flame', label: 'Flame Graph', icon: <Flame size={14} /> },
  { id: 'diff', label: 'Differential Flame Graph', icon: <GitCompare size={14} /> },
  { id: 'hotspots', label: 'Hotspots & Butterfly', icon: <Split size={14} /> },
  { id: 'subsystems', label: 'Subsystems Breakdown', icon: <Layers size={14} /> },
];

const DURATIONS = [10, 30, 60, 120, 300];
const POLL_MS = 1_500;
const TIMELINE_BUCKETS = 240;

interface Loaded<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

/** Runs {@code load} whenever {@code key} changes (null: nothing to load), dropping answers to stale keys. */
function useLoad<T>(key: string | null, load: () => Promise<T>): Loaded<T> {
  const [state, setState] = useState<Loaded<T>>({ data: null, loading: false, error: null });
  useEffect(() => {
    if (key == null) {
      setState({ data: null, loading: false, error: null });
      return undefined;
    }
    let current = true;
    setState((s) => ({ data: s.data, loading: true, error: null }));
    load().then(
      (data) => current && setState({ data, loading: false, error: null }),
      (error: unknown) => current && setState({ data: null, loading: false, error: errorMessage(error) }),
    );
    return () => {
      current = false;
    };
  }, [key]);
  return state;
}

function statusColor(profile: ProfileSummaryDto): string {
  switch (profile.status) {
    case 'READY':
      return palette.emerald;
    case 'FAILED':
      return palette.coral;
    default:
      return palette.amber;
  }
}

function Segmented<T extends string>({ value, options, onChange, disabled }: {
  value: T;
  options: { value: T; label: string; title?: string; disabled?: boolean }[];
  onChange(value: T): void;
  disabled?: boolean;
}) {
  return (
    <div className="inline-flex overflow-hidden rounded-md border border-kraken-border">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          title={option.title}
          disabled={disabled || option.disabled}
          onClick={() => onChange(option.value)}
          className={clsx(
            'h-7 px-2.5 text-2xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-40',
            value === option.value ? 'bg-accent-cyan/15 text-accent-cyan' : 'text-kraken-muted hover:text-kraken-text',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

function ErrorLine({ message }: { message: string | null }) {
  if (!message) {
    return null;
  }
  return (
    <div className="flex items-center gap-2 rounded border border-accent-coral/40 bg-accent-coral/10 px-3 py-2 text-xs text-accent-coral">
      <AlertTriangle size={14} /> {message}
    </div>
  );
}

function Loading() {
  return (
    <div className="flex items-center gap-2 p-4 text-xs text-kraken-muted">
      <Loader2 size={14} className="animate-spin" /> Загрузка…
    </div>
  );
}

/** Start form: JFR through the FlightRecorderMXBean of the target, or async-profiler where it is installed. */
function StartPanel({ pid, capabilities, busy, onStart }: {
  pid: number | null;
  capabilities: ProfilerCapabilitiesDto | null;
  busy: boolean;
  onStart(source: ProfileSource, mode: ProfileMetric, durationSeconds: number, tracing: boolean): void;
}) {
  const [source, setSource] = useState<ProfileSource>('JFR');
  const [mode, setMode] = useState<ProfileMetric>('CPU');
  const [duration, setDuration] = useState(30);
  const [tracing, setTracing] = useState(false);
  const asyncOk = capabilities?.asyncProfilerAvailable ?? false;

  return (
    <div className="flex flex-wrap items-center gap-3 rounded-md border border-kraken-border bg-kraken-panel px-3 py-2.5">
      <span className="text-xs font-semibold text-kraken-text">Новый профиль</span>
      <Segmented<ProfileSource>
        value={source}
        onChange={setSource}
        options={[
          { value: 'JFR', label: 'JFR (FlightRecorderMXBean)', title: 'CPU, Wall-Clock и аллокации за одну запись' },
          {
            value: 'ASYNC_PROFILER', label: 'async-profiler', disabled: !asyncOk,
            title: asyncOk ? capabilities?.asyncProfilerPath ?? '' : capabilities?.asyncProfilerReason ?? 'недоступен',
          },
        ]}
      />
      {source === 'ASYNC_PROFILER' && (
        <Segmented<ProfileMetric>
          value={mode}
          onChange={setMode}
          options={[
            { value: 'CPU', label: 'cpu' },
            { value: 'WALL', label: 'wall' },
            { value: 'ALLOC', label: 'alloc' },
          ]}
        />
      )}
      <label className="flex items-center gap-1.5 text-2xs text-kraken-muted">
        Длительность
        <select
          value={duration}
          onChange={(e) => setDuration(Number(e.target.value))}
          className="h-7 rounded border border-kraken-border bg-kraken-bg px-1.5 text-xs text-kraken-text"
        >
          {DURATIONS.map((d) => <option key={d} value={d}>{d} с</option>)}
        </select>
      </label>
      <Segmented
        value={tracing ? 'tracing' : 'sampling'}
        onChange={(v) => setTracing(v === 'tracing')}
        disabled={source !== 'JFR'}
        options={[
          { value: 'sampling', label: 'Sampling', title: 'Сэмплы стека + события длиннее 10 мс: низкий overhead' },
          {
            value: 'tracing', label: 'Tracing',
            title: 'Park / Monitor / I/O от 1 мс и каждое исключение со стеком: точнее, но дороже',
          },
        ]}
      />
      <ToolbarButton
        tone="primary"
        icon={<Play size={14} />}
        label={pid == null ? 'Выберите процесс' : `Записать PID ${pid}`}
        busy={busy}
        disabled={pid == null}
        onClick={() => onStart(source, mode, duration, tracing)}
        className="ml-auto"
      />
      {capabilities && !asyncOk && (
        <span className="w-full text-2xs text-kraken-subtle">async-profiler: {capabilities.asyncProfilerReason}</span>
      )}
    </div>
  );
}

function ProfileCard({ profile, active, baseline, now, onOpen, onBaseline, onDelete }: {
  profile: ProfileSummaryDto;
  active: boolean;
  baseline: boolean;
  now: number;
  onOpen(): void;
  onBaseline(): void;
  onDelete(): void;
}) {
  const started = Date.parse(profile.startedAt);
  const recording = profile.status === 'RECORDING';
  const progress = recording ? Math.min(1, (now - started) / Math.max(1, profile.durationMs)) : 1;
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => e.key === 'Enter' && onOpen()}
      className={clsx(
        'relative w-60 shrink-0 cursor-pointer overflow-hidden rounded-md border bg-kraken-card px-3 py-2 text-left transition-colors',
        active ? 'border-accent-cyan shadow-glow-cyan' : baseline ? 'border-accent-violet' : 'border-kraken-border hover:border-kraken-muted',
      )}
    >
      <div className="flex items-center gap-2">
        <Badge color={statusColor(profile)}>{profile.status}</Badge>
        <Badge color={profile.source === 'JFR' ? palette.cyan : palette.violet}>
          {profile.source === 'JFR' ? 'JFR' : `async ${profile.mode.toLowerCase()}`}
        </Badge>
        {profile.tracing && <Badge color={palette.amber}>tracing</Badge>}
        <button
          type="button"
          title="Удалить профиль"
          onClick={(e) => {
            e.stopPropagation();
            onDelete();
          }}
          className="ml-auto text-kraken-subtle hover:text-accent-coral"
        >
          {recording ? <X size={13} /> : <Trash2 size={13} />}
        </button>
      </div>
      <div className="mt-1 truncate text-xs text-kraken-text" title={profile.displayName}>{profile.displayName}</div>
      <div className="font-mono text-2xs text-kraken-muted">
        PID {profile.pid} · {formatTime(profile.startedAt)} · {formatDuration(profile.durationMs)}
      </div>
      <div className="truncate font-mono text-2xs text-kraken-subtle">
        {profile.status === 'READY'
          ? `${formatNumber(profile.sampleCount)} сэмпл. · ${formatNumber(profile.eventCount)} соб. · ${profile.threadCount} потоков`
          : profile.status === 'FAILED' ? profile.errorMessage : profile.progressMessage}
      </div>
      {profile.status === 'READY' && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onBaseline();
          }}
          className={clsx('mt-1 text-2xs', baseline ? 'text-accent-violet' : 'text-kraken-subtle hover:text-accent-violet')}
        >
          {baseline ? '✓ baseline для diff' : 'сделать baseline для diff'}
        </button>
      )}
      {recording && (
        <div className="absolute inset-x-0 bottom-0 h-0.5 bg-kraken-border">
          <div className="h-full bg-accent-amber transition-all" style={{ width: `${progress * 100}%` }} />
        </div>
      )}
    </div>
  );
}

/**
 * Screen "CPU & Timeline" (dotTrace analog): record a profile of the target, see CPU / GC / locks / I/O and thread
 * states on one timeline, brush an interval and read the flame graph, hotspots with their butterfly and the
 * subsystem breakdown of exactly that interval (and thread).
 */
export function DotTraceCpuAndTimelineView() {
  const mode = useInspectorStore((s) => s.mode);
  const offlineProfiles = useInspectorStore((s) => s.offlineReport?.profiles);
  const activePid = useInspectorStore((s) => s.activePid);
  const pushToast = useInspectorStore((s) => s.pushToast);
  const now = useNow(500);

  const [capabilities, setCapabilities] = useState<ProfilerCapabilitiesDto | null>(null);
  const [profiles, setProfiles] = useState<ProfileSummaryDto[]>([]);
  const [starting, setStarting] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [baselineId, setBaselineId] = useState<string | null>(null);
  const [metric, setMetric] = useState<ProfileMetric>('CPU');
  const [tab, setTab] = useState<Tab>('flame');
  const [selection, setSelection] = useState<TimeWindow | null>(null);
  const [thread, setThread] = useState<number | null>(null);
  const [method, setMethod] = useState<MethodRef | null>(null);

  const refresh = useCallback(async () => {
    try {
      setProfiles(await api.profiles.list());
    } catch {
      // the next poll retries
    }
  }, []);

  // again on every return to the view: it stays mounted while hidden
  const active = useViewActive();
  useEffect(() => {
    if (mode !== 'live' || !active) {
      return;
    }
    api.profiles.capabilities().then(setCapabilities, () => setCapabilities(null));
    void refresh();
  }, [mode, active, refresh]);

  const pending = profiles.some((p) => p.status === 'RECORDING' || p.status === 'PARSING');
  useEffect(() => {
    if (!pending) {
      return undefined;
    }
    const timer = window.setInterval(() => void refresh(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [pending, refresh]);

  const profile = profiles.find((p) => p.profileId === selectedId) ?? null;
  const ready = profile?.status === 'READY' ? profile : null;
  const baseline = profiles.find((p) => p.profileId === baselineId && p.status === 'READY') ?? null;

  // pick the newest ready profile when nothing is selected
  useEffect(() => {
    const newest = profiles[0];
    if (newest && (!selectedId || !profiles.some((p) => p.profileId === selectedId))) {
      setSelectedId(newest.profileId);
    }
  }, [profiles, selectedId]);

  // a new profile: reset the cross-filter and pick a metric it has
  useEffect(() => {
    setSelection(null);
    setThread(null);
    setMethod(null);
    if (ready && !ready.metrics.includes(metric)) {
      setMetric(ready.metrics[0] ?? 'CPU');
    }
  }, [ready?.profileId]);

  const filter: ProfileWindow = useMemo(
    () => ({ from: selection?.from ?? null, to: selection?.to ?? null, thread }),
    [selection, thread],
  );
  const windowKey = `${selection?.from ?? ''}:${selection?.to ?? ''}:${thread ?? ''}`;
  const id = ready?.profileId ?? null;

  const timeline = useLoad<TimelineDto>(id && `${id}`, () => api.profiles.timeline(id!, TIMELINE_BUCKETS));
  const flame = useLoad<FlameGraphDto>(id && tab === 'flame' ? `${id}|${metric}|${windowKey}` : null,
    () => api.profiles.flame(id!, metric, filter));
  const diff = useLoad<DiffFlameGraphDto>(
    id && tab === 'diff' && baseline && baseline.profileId !== id ? `${baseline.profileId}|${id}|${metric}` : null,
    () => api.profiles.diffFlame(baseline!.profileId, id!, metric));
  const hotspots = useLoad<HotspotDto[]>(id && tab === 'hotspots' ? `${id}|${metric}|${windowKey}` : null,
    () => api.profiles.hotspots(id!, metric, filter));
  const butterfly = useLoad<ButterflyDto>(
    id && tab === 'hotspots' && method ? `${id}|${metric}|${windowKey}|${method.className}|${method.methodName}` : null,
    () => api.profiles.butterfly(id!, method!.className, method!.methodName, metric, filter));
  const subsystems = useLoad<SubsystemBreakdownDto>(id && tab === 'subsystems' ? `${id}|${metric}|${windowKey}` : null,
    () => api.profiles.subsystems(id!, metric, filter));

  // the heaviest hotspot opens in the butterfly by default
  useEffect(() => {
    const top = hotspots.data?.[0];
    if (!method && top) {
      setMethod({ className: top.className, methodName: top.methodName });
    }
  }, [hotspots.data, method]);

  const flameRoot = useMemo(() => (flame.data ? fromFlame(flame.data.root) : null), [flame.data]);
  const diffRoot = useMemo(() => (diff.data ? fromDiff(diff.data.root) : null), [diff.data]);

  async function start(source: ProfileSource, sampled: ProfileMetric, durationSeconds: number, tracing: boolean) {
    if (activePid == null) {
      return;
    }
    setStarting(true);
    try {
      const started = await api.profiles.start({ pid: activePid, source, mode: sampled, durationSeconds, tracing });
      setProfiles((current) => [started, ...current.filter((p) => p.profileId !== started.profileId)]);
      if (ready) {
        setBaselineId(ready.profileId);
      }
      setSelectedId(started.profileId);
      pushToast({ tone: 'info', title: 'Профилирование запущено', message: `${started.profileId}, ${durationSeconds} с` });
    } catch (error) {
      pushToast({ tone: 'error', title: 'Профиль не запущен', message: errorMessage(error) });
    } finally {
      setStarting(false);
    }
  }

  async function remove(profileId: string) {
    try {
      await api.profiles.delete(profileId);
      if (baselineId === profileId) {
        setBaselineId(null);
      }
      if (selectedId === profileId) {
        setSelectedId(null);
      }
      await refresh();
    } catch (error) {
      pushToast({ tone: 'error', title: 'Не удалось удалить профиль', message: errorMessage(error) });
    }
  }

  function openMethod(className: string, methodName: string) {
    setMethod({ className, methodName });
    setTab('hotspots');
  }

  if (mode !== 'live') {
    return <OfflineProfilesView profiles={offlineProfiles ?? []} />;
  }

  const unit = metric === 'ALLOC' ? 'bytes' : 'us';
  const threadName = thread != null ? timeline.data?.threads.find((t) => t.threadIndex === thread)?.name ?? `#${thread}` : null;

  return (
    <div className="space-y-3 p-4">
      <StartPanel pid={activePid} capabilities={capabilities} busy={starting} onStart={start} />

      {profiles.length > 0 ? (
        <div className="flex gap-2 overflow-x-auto pb-1">
          {profiles.map((p) => (
            <ProfileCard
              key={p.profileId}
              profile={p}
              active={p.profileId === selectedId}
              baseline={p.profileId === baselineId}
              now={now}
              onOpen={() => setSelectedId(p.profileId)}
              onBaseline={() => setBaselineId(p.profileId === baselineId ? null : p.profileId)}
              onDelete={() => void remove(p.profileId)}
            />
          ))}
        </div>
      ) : (
        <EmptyState icon={<Cpu size={22} />} title="Профилей пока нет">
          JFR записывается в целевой JVM через её FlightRecorderMXBean (без RecordingStream в анализаторе) и
          переносится сюда потоком: CPU Time (ExecutionSample), Wall-Clock (ThreadPark, JavaMonitorEnter/Wait,
          SocketRead, FileRead) и аллокации — за одну запись.
        </EmptyState>
      )}

      {profile && profile.status !== 'READY' && (
        <div className="flex items-center gap-2 rounded-md border border-kraken-border bg-kraken-panel px-3 py-3 text-xs text-kraken-muted">
          {profile.status === 'FAILED'
            ? <><AlertTriangle size={14} className="text-accent-coral" /> {profile.errorMessage}</>
            : <><Loader2 size={14} className="animate-spin text-accent-amber" /> {profile.progressMessage ?? profile.status}</>}
        </div>
      )}

      {ready && (
        <>
          <div className="rounded-md border border-kraken-border bg-kraken-panel p-3">
            <div className="mb-2 flex flex-wrap items-center gap-3">
              <span className="text-xs font-semibold text-kraken-text">Timeline</span>
              <span className="font-mono text-2xs text-kraken-subtle">
                {formatMs(ready.recordedMs)} · интервал сэмплирования {ready.samplingIntervalMs} мс
                {ready.truncated && <span className="text-accent-amber"> · обрезан по max-samples</span>}
              </span>
              <div className="ml-auto flex items-center gap-2">
                {selection && (
                  <FilterChip onClear={() => setSelection(null)}>
                    {formatMs(selection.from)} – {formatMs(selection.to)} ({formatMs(selection.to - selection.from)})
                  </FilterChip>
                )}
                {threadName && <FilterChip onClear={() => setThread(null)}>поток {threadName}</FilterChip>}
              </div>
            </div>
            <ErrorLine message={timeline.error} />
            {timeline.data ? (
              <ProfileTimeline
                timeline={timeline.data}
                selection={selection}
                onSelect={setSelection}
                thread={thread}
                onThread={setThread}
              />
            ) : timeline.loading && <Loading />}
          </div>

          <div className="rounded-md border border-kraken-border bg-kraken-panel">
            <div className="flex flex-wrap items-center gap-1 border-b border-kraken-border px-2 pt-2">
              {TABS.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setTab(t.id)}
                  className={clsx(
                    '-mb-px flex items-center gap-1.5 rounded-t-md border border-b-0 px-3 py-1.5 text-xs font-medium',
                    tab === t.id
                      ? 'border-kraken-border bg-kraken-bg text-accent-cyan'
                      : 'border-transparent text-kraken-muted hover:text-kraken-text',
                  )}
                >
                  {t.icon} {t.label}
                </button>
              ))}
              <div className="ml-auto pb-1.5">
                <Segmented<ProfileMetric>
                  value={metric}
                  onChange={setMetric}
                  options={(['CPU', 'WALL', 'ALLOC'] as ProfileMetric[]).map((m) => ({
                    value: m, label: METRIC_LABEL[m], title: METRIC_HINT[m], disabled: !ready.metrics.includes(m),
                  }))}
                />
              </div>
            </div>

            <div className="p-3">
              {tab === 'flame' && (
                <>
                  <ErrorLine message={flame.error} />
                  {flame.data && flameRoot ? (
                    <>
                      <div className="mb-2 font-mono text-2xs text-kraken-subtle">
                        {formatValue(flame.data.totalValue, flame.data.unit)} · {formatNumber(flame.data.totalSamples)} сэмпл./событий
                        {flame.data.prunedNodes > 0 && ` · скрыто мелких фреймов: ${formatNumber(flame.data.prunedNodes)}`}
                        {' · '}клик — зум, ПКМ — Butterfly
                      </div>
                      <FlameGraphView
                        root={flameRoot}
                        unit={flame.data.unit}
                        onPick={(item) => openMethod(item.className, item.methodName)}
                      />
                    </>
                  ) : flame.loading && <Loading />}
                </>
              )}

              {tab === 'diff' && (
                <>
                  <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-kraken-muted">
                    <span>Baseline:</span>
                    <select
                      value={baselineId ?? ''}
                      onChange={(e) => setBaselineId(e.target.value || null)}
                      className="h-7 rounded border border-kraken-border bg-kraken-bg px-1.5 text-xs text-kraken-text"
                    >
                      <option value="">— выберите —</option>
                      {profiles.filter((p) => p.status === 'READY' && p.profileId !== ready.profileId).map((p) => (
                        <option key={p.profileId} value={p.profileId}>{p.profileId} · {p.displayName}</option>
                      ))}
                    </select>
                    <span>→ Target: <span className="font-mono text-accent-cyan">{ready.profileId}</span></span>
                    <span className="text-2xs text-kraken-subtle">(сравнение по всей записи, без фильтра таймлайна)</span>
                  </div>
                  <ErrorLine message={diff.error} />
                  {!baseline || baseline.profileId === ready.profileId ? (
                    <div className="p-4 text-xs text-kraken-subtle">
                      Выберите baseline: например, профиль до изменения. Ширина фрейма — доля в target, цвет — изменение
                      доли: красный — регрессия, зелёный — улучшение.
                    </div>
                  ) : diff.data && diffRoot ? (
                    <>
                      <div className="mb-2 font-mono text-2xs text-kraken-subtle">
                        baseline {formatValue(diff.data.baselineTotal, diff.data.unit)} → target {formatValue(diff.data.targetTotal, diff.data.unit)}
                      </div>
                      <FlameGraphView root={diffRoot} unit={diff.data.unit} differential />
                    </>
                  ) : diff.loading && <Loading />}
                </>
              )}

              {tab === 'hotspots' && (
                <>
                  <ErrorLine message={hotspots.error ?? butterfly.error} />
                  <HotspotsButterflyPanel
                    hotspots={hotspots.data}
                    unit={unit}
                    selected={method}
                    onSelect={setMethod}
                    butterfly={butterfly.data}
                    butterflyLoading={butterfly.loading}
                  />
                </>
              )}

              {tab === 'subsystems' && (
                <>
                  <ErrorLine message={subsystems.error} />
                  {subsystems.data ? (
                    <SubsystemsPanel breakdown={subsystems.data} onOpenMethod={openMethod} />
                  ) : subsystems.loading && <Loading />}
                </>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function FilterChip({ children, onClear }: { children: ReactNode; onClear(): void }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full border border-accent-cyan/50 bg-accent-cyan/10 px-2 py-0.5 font-mono text-2xs text-accent-cyan">
      {children}
      <button type="button" onClick={onClear} className="hover:text-kraken-text" title="Сбросить">
        <X size={11} />
      </button>
    </span>
  );
}
