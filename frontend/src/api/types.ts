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

export type SnapshotStatus = 'DUMPING' | 'INDEXING' | 'ANALYZING' | 'READY' | 'FAILED';

export type SnapshotKind = 'LIVE_HISTOGRAM' | 'DEEP';

export type MemoryGroup =
  | 'USER_BUSINESS_CODE'
  | 'JAKARTA_WILDFLY_INTERNAL'
  | 'HAZELCAST_CLIENT'
  | 'JDK_CORE'
  | 'THIRD_PARTY_LIBRARY';

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
  /** process start, for the uptime display */
  startTimeEpochMs: number | null;
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
  kind: SnapshotKind;
  sourcePid: number;
  sourceDisplayName: string;
  sourceProcessType: ProcessType;
  /** ISO-8601 instant */
  createdAt: string;
  liveObjectsOnly: boolean;
  status: SnapshotStatus;
  /** 0..100 within the current phase */
  progressPercent: number;
  progressMessage: string;
  hprofFileSizeBytes: number;
  rawDumpDeleted: boolean;
  /** pause the dump (or histogram) cost the target */
  dumpDurationMs: number | null;
  indexingDurationMs: number | null;
  analysisDurationMs: number | null;
  totalInstances: number;
  totalClasses: number;
  totalHeapBytes: number;
  gcRootCount: number;
  retainedSizesAvailable: boolean;
  errorMessage: string | null;
  /** loaded from a report file */
  imported: boolean;
}

export interface ClassSampleDto {
  objectId: number;
  retainedSize: number;
  gcRootPath: GcRootPathStepDto[];
  gcRootPathText: string;
}

export interface ClassStatsDto {
  className: string;
  packageCategory: PackageCategory;
  memoryGroup: MemoryGroup;
  instances: number;
  shallowBytes: number;
  /** null for histogram snapshots */
  retainedBytes: number | null;
  definitionCount: number;
  /** only for the top classes by retained size */
  sample: ClassSampleDto | null;
}

export interface MemoryGroupStatsDto {
  group: MemoryGroup;
  classCount: number;
  instances: number;
  shallowBytes: number;
  percentOfHeap: number;
}

export interface ClassDiffDto {
  className: string;
  packageCategory: PackageCategory;
  memoryGroup: MemoryGroup;
  instancesBefore: number;
  instancesAfter: number;
  deltaInstances: number;
  shallowBytesBefore: number;
  shallowBytesAfter: number;
  deltaShallowBytes: number;
  retainedBytesBefore: number | null;
  retainedBytesAfter: number | null;
  deltaRetainedBytes: number | null;
  newClass: boolean;
  /** 0..100 */
  survivalScore: number;
}

export interface SnapshotDiffDto {
  baseline: SnapshotSummaryDto;
  current: SnapshotSummaryDto;
  retainedDeltaAvailable: boolean;
  samePid: boolean;
  chronological: boolean;
  deltaTotalInstances: number;
  deltaTotalBytes: number;
  newClasses: number;
  removedClasses: number;
  growingClasses: number;
  classes: ClassDiffDto[];
}

export interface PageDto<T> {
  items: T[];
  total: number;
  offset: number;
  limit: number;
}

export interface DiskSpaceCheckDto {
  pid: number;
  directory: string;
  heapUsedBytes: number;
  factor: number;
  requiredBytes: number;
  usableBytes: number;
  sufficient: boolean;
}

export interface HistogramSnapshotRequestDto {
  pid: number;
}

/** Paths are ordered from the GC root (index 0) to the inspected object (last). */
export interface GcRootPathStepDto {
  objectId: number;
  className: string;
  /** field, "static NAME" or "[i]" through which this object references the next step; null for the last */
  referenceName: string | null;
  gcRoot: boolean;
  gcRootKind: string | null;
}

