// TypeScript mirror of backend com.poludnitsa.inspector.api.dto. Keep field names and nullability 1:1.

export type ProcessType = 'WILDFLY' | 'HAZELCAST' | 'SPRING_BOOT' | 'STANDARD_JVM';

export type ConnectionSource = 'LOCAL_ATTACH' | 'REMOTE_JMX';

export type ConnectionState =
  | 'DISCONNECTED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'RECONNECTING'
  | 'FAILED'
  | 'TERMINATED';

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

export type MemoryPoolKind =
  | 'EDEN'
  | 'SURVIVOR'
  | 'OLD'
  | 'METASPACE'
  | 'COMPRESSED_CLASS_SPACE'
  | 'CODE_CACHE'
  | 'OTHER_HEAP'
  | 'OTHER_NON_HEAP';

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
  connectionSource: ConnectionSource;
  connectionState: ConnectionState;
  remoteEndpoint: string | null;
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
  kind: MemoryPoolKind;
  used: number;
  committed: number;
  max: number;
  usedAfterLastGc: number | null;
}

export interface GcCollectorMetricsDto {
  name: string;
  generation: GcGeneration;
  /** false for concurrent-cycle beans ("ZGC Cycles"); only STW beans count towards GC overhead */
  stopTheWorld: boolean;
  collectionCount: number;
  collectionTimeMs: number;
  countDelta: number;
  timeDeltaMs: number;
  lastPauseMs: number | null;
}

export interface GrowthWindowDto {
  windowMinutes: 1 | 5 | 15;
  samples: number;
  bytesSlopePerMin: number;
  instancesSlopePerMin: number;
  monotonicity: number;
  rSquared: number;
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
  leakCandidate: boolean;
  oldGcSurvivalStreak: number;
  windows: GrowthWindowDto[];
}

export interface AllocatingClassDto {
  className: string;
  packageCategory: PackageCategory;
  bytesPerSec: number;
  samples: number;
}

export interface JfrAllocationStatsDto {
  active: boolean;
  recordingName: string;
  startedAtEpochMs: number;
  lastUpdatedEpochMs: number | null;
  allocationRateBytesPerSec: number | null;
  topAllocatingClasses: AllocatingClassDto[];
  error: string | null;
}

export interface LiveMetricsPayloadDto {
  pid: number;
  timestampEpochMs: number;
  heap: MemoryUsageDto;
  nonHeap: MemoryUsageDto;
  pools: MemoryPoolDto[];
  gcCollectors: GcCollectorMetricsDto[];
  /** STW pause share over the last minute, 0..100 */
  gcOverheadPercent: number;
  gcCollectionsPerMin: number;
  allocationRateBytesPerSec: number | null;
  histogramTimestampEpochMs: number | null;
  histogramDurationMs: number | null;
  /** null when the target has no DiagnosticCommand MBean */
  histogramIntervalMs: number | null;
  topGrowingClasses: ClassGrowthSuspectDto[];
  jfrAllocation: JfrAllocationStatsDto | null;
}

export interface ClassHistogramEntryDto {
  rank: number;
  className: string;
  packageCategory: PackageCategory;
  instances: number;
  bytes: number;
  module: string | null;
  /** > 1: same class name loaded by several class loaders (redeploy leak hint) */
  definitionCount: number;
}

export interface ClassHistogramDto {
  pid: number;
  timestampEpochMs: number;
  durationMs: number;
  totalInstances: number;
  totalBytes: number;
  classCount: number;
  entries: ClassHistogramEntryDto[];
}

export interface TargetStatusDto {
  pid: number;
  state: ConnectionState;
  message: string | null;
  timestampEpochMs: number;
}

export type LiveStreamMessageType = 'SUBSCRIBED' | 'UNSUBSCRIBED' | 'METRICS' | 'STATUS' | 'ERROR' | 'PONG';

/** Server-to-client frame on /ws/live. */
export interface LiveStreamMessageDto {
  type: LiveStreamMessageType;
  pid: number | null;
  metrics: LiveMetricsPayloadDto | null;
  status: TargetStatusDto | null;
  error: string | null;
}

/** Client-to-server frame on /ws/live. */
export type LiveStreamClientMessage =
  | { action: 'subscribe'; pid: number }
  | { action: 'unsubscribe' }
  | { action: 'ping' };

export interface RemoteJmxConnectRequestDto {
  hostPort?: string;
  serviceUrl?: string;
  username?: string;
  password?: string;
}

export interface SelectProcessRequestDto {
  pid: number;
}

export interface ActiveProcessDto {
  pid: number | null;
}

export interface GcTriggerRequestDto {
  confirm: boolean;
}

export interface GcTriggerResultDto {
  pid: number;
  durationMs: number;
  heapUsedBefore: number;
  heapUsedAfter: number;
  explicitGcDisabled: boolean;
  explicitGcConcurrent: boolean;
  warning: string;
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
