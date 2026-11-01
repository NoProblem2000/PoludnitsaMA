import { http } from './http';
import type {
  AboutDto,
  ActiveProcessDto,
  AllocationStacksDto,
  ButterflyDto,
  ClassLoaderStatsDto,
  ClassStatsDto,
  DiffFlameGraphDto,
  DiskSpaceCheckDto,
  DominatorNodeDto,
  FlameGraphDto,
  GcRootPathDto,
  GcTriggerResultDto,
  HotspotDto,
  ImportedReportDto,
  InspectionFindingDto,
  InspectionRuleRunDto,
  JfrAllocationStatsDto,
  JvmProcessDto,
  LiveMetricsPayloadDto,
  MemoryGroup,
  MemoryGroupStatsDto,
  MetricsPointDto,
  OqlQueryRequestDto,
  OqlResultDto,
  PageDto,
  PathMode,
  ProfileMetric,
  ProfileRequestDto,
  ProfileSummaryDto,
  ProfilerCapabilitiesDto,
  ReportExportRequestDto,
  SnapshotDiffDto,
  SnapshotSummaryDto,
  SubsystemBreakdownDto,
  TimelineDto,
} from './types';

export type ClassSort = 'RETAINED' | 'SHALLOW' | 'INSTANCES' | 'NAME';

/** Cross-filter of the call tree queries: a brushed interval (ms from the profile start) and a thread. */
export interface ProfileWindow {
  from?: number | null;
  to?: number | null;
  thread?: number | null;
}

const encode = encodeURIComponent;

function windowQuery(metric: ProfileMetric, window: ProfileWindow, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams({ metric, ...extra });
  if (window.from != null) {
    params.set('from', window.from.toFixed(1));
  }
  if (window.to != null) {
    params.set('to', window.to.toFixed(1));
  }
  if (window.thread != null) {
    params.set('thread', String(window.thread));
  }
  return params.toString();
}