export interface DominatorNodeDto {
  objectId: number;
  className: string;
  packageCategory: PackageCategory;
  memoryGroup: MemoryGroup;
  shallowSize: number;
  retainedSize: number;
  percentOfHeap: number;
  gcRootPath: GcRootPathStepDto[];
  /** object first: "OrderDto <- Object[142] <- static CACHE in com.acme.OrderService [GC Root: ...]" */
  gcRootPathText: string;
  gcRootKind: string | null;
  /** nearest non-JDK class on the path (who holds it); null in summaries written before the field existed */
  ownerClassName: string | null;
  ownerGroup: MemoryGroup | null;
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
  /** object first: "Ctx <- ThreadLocalMap$Entry.value <- Thread.threadLocals [GC Root: thread object]" */
  gcRootPathText: string | null;
  /** facts found in the heap that support the diagnosis */
  evidence: string[];
  /** Russian explanation */
  whyItHappens: string;
  howToFixCodeSnippet: string;
}

export interface InspectionRuleRunDto {
  ruleId: string;
  title: string;
  category: FindingCategory;
  durationMs: number;
  findings: number;
  applicable: boolean;
  truncated: boolean;
  error: string | null;
}

export interface InspectionRuleDto {
  ruleId: string;
  title: string;
  category: FindingCategory;
  enabled: boolean;
}

/** Compact live-metrics point; bytes, -1 = unknown. */
export interface MetricsPointDto {
  timestampEpochMs: number;
  heapUsed: number;
  heapCommitted: number;
  heapMax: number;
  nonHeapUsed: number;
  metaspaceUsed: number;
  oldGenUsed: number;
  oldGenUsedAfterGc: number;
  gcOverheadPercent: number;
  gcCollectionsPerMin: number;
  allocationRateBytesPerSec: number | null;
}

/** Backend SnapshotData: the full analysis that survives the deleted .hprof. */
export interface SnapshotDataDto {
  totalInstances: number;
  totalClasses: number;
  totalHeapBytes: number;
  gcRootCount: number;
  retainedSizesAvailable: boolean;
  classes: ClassStatsDto[];
  dominators: DominatorNodeDto[];
  groups: MemoryGroupStatsDto[];
  findings: InspectionFindingDto[];
  inspections: InspectionRuleRunDto[];
}

export interface DiffPairDto {
  baselineId: string;
  currentId: string;
}

export interface ReportExportRequestDto {
  pid?: number | null;
  snapshotIds?: string[];
  diffs?: DiffPairDto[];
  includeMetricsHistory?: boolean;
  title?: string | null;
}

export interface SessionReportBundle {
  formatVersion: number;
  reportId: string;
  title: string;
  /** ISO-8601 instant */
  generatedAt: string;
  generator: string;
  hostName: string;
  process: JvmProcessDto | null;
  metricsHistory: MetricsPointDto[];
  snapshots: { summary: SnapshotSummaryDto; data: SnapshotDataDto }[];
  diffs: SnapshotDiffDto[];
  findings: InspectionFindingDto[];
}

export interface ImportedReportDto {
  reportId: string;
  title: string;
  generatedAt: string;
  hostName: string;
  processName: string | null;
  /** id in the file -> id in this session */
  snapshotIds: Record<string, string>;
  findings: number;
  metricsPoints: number;
  warnings: string[];
}

export interface HeapDumpRequestDto {
  pid: number;
  liveObjectsOnly: boolean;
}

export interface AboutDto {
  name: string;
  /** build-info.properties (release builds), else the jar manifest, else "dev" */
  version: string;
  /** ISO-8601 instant; release builds only */
  buildTime: string | null;
  gitBranch: string | null;
  gitCommitId: string | null;
  /** ISO-8601 instant */
  gitCommitTime: string | null;
  gitDirty: boolean;
  /** SPDX id, "Apache-2.0" */
  license: string;
  licenseUrl: string;
  /** Java version of the analyzer JVM */
  runtimeJava: string;
}

export interface ApiErrorDto {
  /** ISO-8601 instant */
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
}
