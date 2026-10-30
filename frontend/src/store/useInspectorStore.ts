import { create } from 'zustand';
import { api, fetchAllClasses } from '@/api/endpoints';
import { ApiError, errorMessage, saveBlob } from '@/api/http';
import { LiveSocket, type SocketState } from '@/api/liveSocket';
import { readReportFile } from '@/api/offlineReport';
import type {
  ClassStatsDto,
  ConnectionState,
  DiskSpaceCheckDto,
  GcTriggerResultDto,
  InspectionFindingDto,
  JvmProcessDto,
  LiveMetricsPayloadDto,
  LiveStreamMessageDto,
  SessionReportBundle,
  SnapshotSummaryDto,
} from '@/api/types';
import { formatBytes, formatDuration, formatNumber } from '@/utils/format';
import {
  appendWindowed,
  type ChartPoint,
  type GcMarker,
  gcMarkersFromPayload,
  LIVE_WINDOW_MS,
  pointFromHistory,
  pointFromPayload,
} from './liveSeries';

export type Mode = 'live' | 'offline';
export type ViewId = 'live' | 'heuristics' | 'dominators' | 'diff';
/** safe: {@code dumpHeap(live=false)}, no Full GC; exact: {@code live=true}, Full GC first. */
export type DumpMode = 'safe' | 'exact';
export type StreamState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline';
export type ToastTone = 'info' | 'success' | 'warning' | 'error';

export interface Toast {
  id: number;
  tone: ToastTone;
  title: string;
  message?: string;
}

export interface FindingCounts {
  critical: number;
  warning: number;
  info: number;
}

interface Busy {
  histogram: boolean;
  deep: boolean;
  gc: boolean;
  export: boolean;
  report: boolean;
}

export interface InspectorState {
  mode: Mode;
  view: ViewId;

  processes: JvmProcessDto[];
  processesLoading: boolean;
  processesError: string | null;
  activePid: number | null;

  streamState: StreamState;
  targetState: ConnectionState | null;
  targetMessage: string | null;
  latest: LiveMetricsPayloadDto | null;
  /** ring buffer of the last {@link LIVE_WINDOW_MS} (live) or the whole report history (offline) */
  points: ChartPoint[];
  gcMarkers: GcMarker[];

  /** snapshots of the active process (live) or of the report (offline), oldest first */
  snapshots: SnapshotSummaryDto[];
  selectedSnapshotId: string | null;
  /** up to two snapshots picked for comparison, in pick order */
  compareIds: string[];

  /** every class of the latest quick histogram / snapshot, for the radar table */
  radarClasses: ClassStatsDto[];
  radarClassesSnapshotId: string | null;
  /** findings of the newest deep snapshot (live) or of the report (offline) */
  findings: InspectionFindingDto[];

  dumpMode: DumpMode;
  busy: Busy;
  offlineReport: SessionReportBundle | null;
  toasts: Toast[];

  init(): void;
  dispose(): void;
  refreshProcesses(): Promise<void>;
  selectProcess(pid: number | null): Promise<void>;
  setView(view: ViewId): void;
  setDumpMode(mode: DumpMode): void;
  quickHistogram(): Promise<void>;
  checkDiskSpace(): Promise<DiskSpaceCheckDto | null>;
  deepDump(): Promise<void>;
  forceGc(): Promise<GcTriggerResultDto | null>;
  exportReport(format: 'JSON' | 'HTML'): Promise<void>;
  openReportFile(file: File): Promise<void>;
  closeOfflineReport(): void;
  refreshSnapshots(): Promise<void>;
  selectSnapshot(id: string | null): void;
  toggleCompare(id: string): void;
  pushToast(toast: Omit<Toast, 'id'>): void;
  dismissToast(id: number): void;
}

const PROCESS_REFRESH_MS = 15_000;
const SNAPSHOT_POLL_MS = 1_500;
const TOAST_TTL_MS = 7_000;
const LAST_PID_KEY = 'jvm-inspector.last-pid';

let socket: LiveSocket | null = null;
let processTimer: number | undefined;
let snapshotTimer: number | undefined;
let toastSeq = 0;
/** statuses seen by the poller, to notice transitions to READY / FAILED */
const knownStatus = new Map<string, SnapshotSummaryDto['status']>();