/** Typed REST calls of the backend (see ProcessControlController, SnapshotController, ReportController). */
export const api = {
  about: {
    get: () => http.get<AboutDto>('/api/about'),
    license: () => http.text('/api/about/license'),
    notice: () => http.text('/api/about/notice'),
  },
  processes: {
    list: () => http.get<JvmProcessDto[]>('/api/processes'),
    connect: (pid: number) => http.post<JvmProcessDto>(`/api/processes/${pid}/connect`),
    active: () => http.get<ActiveProcessDto>('/api/processes/active'),
    select: (pid: number) => http.put<ActiveProcessDto>('/api/processes/active', { pid }),
    clearSelection: () => http.del<ActiveProcessDto>('/api/processes/active'),
    latestMetrics: (pid: number) => http.get<LiveMetricsPayloadDto | undefined>(`/api/processes/${pid}/metrics`),
    metricsHistory: (pid: number) => http.get<MetricsPointDto[]>(`/api/processes/${pid}/metrics/history`),
    gc: (pid: number) => http.post<GcTriggerResultDto>(`/api/processes/${pid}/gc`, { confirm: true }),
    startJfr: (pid: number) => http.post<JfrAllocationStatsDto>(`/api/processes/${pid}/jfr`),
    stopJfr: (pid: number) => http.del<JfrAllocationStatsDto>(`/api/processes/${pid}/jfr`),
    /** who allocates a class, from the JFR profile */
    allocationStacks: (pid: number, className: string) =>
      http.get<AllocationStacksDto>(`/api/processes/${pid}/jfr/allocations?className=${encode(className)}`),
  },
  snapshots: {
    list: () => http.get<SnapshotSummaryDto[]>('/api/snapshots'),
    get: (id: string) => http.get<SnapshotSummaryDto>(`/api/snapshots/${encode(id)}`),
    diskCheck: (pid: number) => http.get<DiskSpaceCheckDto>(`/api/snapshots/disk-check?pid=${pid}`),
    histogram: (pid: number) => http.post<SnapshotSummaryDto>('/api/snapshots/histogram', { pid }),
    deep: (pid: number, liveObjectsOnly: boolean, keepForQueries = false) =>
      http.post<SnapshotSummaryDto>('/api/snapshots/deep', { pid, liveObjectsOnly, keepForQueries }),
    classes: (id: string, params: { sort?: ClassSort; group?: MemoryGroup; offset?: number; limit?: number }) => {
      const query = new URLSearchParams();
      query.set('sort', params.sort ?? 'RETAINED');
      query.set('offset', String(params.offset ?? 0));
      query.set('limit', String(params.limit ?? 1000));
      if (params.group) {
        query.set('group', params.group);
      }
      return http.get<PageDto<ClassStatsDto>>(`/api/snapshots/${encode(id)}/classes?${query.toString()}`);
    },
    findings: (id: string) => http.get<InspectionFindingDto[]>(`/api/snapshots/${encode(id)}/findings`),
    delete: (id: string) => http.del<void>(`/api/snapshots/${encode(id)}`),
    dominators: (id: string) => http.get<DominatorNodeDto[]>(`/api/snapshots/${encode(id)}/dominators`),
    groups: (id: string) => http.get<MemoryGroupStatsDto[]>(`/api/snapshots/${encode(id)}/groups`),
    classLoaders: (id: string) => http.get<ClassLoaderStatsDto[]>(`/api/snapshots/${encode(id)}/classloaders`),
    /** OQL against a dump kept open (keepForQueries) */
    oql: (id: string, request: OqlQueryRequestDto) =>
      http.post<OqlResultDto>(`/api/snapshots/${encode(id)}/oql`, request),
    /** shortest GC root path of any object of an open dump */
    path: (id: string, objectId: number, mode: PathMode) =>
      http.get<GcRootPathDto>(`/api/snapshots/${encode(id)}/paths/${objectId}?mode=${mode}`),
    closeHeap: (id: string) => http.del<void>(`/api/snapshots/${encode(id)}/heap`),
    inspections: (id: string) => http.get<InspectionRuleRunDto[]>(`/api/snapshots/${encode(id)}/inspections`),
    /** Growing classes first; {@code includeShrinking} also returns the freed ones. */
    diff: (baselineId: string, currentId: string, limit = 5000, includeShrinking = true) =>
      http.get<SnapshotDiffDto>(`/api/snapshots/diff?baseline=${encode(baselineId)}&current=${encode(currentId)}`
        + `&limit=${limit}&includeShrinking=${includeShrinking}`),
  },
  profiles: {
    capabilities: () => http.get<ProfilerCapabilitiesDto>('/api/profiles/capabilities'),
    list: () => http.get<ProfileSummaryDto[]>('/api/profiles'),
    start: (request: ProfileRequestDto) => http.post<ProfileSummaryDto>('/api/profiles', request),
    get: (id: string) => http.get<ProfileSummaryDto>(`/api/profiles/${encode(id)}`),
    delete: (id: string) => http.del<void>(`/api/profiles/${encode(id)}`),
    timeline: (id: string, buckets: number) =>
      http.get<TimelineDto>(`/api/profiles/${encode(id)}/timeline?buckets=${buckets}`),
    flame: (id: string, metric: ProfileMetric, window: ProfileWindow) =>
      http.get<FlameGraphDto>(`/api/profiles/${encode(id)}/flame?${windowQuery(metric, window)}`),
    /** widths follow the target, colours the change of share */
    diffFlame: (baselineId: string, targetId: string, metric: ProfileMetric) =>
      http.get<DiffFlameGraphDto>(`/api/profiles/diff/flame?baseline=${encode(baselineId)}&target=${encode(targetId)}&metric=${metric}`),
    hotspots: (id: string, metric: ProfileMetric, window: ProfileWindow, limit = 200) =>
      http.get<HotspotDto[]>(`/api/profiles/${encode(id)}/hotspots?${windowQuery(metric, window, { limit: String(limit) })}`),
    butterfly: (id: string, className: string, method: string, metric: ProfileMetric, window: ProfileWindow) =>
      http.get<ButterflyDto>(`/api/profiles/${encode(id)}/butterfly?${windowQuery(metric, window, { className, method })}`),
    subsystems: (id: string, metric: ProfileMetric, window: ProfileWindow) =>
      http.get<SubsystemBreakdownDto>(`/api/profiles/${encode(id)}/subsystems?${windowQuery(metric, window)}`),
  },
  reports: {
    export: (format: 'JSON' | 'HTML', request: ReportExportRequestDto) =>
      http.download(`/api/reports/export?format=${format}`, request,
        format === 'JSON' ? 'jvm-memory-report.json.gz' : 'jvm-memory-report.html'),
    /** Registers a report file's snapshots in the backend session (multipart part "file"). */
    import: (file: File) => http.upload<ImportedReportDto>('/api/reports/import', 'file', file),
  },
};

/** Reads every page of a snapshot's classes, up to {@code max} rows. */
export async function fetchAllClasses(id: string, sort: ClassSort, max = 20_000): Promise<ClassStatsDto[]> {
  const pageSize = 1000;
  const rows: ClassStatsDto[] = [];
  for (let offset = 0; offset < max; offset += pageSize) {
    const page = await api.snapshots.classes(id, { sort, offset, limit: pageSize });
    rows.push(...page.items);
    if (page.items.length < pageSize || rows.length >= page.total) {
      break;
    }
  }
  return rows;
}
