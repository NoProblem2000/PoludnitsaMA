import { useEffect, useMemo, useState } from 'react';
import { api, fetchAllClasses } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type {
  ClassLoaderStatsDto,
  ClassStatsDto,
  DominatorNodeDto,
  InspectionFindingDto,
  InspectionRuleRunDto,
  MemoryGroupStatsDto,
  SnapshotSummaryDto,
} from '@/api/types';
import { useInspectorStore } from './useInspectorStore';

/** What the deep-analysis screens read from one READY deep snapshot. */
export interface DeepAnalysis {
  dominators: DominatorNodeDto[];
  findings: InspectionFindingDto[];
  groups: MemoryGroupStatsDto[];
  inspections: InspectionRuleRunDto[];
  classLoaders: ClassLoaderStatsDto[];
}

export interface DeepAnalysisState {
  loading: boolean;
  error: string | null;
  data: DeepAnalysis | null;
}

// A READY snapshot never changes, so its analysis is fetched once per session.
const MAX_CACHED = 8;
const cache = new Map<string, Promise<DeepAnalysis>>();

function fetchAnalysis(id: string): Promise<DeepAnalysis> {
  let pending = cache.get(id);
  if (!pending) {
    pending = Promise.all([
      api.snapshots.dominators(id),
      api.snapshots.findings(id),
      api.snapshots.groups(id),
      api.snapshots.inspections(id),
      api.snapshots.classLoaders(id),
    ]).then(([dominators, findings, groups, inspections, classLoaders]) =>
      ({ dominators, findings, groups, inspections, classLoaders }));
    pending.catch(() => cache.delete(id));
    cache.set(id, pending);
    while (cache.size > MAX_CACHED) {
      const oldest = cache.keys().next().value;
      if (oldest === undefined) {
        break;
      }
      cache.delete(oldest);
    }
  }
  return pending;
}

export function isReadyDeep(snapshot: SnapshotSummaryDto): boolean {
  return snapshot.kind === 'DEEP' && snapshot.status === 'READY';
}

/**
 * The deep snapshot the analysis screens show: the one selected on the timeline when it is a ready deep snapshot,
 * otherwise the newest ready one.
 */
export function useChosenDeepSnapshot(): { choices: SnapshotSummaryDto[]; chosen: SnapshotSummaryDto | null } {
  const snapshots = useInspectorStore((s) => s.snapshots);
  const selectedId = useInspectorStore((s) => s.selectedSnapshotId);
  return useMemo(() => {
    const choices = snapshots.filter(isReadyDeep);
    const chosen = choices.find((s) => s.snapshotId === selectedId) ?? choices[choices.length - 1] ?? null;
    return { choices, chosen };
  }, [snapshots, selectedId]);
}

/** Dominators, findings and groups of a deep snapshot: from the open report offline, from the backend live. */
export function useDeepAnalysis(snapshot: SnapshotSummaryDto | null): DeepAnalysisState {
  const report = useInspectorStore((s) => s.offlineReport);
  const id = snapshot?.snapshotId ?? null;
  const offline = useMemo<DeepAnalysis | null>(() => {
    const data = report?.snapshots.find((s) => s.summary.snapshotId === id)?.data;
    return data
      ? {
        dominators: data.dominators,
        findings: data.findings,
        groups: data.groups,
        inspections: data.inspections,
        // reports written before class loaders existed have none
        classLoaders: data.classLoaders ?? [],
      }
      : null;
  }, [report, id]);
  // Keyed by snapshot: until the analysis of the current id arrives it is loading, never "empty".
  const [state, setState] = useState<{ id: string; error: string | null; data: DeepAnalysis | null } | null>(null);

  useEffect(() => {
    if (report || id == null) {
      return undefined;
    }
    let current = true;
    fetchAnalysis(id).then(
      (data) => current && setState({ id, error: null, data }),
      (error: unknown) => current && setState({ id, error: errorMessage(error), data: null }),
    );
    return () => {
      current = false;
    };
  }, [report, id]);

  if (report) {
    return { loading: false, error: offline || id == null ? null : 'В отчёте нет данных этого снапшота', data: offline };
  }
  if (id == null) {
    return { loading: false, error: null, data: null };
  }
  return state?.id === id ? { loading: false, error: state.error, data: state.data } : { loading: true, error: null, data: null };
}

// Classes of a snapshot are tens of thousands of rows: loaded only by the views that group them.
const classCache = new Map<string, Promise<ClassStatsDto[]>>();

/** Every class of a snapshot (by retained size): from the report offline, paged from the backend live. */
export function useSnapshotClasses(snapshot: SnapshotSummaryDto | null): {
  loading: boolean;
  error: string | null;
  classes: ClassStatsDto[];
} {
  const report = useInspectorStore((s) => s.offlineReport);
  const id = snapshot?.snapshotId ?? null;
  const offline = useMemo(() => report?.snapshots.find((s) => s.summary.snapshotId === id)?.data.classes ?? null,
    [report, id]);
  const [state, setState] = useState<{ id: string; classes: ClassStatsDto[]; error: string | null } | null>(null);

  useEffect(() => {
    if (report || id == null) {
      return undefined;
    }
    let current = true;
    let pending = classCache.get(id);
    if (!pending) {
      pending = fetchAllClasses(id, 'RETAINED');
      pending.catch(() => classCache.delete(id));
      classCache.set(id, pending);
      while (classCache.size > 4) {
        const oldest = classCache.keys().next().value;
        if (oldest === undefined) {
          break;
        }
        classCache.delete(oldest);
      }
    }
    pending.then(
      (classes) => current && setState({ id, classes, error: null }),
      (error: unknown) => current && setState({ id, classes: [], error: errorMessage(error) }),
    );
    return () => {
      current = false;
    };
  }, [report, id]);

  if (report) {
    return { loading: false, error: null, classes: offline ?? [] };
  }
  if (id == null) {
    return { loading: false, error: null, classes: [] };
  }
  return state?.id === id ? { loading: false, error: state.error, classes: state.classes }
    : { loading: true, error: null, classes: [] };
}
