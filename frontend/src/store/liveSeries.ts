import type { GcGeneration, LiveMetricsPayloadDto, MemoryPoolKind, MetricsPointDto } from '@/api/types';

/** Window of the live charts. */
export const LIVE_WINDOW_MS = 300_000;

/** One second of memory, flattened for ECharts. {@code null}: not known (offline history has no Eden/Survivor). */
export interface ChartPoint {
  t: number;
  heapUsed: number;
  heapCommitted: number;
  heapMax: number;
  eden: number | null;
  survivor: number | null;
  old: number | null;
  oldAfterGc: number | null;
  metaspace: number | null;
  gcOverheadPercent: number;
  allocationRate: number | null;
}

/** Collections observed between two samples. */
export interface GcMarker {
  t: number;
  collector: string;
  generation: GcGeneration;
  pauseMs: number;
  collections: number;
  stopTheWorld: boolean;
}

function poolSum(payload: LiveMetricsPayloadDto, kind: MemoryPoolKind): number | null {
  let sum: number | null = null;
  for (const pool of payload.pools) {
    if (pool.kind === kind) {
      sum = (sum ?? 0) + pool.used;
    }
  }
  return sum;
}

export function pointFromPayload(payload: LiveMetricsPayloadDto): ChartPoint {
  const oldPool = payload.pools.find((pool) => pool.kind === 'OLD');
  return {
    t: payload.timestampEpochMs,
    heapUsed: payload.heap.used,
    heapCommitted: payload.heap.committed,
    heapMax: payload.heap.max,
    eden: poolSum(payload, 'EDEN'),
    survivor: poolSum(payload, 'SURVIVOR'),
    old: poolSum(payload, 'OLD'),
    oldAfterGc: oldPool?.usedAfterLastGc ?? null,
    metaspace: poolSum(payload, 'METASPACE'),
    gcOverheadPercent: payload.gcOverheadPercent,
    allocationRate: payload.allocationRateBytesPerSec,
  };
}

export function pointFromHistory(point: MetricsPointDto): ChartPoint {
  const known = (value: number) => (value >= 0 ? value : null);
  return {
    t: point.timestampEpochMs,
    heapUsed: point.heapUsed,
    heapCommitted: point.heapCommitted,
    heapMax: point.heapMax,
    eden: null,
    survivor: null,
    old: known(point.oldGenUsed),
    oldAfterGc: known(point.oldGenUsedAfterGc),
    metaspace: known(point.metaspaceUsed),
    gcOverheadPercent: point.gcOverheadPercent,
    allocationRate: point.allocationRateBytesPerSec,
  };
}

export function gcMarkersFromPayload(payload: LiveMetricsPayloadDto): GcMarker[] {
  const markers: GcMarker[] = [];
  for (const collector of payload.gcCollectors) {
    if (collector.countDelta <= 0) {
      continue;
    }
    markers.push({
      t: payload.timestampEpochMs,
      collector: collector.name,
      generation: collector.generation,
      pauseMs: collector.lastPauseMs ?? collector.timeDeltaMs / collector.countDelta,
      collections: collector.countDelta,
      stopTheWorld: collector.stopTheWorld,
    });
  }
  return markers;
}

/**
 * Ring buffer by time: appends and drops everything older than {@code windowMs} before the newest element.
 * Returns a new array so React/Zustand subscribers see the change.
 */
export function appendWindowed<T extends { t: number }>(items: readonly T[], added: readonly T[], windowMs: number): T[] {
  if (added.length === 0) {
    return items as T[];
  }
  const newest = added[added.length - 1]?.t ?? 0;
  const cutoff = newest - windowMs;
  let start = 0;
  while (start < items.length && (items[start]?.t ?? 0) < cutoff) {
    start++;
  }
  return items.slice(start).concat(added);
}
