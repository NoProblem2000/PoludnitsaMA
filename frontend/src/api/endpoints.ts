import { http } from './http';
import type {
  ActiveProcessDto,
  ClassStatsDto,
  DiskSpaceCheckDto,
  GcTriggerResultDto,
  InspectionFindingDto,
  JvmProcessDto,
  LiveMetricsPayloadDto,
  MemoryGroup,
  MetricsPointDto,
  PageDto,
  ReportExportRequestDto,
  SnapshotSummaryDto,
} from './types';

export type ClassSort = 'RETAINED' | 'SHALLOW' | 'INSTANCES' | 'NAME';

const encode = encodeURIComponent;

/** Typed REST calls of the backend (see ProcessControlController, SnapshotController, ReportController). */
export const api = {
  processes: {
    list: () => http.get<JvmProcessDto[]>('/api/processes'),
    connect: (pid: number) => http.post<JvmProcessDto>(`/api/processes/${pid}/connect`),
    active: () => http.get<ActiveProcessDto>('/api/processes/active'),
    select: (pid: number) => http.put<ActiveProcessDto>('/api/processes/active', { pid }),
    clearSelection: () => http.del<ActiveProcessDto>('/api/processes/active'),
    latestMetrics: (pid: number) => http.get<LiveMetricsPayloadDto | undefined>(`/api/processes/${pid}/metrics`),
    metricsHistory: (pid: number) => http.get<MetricsPointDto[]>(`/api/processes/${pid}/metrics/history`),
    gc: (pid: number) => http.post<GcTriggerResultDto>(`/api/processes/${pid}/gc`, { confirm: true }),
  },
  snapshots: {
    list: () => http.get<SnapshotSummaryDto[]>('/api/snapshots'),
    get: (id: string) => http.get<SnapshotSummaryDto>(`/api/snapshots/${encode(id)}`),
    diskCheck: (pid: number) => http.get<DiskSpaceCheckDto>(`/api/snapshots/disk-check?pid=${pid}`),
    histogram: (pid: number) => http.post<SnapshotSummaryDto>('/api/snapshots/histogram', { pid }),
    deep: (pid: number, liveObjectsOnly: boolean) =>
      http.post<SnapshotSummaryDto>('/api/snapshots/deep', { pid, liveObjectsOnly }),
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
  },
  reports: {
    export: (format: 'JSON' | 'HTML', request: ReportExportRequestDto) =>
      http.download(`/api/reports/export?format=${format}`, request,
        format === 'JSON' ? 'jvm-memory-report.json.gz' : 'jvm-memory-report.html'),
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
