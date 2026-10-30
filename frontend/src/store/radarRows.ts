import type {
  ClassGrowthSuspectDto,
  ClassStatsDto,
  GrowthWindowDto,
  MemoryGroup,
  PackageCategory,
  SnapshotDiffDto,
} from '@/api/types';
import { memoryGroupOf } from '@/utils/categories';

/** One row of the Live Leak Radar: a class, its size and how it grows. */
export interface RadarRow {
  className: string;
  packageCategory: PackageCategory;
  group: MemoryGroup;
  instances: number;
  shallowBytes: number;
  /** {@code null} when no trend is known for the class (only in a histogram) */
  growthBytesPerMin: number | null;
  growthInstancesPerMin: number | null;
  /** 0..100 */
  score: number;
  leakCandidate: boolean;
  oldGcSurvivalStreak: number | null;
  windows: GrowthWindowDto[];
  definitionCount: number;
}

interface Sources {
  /** live radar of the backend (top suspects) */
  suspects: readonly ClassGrowthSuspectDto[];
  /** full class list of the latest quick histogram or snapshot */
  classes: readonly ClassStatsDto[];
  /** offline reports: growth between two snapshots */
  diff?: SnapshotDiffDto | null;
}

/**
 * Merges the radar's suspects (trend + score), the latest histogram (every class) and, offline, a snapshot diff.
 * Suspects win for sizes because they are the freshest sample.
 */
export function buildRadarRows({ suspects, classes, diff }: Sources): RadarRow[] {
  const rows = new Map<string, RadarRow>();

  for (const stats of classes) {
    rows.set(stats.className, {
      className: stats.className,
      packageCategory: stats.packageCategory,
      group: stats.memoryGroup,
      instances: stats.instances,
      shallowBytes: stats.shallowBytes,
      growthBytesPerMin: null,
      growthInstancesPerMin: null,
      score: 0,
      leakCandidate: false,
      oldGcSurvivalStreak: null,
      windows: [],
      definitionCount: stats.definitionCount,
    });
  }

  if (diff) {
    const minutes = Math.max(
      1 / 60,
      (new Date(diff.current.createdAt).getTime() - new Date(diff.baseline.createdAt).getTime()) / 60_000,
    );
    for (const change of diff.classes) {
      const deltaBytes = change.deltaRetainedBytes ?? change.deltaShallowBytes;
      const existing = rows.get(change.className);
      rows.set(change.className, {
        className: change.className,
        packageCategory: change.packageCategory,
        group: change.memoryGroup,
        instances: change.instancesAfter,
        shallowBytes: change.shallowBytesAfter,
        growthBytesPerMin: deltaBytes / minutes,
        growthInstancesPerMin: change.deltaInstances / minutes,
        score: change.survivalScore,
        leakCandidate: change.survivalScore >= 60,
        oldGcSurvivalStreak: null,
        windows: [],
        definitionCount: existing?.definitionCount ?? 1,
      });
    }
  }

  for (const suspect of suspects) {
    const existing = rows.get(suspect.className);
    rows.set(suspect.className, {
      className: suspect.className,
      packageCategory: suspect.packageCategory,
      group: memoryGroupOf(suspect.packageCategory),
      instances: suspect.instanceCount,
      shallowBytes: suspect.shallowBytes,
      growthBytesPerMin: suspect.deltaBytesPerMin,
      growthInstancesPerMin: suspect.deltaInstancesPerMin,
      score: suspect.leakProbabilityScore,
      leakCandidate: suspect.leakCandidate,
      oldGcSurvivalStreak: suspect.oldGcSurvivalStreak,
      windows: suspect.windows,
      definitionCount: existing?.definitionCount ?? 1,
    });
  }

  return Array.from(rows.values());
}
