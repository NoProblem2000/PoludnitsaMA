import type { ClassDiffDto, ClassStatsDto, SnapshotDataDto, SnapshotDiffDto, SnapshotSummaryDto } from '@/api/types';

const MIB = 1024 * 1024;

/** Mirror of backend {@code SnapshotDiffService.confidence}. */
function confidence(baseline: SnapshotSummaryDto, current: SnapshotSummaryDto): number {
  const bothDeep = baseline.kind === 'DEEP' && current.kind === 'DEEP';
  if (bothDeep && baseline.liveObjectsOnly && current.liveObjectsOnly) {
    return 1;
  }
  return bothDeep ? 0.85 : 0.7;
}

/** Mirror of backend {@code SnapshotDiffService.survivalScore}. */
export function survivalScore(instancesBefore: number, deltaInstances: number, bytesBefore: number, deltaBytes: number,
  newClass: boolean, significanceBytes: number, trust: number): number {
  if (deltaBytes <= 0) {
    return 0;
  }
  const significance = Math.min(1, deltaBytes / significanceBytes);
  const relativeBytes = newClass ? 10 : deltaBytes / Math.max(bytesBefore, 1);
  const relativeInstances = newClass ? 10 : deltaInstances > 0 ? deltaInstances / Math.max(instancesBefore, 1) : 0;
  const r = 1 - Math.exp(-relativeBytes / 0.25);
  const i = 1 - Math.exp(-relativeInstances / 0.25);
  const score = Math.round(100 * trust * Math.sqrt(significance) * (0.5 + 0.3 * r + 0.2 * i));
  return Math.max(0, Math.min(100, score));
}

/**
 * The backend diff (with shrinking classes) computed in the browser, for snapshots of an offline report: the report
 * holds every class of each snapshot, so any pair can be compared without a backend.
 */
export function computeLocalDiff(
  baseline: { summary: SnapshotSummaryDto; data: SnapshotDataDto },
  current: { summary: SnapshotSummaryDto; data: SnapshotDataDto },
): SnapshotDiffDto {
  const before = baseline.data;
  const after = current.data;
  const retained = before.retainedSizesAvailable && after.retainedSizesAvailable;
  const trust = confidence(baseline.summary, current.summary);
  const significanceBytes = Math.max(MIB, after.totalHeapBytes * 0.005);
  const index = (data: SnapshotDataDto) => new Map<string, ClassStatsDto>(data.classes.map((c) => [c.className, c]));
  const beforeByName = index(before);
  const afterByName = index(after);
  const names = new Set([...afterByName.keys(), ...beforeByName.keys()]);

  let newClasses = 0;
  let removedClasses = 0;
  let growingClasses = 0;
  const classes: ClassDiffDto[] = [];
  for (const name of names) {
    const x = beforeByName.get(name);
    const y = afterByName.get(name);
    const instancesBefore = x?.instances ?? 0;
    const instancesAfter = y?.instances ?? 0;
    const shallowBefore = x?.shallowBytes ?? 0;
    const shallowAfter = y?.shallowBytes ?? 0;
    const retainedBefore = retained ? x?.retainedBytes ?? 0 : null;
    const retainedAfter = retained ? y?.retainedBytes ?? 0 : null;
    const deltaRetained = retainedBefore != null && retainedAfter != null ? retainedAfter - retainedBefore : null;
    const deltaInstances = instancesAfter - instancesBefore;
    const deltaShallow = shallowAfter - shallowBefore;
    if (deltaInstances === 0 && deltaShallow === 0 && (deltaRetained == null || deltaRetained === 0)) {
      continue;
    }
    const isNew = instancesBefore === 0 && instancesAfter > 0;
    newClasses += isNew ? 1 : 0;
    removedClasses += instancesAfter === 0 && instancesBefore > 0 ? 1 : 0;
    growingClasses += deltaShallow > 0 ? 1 : 0;
    const bytesBefore = retained ? retainedBefore ?? 0 : shallowBefore;
    const deltaBytes = retained ? deltaRetained ?? 0 : deltaShallow;
    const stats = (y ?? x) as ClassStatsDto;
    classes.push({
      className: name,
      packageCategory: stats.packageCategory,
      memoryGroup: stats.memoryGroup,
      instancesBefore,
      instancesAfter,
      deltaInstances,
      shallowBytesBefore: shallowBefore,
      shallowBytesAfter: shallowAfter,
      deltaShallowBytes: deltaShallow,
      retainedBytesBefore: retainedBefore,
      retainedBytesAfter: retainedAfter,
      deltaRetainedBytes: deltaRetained,
      newClass: isNew,
      survivalScore: survivalScore(instancesBefore, deltaInstances, bytesBefore, deltaBytes, isNew, significanceBytes, trust),
    });
  }
  return {
    baseline: baseline.summary,
    current: current.summary,
    retainedDeltaAvailable: retained,
    samePid: baseline.summary.sourcePid === current.summary.sourcePid,
    chronological: new Date(baseline.summary.createdAt).getTime() <= new Date(current.summary.createdAt).getTime(),
    deltaTotalInstances: after.totalInstances - before.totalInstances,
    deltaTotalBytes: after.totalHeapBytes - before.totalHeapBytes,
    newClasses,
    removedClasses,
    growingClasses,
    classes,
  };
}