function isFinished(snapshot: SnapshotSummaryDto): boolean {
  return snapshot.status === 'READY' || snapshot.status === 'FAILED';
}

function byCreation(a: SnapshotSummaryDto, b: SnapshotSummaryDto): number {
  return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
}

function rememberPid(pid: number | null): void {
  try {
    if (pid == null) {
      localStorage.removeItem(LAST_PID_KEY);
    } else {
      localStorage.setItem(LAST_PID_KEY, String(pid));
    }
  } catch {
    // storage blocked: only a convenience
  }
}

function recalledPid(): number | null {
  try {
    const value = localStorage.getItem(LAST_PID_KEY);
    return value ? Number(value) : null;
  } catch {
    return null;
  }
}

const emptyLive = {
  latest: null,
  points: [] as ChartPoint[],
  gcMarkers: [] as GcMarker[],
  snapshots: [] as SnapshotSummaryDto[],
  selectedSnapshotId: null,
  compareIds: [] as string[],
  radarClasses: [] as ClassStatsDto[],
  radarClassesSnapshotId: null,
  findings: [] as InspectionFindingDto[],
  targetState: null,
  targetMessage: null,
};

export const useInspectorStore = create<InspectorState>()((set, get) => {
  const toast = (tone: ToastTone, title: string, message?: string) => get().pushToast({ tone, title, message });

  const setBusy = (key: keyof Busy, value: boolean) => set((state) => ({ busy: { ...state.busy, [key]: value } }));

  function onSocketState(state: SocketState): void {
    if (get().mode === 'offline') {
      return;
    }
    const mapped: StreamState =
      state === 'open' ? (get().activePid != null ? 'live' : 'idle')
        : state === 'connecting' ? 'connecting'
          : state === 'reconnecting' ? 'reconnecting'
            : 'idle';
    set({ streamState: mapped });
  }

  function onSocketMessage(message: LiveStreamMessageDto): void {
    const state = get();
    if (state.mode === 'offline' || message.pid == null && message.type !== 'ERROR' && message.type !== 'PONG') {
      return;
    }
    switch (message.type) {
      case 'METRICS': {
        const payload = message.metrics;
        if (!payload || payload.pid !== state.activePid) {
          return;
        }
        set((current) => ({
          latest: payload,
          streamState: 'live',
          points: appendWindowed(current.points, [pointFromPayload(payload)], LIVE_WINDOW_MS),
          gcMarkers: appendWindowed(current.gcMarkers, gcMarkersFromPayload(payload), LIVE_WINDOW_MS),
        }));
        break;
      }
      case 'STATUS': {
        const status = message.status;
        if (!status || status.pid !== state.activePid) {
          return;
        }
        const previous = state.targetState;
        set({ targetState: status.state, targetMessage: status.message });
        if (status.state === 'TERMINATED' && previous !== 'TERMINATED') {
          toast('warning', `Процесс ${status.pid} завершился`, status.message ?? undefined);
          void get().refreshProcesses();
        } else if ((status.state === 'FAILED' || status.state === 'RECONNECTING') && previous === 'CONNECTED') {
          toast('warning', `Потеряна связь с PID ${status.pid}`, status.message ?? undefined);
        }
        break;
      }
      case 'ERROR':
        toast('error', 'Ошибка live-потока', message.error ?? undefined);
        break;
      default:
        break;
    }
  }

  function ensureSnapshotPolling(): void {
    const running = get().snapshots.some((snapshot) => !isFinished(snapshot));
    if (running && snapshotTimer === undefined) {
      snapshotTimer = window.setInterval(() => void get().refreshSnapshots(), SNAPSHOT_POLL_MS);
    } else if (!running && snapshotTimer !== undefined) {
      window.clearInterval(snapshotTimer);
      snapshotTimer = undefined;
    }
  }

  async function loadFindings(snapshots: SnapshotSummaryDto[]): Promise<void> {
    const newestDeep = [...snapshots].reverse().find((s) => s.kind === 'DEEP' && s.status === 'READY');
    if (!newestDeep) {
      set({ findings: [] });
      return;
    }
    try {
      const findings = await api.snapshots.findings(newestDeep.snapshotId);
      if (get().mode === 'live') {
        set({ findings });
      }
    } catch (error) {
      toast('error', 'Не удалось загрузить диагнозы', errorMessage(error));
    }
  }

  async function loadRadarClasses(snapshot: SnapshotSummaryDto): Promise<void> {
    try {
      const sort = snapshot.retainedSizesAvailable ? 'RETAINED' : 'SHALLOW';
      const classes = await fetchAllClasses(snapshot.snapshotId, sort);
      if (get().mode === 'live' && get().activePid === snapshot.sourcePid) {
        set({ radarClasses: classes, radarClassesSnapshotId: snapshot.snapshotId });
      }
    } catch (error) {
      toast('error', 'Не удалось загрузить классы снапшота', errorMessage(error));
    }
  }

  return {
    mode: 'live',
    view: 'live',
    processes: [],
    processesLoading: false,
    processesError: null,
    activePid: null,
    streamState: 'idle',
    ...emptyLive,
    dumpMode: 'safe',
    busy: { histogram: false, deep: false, gc: false, export: false, report: false },
    offlineReport: null,
    toasts: [],

    init() {
      if (!socket) {
        socket = new LiveSocket({ onMessage: onSocketMessage, onState: onSocketState });
      }
      void (async () => {
        await get().refreshProcesses();
        if (get().activePid != null || get().mode === 'offline') {
          return;
        }
        let pid: number | null = null;
        try {
          pid = (await api.processes.active()).pid;
        } catch {
          // backend down: refreshProcesses already reported it
        }
        pid ??= recalledPid();
        if (pid != null && get().processes.some((process) => process.pid === pid)) {
          await get().selectProcess(pid);
        }
      })();
      window.clearInterval(processTimer);
      processTimer = window.setInterval(() => {
        if (get().mode === 'live') {
          void get().refreshProcesses();
        }
      }, PROCESS_REFRESH_MS);
    },

    dispose() {
      window.clearInterval(processTimer);
      window.clearInterval(snapshotTimer);
      processTimer = undefined;
      snapshotTimer = undefined;
      socket?.close();
      socket = null;
    },

    async refreshProcesses() {
      set({ processesLoading: true });
      try {
        const processes = await api.processes.list();
        set({ processes, processesLoading: false, processesError: null });
      } catch (error) {
        set({ processesLoading: false, processesError: errorMessage(error) });
      }
    },

    async selectProcess(pid) {
      if (get().mode === 'offline') {
        set({ mode: 'live', offlineReport: null });
      }
      knownStatus.clear();
      set({ activePid: pid, ...emptyLive, streamState: pid == null ? 'idle' : 'connecting' });
      rememberPid(pid);
      if (pid == null) {
        socket?.unsubscribe();
        void api.processes.clearSelection().catch(() => undefined);
        return;
      }
      socket?.subscribe(pid);
      try {
        await api.processes.select(pid);
      } catch (error) {
        toast('error', `Не удалось выбрать PID ${pid}`, errorMessage(error));
      }
      try {
        const history = await api.processes.metricsHistory(pid);
        const cutoff = Date.now() - LIVE_WINDOW_MS;
        const prefill = history.filter((point) => point.timestampEpochMs >= cutoff).map(pointFromHistory);
        if (get().activePid === pid && get().points.length === 0) {
          set({ points: prefill });
        } else if (get().activePid === pid) {
          const firstLive = get().points[0]?.t ?? Number.MAX_SAFE_INTEGER;
          set({ points: prefill.filter((point) => point.t < firstLive).concat(get().points) });
        }
      } catch {
        // history is a nicety; the stream fills the chart anyway
      }
      await get().refreshSnapshots();
    },

    setView(view) {
      set({ view });
    },

    setDumpMode(dumpMode) {
      set({ dumpMode });
    },

    async quickHistogram() {
      const pid = get().activePid;
      if (pid == null || get().busy.histogram) {
        return;
      }
      setBusy('histogram', true);
      try {
        // The background leak radar takes histograms too; the backend spaces them by 2 s, so wait it out.
        let summary: SnapshotSummaryDto | null = null;
        for (let attempt = 0; summary == null; attempt++) {
          try {
            summary = await api.snapshots.histogram(pid);
          } catch (error) {
            const spacing = error instanceof ApiError
              && (error.code === 'HISTOGRAM_RATE_LIMITED' || error.code === 'HISTOGRAM_IN_PROGRESS');
            if (!spacing || attempt >= 4) {
              throw error;
            }
            await new Promise((resolve) => window.setTimeout(resolve, 1_200));
          }
        }
        set((state) => ({ snapshots: [...state.snapshots, summary].sort(byCreation), selectedSnapshotId: summary.snapshotId }));
        await loadRadarClasses(summary);
        toast('success', 'Quick Histogram готова',
          `${formatNumber(summary.totalClasses)} классов, ${formatBytes(summary.totalHeapBytes)}; пауза ${formatDuration(summary.dumpDurationMs)}`);
      } catch (error) {
        toast('error', 'Quick Histogram не снята', errorMessage(error));
      } finally {
        setBusy('histogram', false);
      }
    },

    async checkDiskSpace() {
      const pid = get().activePid;
      if (pid == null) {
        return null;
      }
      try {
        return await api.snapshots.diskCheck(pid);
      } catch (error) {
        toast('error', 'Не удалось проверить свободное место', errorMessage(error));
        return null;
      }
    },

    async deepDump() {
      const pid = get().activePid;
      if (pid == null || get().busy.deep) {
        return;
      }
      setBusy('deep', true);
      try {
        const summary = await api.snapshots.deep(pid, get().dumpMode === 'exact');
        knownStatus.set(summary.snapshotId, summary.status);
        set((state) => ({ snapshots: [...state.snapshots, summary].sort(byCreation), selectedSnapshotId: summary.snapshotId }));
        ensureSnapshotPolling();
        toast('info', 'Deep Heap Dump запущен', 'Дамп пишется на целевой JVM, затем анализ и удаление .hprof');
      } catch (error) {
        toast('error', 'Deep Heap Dump не запущен', errorMessage(error));
      } finally {
        setBusy('deep', false);
      }
    },

    async forceGc() {
      const pid = get().activePid;
      if (pid == null || get().busy.gc) {
        return null;
      }
      setBusy('gc', true);
      try {
        const result = await api.processes.gc(pid);
        toast(result.explicitGcDisabled ? 'warning' : 'success',
          `GC: ${formatBytes(result.heapUsedBefore)} → ${formatBytes(result.heapUsedAfter)} за ${formatDuration(result.durationMs)}`,
          result.warning);
        return result;
      } catch (error) {
        toast('error', 'Force GC не выполнен', errorMessage(error));
        return null;
      } finally {
        setBusy('gc', false);
      }
    },

    async exportReport(format) {
      if (get().busy.export) {
        return;
      }
      setBusy('export', true);
      try {
        const pid = get().activePid;
        const download = await api.reports.export(format, { pid: pid ?? null, includeMetricsHistory: true });
        saveBlob(download);
        toast('success', `Отчёт ${format} сохранён`, download.fileName);
      } catch (error) {
        toast('error', 'Экспорт не удался', errorMessage(error));
      } finally {
        setBusy('export', false);
      }
    },

    async openReportFile(file) {
      setBusy('report', true);
      try {
        const report = await readReportFile(file);
        socket?.unsubscribe();
        window.clearInterval(snapshotTimer);
        snapshotTimer = undefined;
        const snapshots = report.snapshots.map((s) => s.summary).sort(byCreation);
        const newest = [...report.snapshots]
          .sort((a, b) => byCreation(a.summary, b.summary))
          .reverse()
          .find((s) => s.data != null);
        set({
          mode: 'offline',
          offlineReport: report,
          streamState: 'offline',
          ...emptyLive,
          activePid: report.process?.pid ?? null,
          points: report.metricsHistory.map(pointFromHistory),
          snapshots,
          selectedSnapshotId: newest?.summary.snapshotId ?? null,
          radarClasses: newest?.data.classes ?? [],
          radarClassesSnapshotId: newest?.summary.snapshotId ?? null,
          findings: report.findings,
        });
        toast('success', 'Офлайн-отчёт открыт',
          `${report.title}: ${snapshots.length} снапшотов, ${report.findings.length} диагнозов`);
      } catch (error) {
        toast('error', 'Не удалось открыть отчёт', errorMessage(error));
      } finally {
        setBusy('report', false);
      }
    },

    closeOfflineReport() {
      set({ mode: 'live', offlineReport: null, ...emptyLive, activePid: null, streamState: 'idle' });
      const pid = recalledPid();
      void get().refreshProcesses().then(() => {
        if (pid != null && get().processes.some((process) => process.pid === pid)) {
          void get().selectProcess(pid);
        }
      });
    },

    async refreshSnapshots() {
      const pid = get().activePid;
      if (pid == null || get().mode === 'offline') {
        return;
      }
      try {
        const all = await api.snapshots.list();
        if (get().activePid !== pid || get().mode === 'offline') {
          return;
        }
        const snapshots = all.filter((snapshot) => snapshot.sourcePid === pid).sort(byCreation);
        let deepFinished = false;
        for (const snapshot of snapshots) {
          const before = knownStatus.get(snapshot.snapshotId);
          if (before && !['READY', 'FAILED'].includes(before) && isFinished(snapshot)) {
            if (snapshot.status === 'READY') {
              deepFinished ||= snapshot.kind === 'DEEP';
              toast('success', 'Deep Heap Dump проанализирован',
                `${formatBytes(snapshot.totalHeapBytes)}, пауза дампа ${formatDuration(snapshot.dumpDurationMs)};`
                + ` сырой .hprof ${snapshot.rawDumpDeleted ? 'удалён' : 'ещё удаляется'}`);
            } else {
              toast('error', 'Deep Heap Dump не удался', snapshot.errorMessage ?? undefined);
            }
          }
          knownStatus.set(snapshot.snapshotId, snapshot.status);
        }
        set({ snapshots });
        ensureSnapshotPolling();
        if (deepFinished || get().findings.length === 0) {
          await loadFindings(snapshots);
        }
        const newestReady = [...snapshots].reverse().find((s) => s.status === 'READY');
        if (newestReady && newestReady.snapshotId !== get().radarClassesSnapshotId
            && (deepFinished || get().radarClasses.length === 0)) {
          await loadRadarClasses(newestReady);
        }
      } catch (error) {
        toast('error', 'Не удалось обновить список снапшотов', errorMessage(error));
      }
    },

    selectSnapshot(id) {
      set({ selectedSnapshotId: id });
    },

    toggleCompare(id) {
      set((state) => {
        if (state.compareIds.includes(id)) {
          return { compareIds: state.compareIds.filter((existing) => existing !== id) };
        }
        return { compareIds: [...state.compareIds, id].slice(-2) };
      });
    },

    pushToast(input) {
      const id = ++toastSeq;
      set((state) => ({ toasts: [...state.toasts, { ...input, id }].slice(-5) }));
      window.setTimeout(() => get().dismissToast(id), TOAST_TTL_MS);
    },

    dismissToast(id) {
      set((state) => ({ toasts: state.toasts.filter((item) => item.id !== id) }));
    },
  };
});

/** Active process descriptor, if listed. */
export function selectActiveProcess(state: InspectorState): JvmProcessDto | null {
  if (state.mode === 'offline') {
    return state.offlineReport?.process ?? null;
  }
  return state.processes.find((process) => process.pid === state.activePid) ?? null;
}

export function countFindings(findings: readonly InspectionFindingDto[]): FindingCounts {
  const counts: FindingCounts = { critical: 0, warning: 0, info: 0 };
  for (const finding of findings) {
    if (finding.severity === 'CRITICAL') {
      counts.critical++;
    } else if (finding.severity === 'WARNING') {
      counts.warning++;
    } else {
      counts.info++;
    }
  }
  return counts;
}

/** Baseline (older) and current (newer) of the comparison pair. */
export function comparePair(state: InspectorState): [SnapshotSummaryDto, SnapshotSummaryDto] | null {
  if (state.compareIds.length !== 2) {
    return null;
  }
  const picked = state.snapshots.filter((s) => state.compareIds.includes(s.snapshotId)).sort(byCreation);
  return picked.length === 2 && picked[0] && picked[1] ? [picked[0], picked[1]] : null;
}
