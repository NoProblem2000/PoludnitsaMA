// TypeScript mirror of backend com.poludnitsa.inspector.api.dto. Keep field names and nullability 1:1.

export type ProcessType = 'WILDFLY' | 'HAZELCAST' | 'OTHER_JAVA';

export type PackageCategory =
  | 'JDK'
  | 'JAKARTA_EE'
  | 'WILDFLY'
  | 'HAZELCAST'
  | 'THIRD_PARTY'
  | 'APPLICATION'
  | 'PRIMITIVE_OR_ARRAY';

export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export type FindingCategory = 'JAKARTA_WILDFLY' | 'HAZELCAST_CLIENT' | 'JVM_CORE';

export type MemoryPoolType = 'HEAP' | 'NON_HEAP';

export type GcGeneration = 'YOUNG' | 'OLD' | 'CONCURRENT' | 'UNKNOWN';

export type SnapshotStatus = 'DUMPING' | 'INDEXING' | 'READY' | 'FAILED';

export interface JvmProcessDto {
  pid: number;
  displayName: string;
  processType: ProcessType;
  heapUsed: number | null;
  heapMax: number | null;
  jvmFlags: string[];
  jvmVersion: string | null;
  attachable: boolean;
  attachError: string | null;
}

/** Bytes; -1 means undefined. */
export interface MemoryUsageDto {
  init: number;
  used: number;
  committed: number;
  max: number;
}

export interface MemoryPoolDto {
  name: string;
  type: MemoryPoolType;
  used: number;
  committed: number;
  max: number;
  usedAfterLastGc: number | null;
}

export interface GcCollectorMetricsDto {
  name: string;
  generation: GcGeneration;
  collectionCount: number;
  collectionTimeMs: number;
  countDelta: number;
  timeDeltaMs: number;
  lastPauseMs: number | null;
}

export interface ClassGrowthSuspectDto {
  className: string;
  packageCategory: PackageCategory;
  instanceCount: number;
  shallowBytes: number;
  deltaInstancesPerMin: number;
  deltaBytesPerMin: number;
  /** 0..100 */
  leakProbabilityScore: number;
}

export interface LiveMetricsPayloadDto {
  pid: number;
  timestampEpochMs: number;
  heap: MemoryUsageDto;
  nonHeap: MemoryUsageDto;
  pools: MemoryPoolDto[];
  gcCollectors: GcCollectorMetricsDto[];
  gcOverheadPercent: number;
  histogramTimestampEpochMs: number | null;
  topGrowingClasses: ClassGrowthSuspectDto[];
}

export interface SnapshotSummaryDto {
  snapshotId: string;
  sourcePid: number | null;
  sourceDisplayName: string;
  sourceProcessType: ProcessType | null;
  hprofPath: string;
  fileSizeBytes: number;
  /** ISO-8601 instant */
  createdAt: string;
  liveObjectsOnly: boolean;
  status: SnapshotStatus;
  indexingProgressPercent: number;
  indexingDurationMs: number | null;
  totalInstances: number;
  totalClasses: number;
  totalHeapBytes: number;
  gcRootCount: number;
  errorMessage: string | null;
}

/** Paths are ordered from the GC root (index 0) to the inspected object (last). */
export interface GcRootPathStepDto {
  objectId: number;
  className: string;
  referenceName: string | null;
  gcRoot: boolean;
  gcRootKind: string | null;
}

export interface DominatorNodeDto {
  objectId: number;
  className: string;
  packageCategory: PackageCategory;
  shallowSize: number;
  retainedSize: number;
  percentOfHeap: number;
  gcRootPath: GcRootPathStepDto[];
  childCount: number;
}

export interface InspectionFindingDto {
  id: string;
  ruleId: string;
  severity: Severity;
  category: FindingCategory;
  title: string;
  wastedMemoryBytes: number;
  affectedInstances: number;
  suspectClassName: string;
  fieldName: string | null;
  gcRootPath: GcRootPathStepDto[];
  whyItHappens: string;
  howToFixCodeSnippet: string;
}

export interface HeapDumpRequestDto {
  pid: number;
  liveObjectsOnly: boolean;
}

export interface ApiErrorDto {
  /** ISO-8601 instant */
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
}
