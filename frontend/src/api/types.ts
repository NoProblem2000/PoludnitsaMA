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

/** How a path step references the next one; everything but STRONG is the referent of a java.lang.ref.Reference. */
export type ReferenceKind = 'STRONG' | 'WEAK' | 'SOFT' | 'PHANTOM' | 'FINALIZER';

export type PathMode = 'STRONG_ONLY' | 'ALL_REFERENCES';

export type ClassLoaderKind = 'BOOTSTRAP' | 'PLATFORM' | 'APPLICATION' | 'JBOSS_MODULE' | 'OTHER';

export type OqlValueType =
  | 'INSTANCE'
  | 'CLASS'
  | 'STRING'
  | 'NUMBER'
  | 'BOOLEAN'
  | 'LIST'
  | 'OBJECT'
  | 'NULL'
  | 'OTHER';

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
  /** the dump is kept open for OQL and on-demand GC root paths */
  heapOpen: boolean;
  /** ISO-8601 instant; when an open dump is closed if unused */
  heapExpiresAt: string | null;
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
  /** how sampled instances are held, most common shape first; top classes of deep snapshots */
  retentionPaths: RetentionPathDto[];
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
  /** grown classes of a deep current snapshot: how they are held there */
  retentionPaths: RetentionPathDto[];
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
  /** kind of the reference to the next step; null for the last step and in older summaries (strong) */
  referenceKind: ReferenceKind | null;
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
  classLoaders: ClassLoaderStatsDto[];
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
  /** CPU profiles, thread series, live contention, GC analyses and OQL history (default true) */
  includeDiagnostics?: boolean | null;
}

export interface SnapshotReport {
  summary: SnapshotSummaryDto;
  data: SnapshotDataDto;
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
  snapshots: SnapshotReport[];
  diffs: SnapshotDiffDto[];
  findings: InspectionFindingDto[];
  /** format 2+: absent in format 1 files */
  profiles: ProfileReportDto[];
  threadSeries: ThreadSeriesAnalysisDto[];
  threadsLive: ThreadLiveDto | null;
  gcAnalyses: GcAnalysisDto[];
  oqlQueries: OqlHistoryEntryDto[];
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
  /** keep the dump open for OQL until closed or idle */
  keepForQueries?: boolean | null;
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

/** One way the instances of a class are held: sampled GC root paths of the same shape. */
export interface RetentionPathDto {
  /** root first: "static CACHE in OrderService → HashMap.table → HashMap$Node[] → HashMap$Node.value" */
  signature: string;
  /** full path of a representative instance, root first */
  steps: GcRootPathStepDto[];
  sampledInstances: number;
  /** 0..1 of the sampled instances */
  share: number;
}

export interface ClassLoaderClassDto {
  className: string;
  instances: number;
  shallowBytes: number;
}

export interface ClassLoaderStatsDto {
  /** null for the bootstrap loader */
  objectId: number | null;
  loaderClassName: string;
  /** module name when known, else the loader */
  displayName: string;
  /** JBoss Modules name ("deployment.order-service.war") of a JBOSS_MODULE loader */
  moduleName: string | null;
  kind: ClassLoaderKind;
  classCount: number;
  instances: number;
  shallowBytes: number;
  /** retained size of the loader instance (its classes, statics and what only they hold); null for bootstrap */
  retainedBytes: number | null;
  topClasses: ClassLoaderClassDto[];
}

export interface GcRootPathDto {
  objectId: number;
  mode: PathMode;
  /** GC root first; empty when none was found within the limits */
  steps: GcRootPathStepDto[];
  text: string;
  rootKind: string | null;
  /** only strong references keep the object: it is really retained */
  strongPathExists: boolean;
  visitedObjects: number;
  truncated: boolean;
}

export interface OqlQueryRequestDto {
  query: string;
  limit?: number | null;
  timeoutSeconds?: number | null;
}

export interface OqlValueDto {
  type: OqlValueType;
  text: string;
  objectId: number | null;
  className: string | null;
  shallowSize: number | null;
  retainedSize: number | null;
  items: OqlValueDto[] | null;
  fields: Record<string, OqlValueDto> | null;
  totalItems: number | null;
}

export interface OqlResultDto {
  snapshotId: string;
  query: string;
  /** "value" for plain results, the property names for select { ... } */
  columns: string[];
  rows: OqlValueDto[][];
  rowCount: number;
  truncated: boolean;
  durationMs: number;
}

export interface AllocationFrameDto {
  className: string;
  method: string;
  /** -1 when unknown */
  line: number;
  /** application code (not JDK, server or library) */
  application: boolean;
}

export interface AllocationStackDto {
  /** allocating frame first */
  frames: AllocationFrameDto[];
  weightBytes: number;
  samples: number;
  /** 0..1 */
  share: number;
}

export interface AllocationHotspotDto {
  frame: AllocationFrameDto;
  weightBytes: number;
  samples: number;
  /** 0..1 */
  share: number;
}

export interface AllocationStacksDto {
  pid: number;
  className: string;
  weightBytes: number;
  samples: number;
  windowMs: number;
  hotspots: AllocationHotspotDto[];
  stacks: AllocationStackDto[];
  eventTypes: string[];
}

// ---- CPU & Timeline profiling (ProfilerController) ----

export type ProfileSource = 'JFR' | 'ASYNC_PROFILER';

export type ProfileMetric = 'CPU' | 'WALL' | 'ALLOC';

export type ProfileStatus = 'RECORDING' | 'PARSING' | 'READY' | 'FAILED';

export type Subsystem = 'BUSINESS' | 'JDBC' | 'HIBERNATE' | 'HTTP' | 'IO' | 'LOCKS' | 'GC' | 'FRAMEWORK' | 'JDK';

export type ThreadState = 'RUNNABLE' | 'BLOCKED' | 'WAITING' | 'IO';

export interface ProfileRequestDto {
  pid: number;
  /** null: JFR */
  source?: ProfileSource | null;
  /** what async-profiler samples; JFR records all metrics at once */
  mode?: ProfileMetric | null;
  durationSeconds: number;
  /** park / monitor / I/O from 1 ms and every exception with its stack (JFR) */
  tracing?: boolean | null;
}

export interface ProfilerCapabilitiesDto {
  jfrAvailable: boolean;
  asyncProfilerAvailable: boolean;
  asyncProfilerPath: string | null;
  asyncProfilerReason: string | null;
  os: string;
}

export interface ProfileSummaryDto {
  profileId: string;
  pid: number;
  displayName: string;
  source: ProfileSource;
  mode: ProfileMetric;
  status: ProfileStatus;
  startedAt: string;
  /** requested recording length */
  durationMs: number;
  /** time covered by the parsed events */
  recordedMs: number;
  progressMessage: string | null;
  errorMessage: string | null;
  sampleCount: number;
  eventCount: number;
  threadCount: number;
  samplingIntervalMs: number;
  tracing: boolean;
  /** metrics with data in this profile */
  metrics: ProfileMetric[];
  truncated: boolean;
}

export interface TimeSpanDto {
  startMs: number;
  durationMs: number;
  label: string;
}

export interface ThreadLaneDto {
  /** for the thread filter of the call tree queries */
  threadIndex: number;
  name: string;
  /** dominant state per column, null where the thread did nothing visible */
  states: (ThreadState | null)[];
  cpuMs: number;
  blockedMs: number;
  waitingMs: number;
  ioMs: number;
}

export interface TimelineDto {
  profileId: string;
  startEpochMs: number;
  durationMs: number;
  bucketMs: number;
  buckets: number;
  jvmCpuPercent: (number | null)[];
  machineCpuPercent: (number | null)[];
  samples: number[];
  gcPauseMs: number[];
  lockMs: number[];
  waitMs: number[];
  ioMs: number[];
  gcPauses: TimeSpanDto[];
  threads: ThreadLaneDto[];
}

/** Values are microseconds for CPU / WALL, bytes for ALLOC. */
export interface FlameNodeDto {
  className: string;
  methodName: string;
  packageCategory: PackageCategory;
  selfSamples: number;
  totalSamples: number;
  selfValue: number;
  totalValue: number;
  /** null for ALLOC */
  totalTimeMs: number | null;
  /** of the tree total, 0..100 */
  percent: number;
  children: FlameNodeDto[];
}

export interface FlameGraphDto {
  profileId: string;
  metric: ProfileMetric;
  /** "us" or "bytes" */
  unit: string;
  fromMs: number;
  toMs: number;
  threadName: string | null;
  totalValue: number;
  totalSamples: number;
  root: FlameNodeDto;
  prunedNodes: number;
}

export interface DiffFlameNodeDto {
  className: string;
  methodName: string;
  packageCategory: PackageCategory;
  baselineValue: number;
  targetValue: number;
  baselinePercent: number;
  targetPercent: number;
  /** positive: regression, negative: improvement */
  deltaPercent: number;
  children: DiffFlameNodeDto[];
}

export interface DiffFlameGraphDto {
  baselineId: string;
  targetId: string;
  metric: ProfileMetric;
  unit: string;
  baselineTotal: number;
  targetTotal: number;
  root: DiffFlameNodeDto;
}

export interface HotspotDto {
  className: string;
  methodName: string;
  packageCategory: PackageCategory;
  subsystem: Subsystem;
  selfSamples: number;
  totalSamples: number;
  selfValue: number;
  totalValue: number;
  selfPercent: number;
  totalPercent: number;
}

export interface ButterflyDto {
  profileId: string;
  metric: ProfileMetric;
  unit: string;
  className: string;
  methodName: string;
  totalValue: number;
  selfValue: number;
  percent: number;
  /** inverted tree: children are the callers */
  callers: FlameNodeDto;
  /** what the method calls */
  callees: FlameNodeDto;
}

export interface SubsystemShareDto {
  subsystem: Subsystem;
  label: string;
  value: number;
  samples: number;
  percent: number;
  topMethods: HotspotDto[];
}

export interface TracedEventStatDto {
  /** JFR event type, e.g. jdk.SocketRead */
  type: string;
  label: string;
  count: number;
  totalMs: number;
  maxMs: number;
  topDetail: string | null;
}

export interface ExceptionCountDto {
  className: string;
  count: number;
}

export interface SubsystemBreakdownDto {
  profileId: string;
  metric: ProfileMetric;
  unit: string;
  fromMs: number;
  toMs: number;
  totalValue: number;
  subsystems: SubsystemShareDto[];
  traced: TracedEventStatDto[];
  exceptions: ExceptionCountDto[];
}

// ---------------------------------------------------------------- threads & contention, GC (step 10)

export type DiagnosticSource = 'LIVE' | 'FILE';

export type JobStatus = 'RUNNING' | 'READY' | 'FAILED';

export type JvmThreadState = 'NEW' | 'RUNNABLE' | 'BLOCKED' | 'WAITING' | 'TIMED_WAITING' | 'TERMINATED';

export type StuckKind = 'CPU_LOOP' | 'DB_QUERY' | 'IO_WAIT' | 'LOCK_WAIT' | 'RUNNABLE';

export type LockKind = 'MONITOR' | 'SYNCHRONIZER';

export type GcPauseCategory = 'YOUNG' | 'MIXED' | 'FULL' | 'HUMONGOUS' | 'REMARK' | 'CLEANUP' | 'CONCURRENT_PAUSE' | 'OTHER';

export interface StackFrameDto {
  className: string;
  methodName: string;
  fileName: string | null;
  lineNumber: number;
  packageCategory: PackageCategory;
  locks: string[];
}

export interface DeadlockNodeDto {
  threadId: number;
  threadName: string;
  state: JvmThreadState;
  waitingForLock: string | null;
  waitingForLockClass: string | null;
  lockKind: LockKind | null;
  heldByThreadId: number;
  heldByThreadName: string | null;
  heldLocks: string[];
  stack: StackFrameDto[];
}

export interface DeadlockDto {
  detectedAt: string;
  kind: LockKind;
  cycle: DeadlockNodeDto[];
}

export interface MonitorContentionDto {
  lockClass: string;
  lockKind: LockKind | null;
  blockedAt: string;
  applicationFrame: string | null;
  blockedTimeMs: number;
  blockedCount: number;
  samples: number;
  threads: number;
  serverThreads: number;
  timeMeasured: boolean;
  topThreads: string[];
  topOwners: string[];
  ownerFrame: string | null;
}

export interface LiveThreadDto {
  threadId: number;
  name: string;
  state: JvmThreadState;
  daemon: boolean;
  serverThread: boolean;
  cpuMs: number | null;
  cpuPercent: number | null;
  blockedCount: number;
  blockedTimeMs: number | null;
  waitedCount: number;
  waitedTimeMs: number | null;
  lockName: string | null;
  lockOwnerName: string | null;
  topFrame: string | null;
}

export interface ThreadLiveDto {
  pid: number;
  capturedAt: string | null;
  samplingSince: string;
  samples: number;
  sampleIntervalMs: number;
  threadCount: number;
  daemonCount: number;
  peakThreadCount: number;
  states: Partial<Record<JvmThreadState, number>>;
  contentionMonitoring: boolean;
  cpuTimeMonitoring: boolean;
  deadlocks: DeadlockDto[];
  contention: MonitorContentionDto[];
  threads: LiveThreadDto[];
}

export interface ThreadSeriesRequestDto {
  pid: number;
  dumps: number;
  intervalMs: number;
}

export interface ThreadSeriesSummaryDto {
  seriesId: string;
  source: DiagnosticSource;
  pid: number | null;
  name: string;
  status: JobStatus;
  startedAt: string;
  dumpsRequested: number;
  dumpsTaken: number;
  intervalMs: number | null;
  progressMessage: string | null;
  errorMessage: string | null;
  threadCount: number;
  stuckCount: number;
  deadlockCount: number;
  clusterCount: number;
}

export interface ThreadDumpMetaDto {
  index: number;
  capturedAt: string | null;
  offsetMs: number | null;
  threadCount: number;
  states: Partial<Record<JvmThreadState, number>>;
  jvmInfo: string | null;
}

export interface ThreadCellDto {
  state: JvmThreadState | null;
  stackId: number;
  sameAsPrevious: boolean;
  topFrame: string | null;
  lockName: string | null;
  cpuDeltaMs: number | null;
}

export interface ThreadSeriesRowDto {
  name: string;
  threadId: number | null;
  daemon: boolean;
  serverThread: boolean;
  idle: boolean;
  stuck: boolean;
  clusterId: number | null;
  /** null: the thread is not in that dump */
  cells: (ThreadCellDto | null)[];
}

export interface StuckThreadDto {
  name: string;
  threadId: number | null;
  state: JvmThreadState;
  kind: StuckKind;
  reason: string;
  fromDump: number;
  toDump: number;
  dumps: number;
  durationMs: number | null;
  cpuDeltaMs: number | null;
  lockName: string | null;
  serverThread: boolean;
  stack: StackFrameDto[];
}

export interface StackClusterDto {
  clusterId: number;
  dumpIndex: number;
  state: JvmThreadState;
  threadCount: number;
  threads: string[];
  topFrame: string | null;
  lockName: string | null;
  stackId: number;
}

export interface ThreadSeriesAnalysisDto {
  summary: ThreadSeriesSummaryDto;
  dumps: ThreadDumpMetaDto[];
  rows: ThreadSeriesRowDto[];
  stacks: StackFrameDto[][];
  stuck: StuckThreadDto[];
  clusters: StackClusterDto[];
  deadlocks: DeadlockDto[];
  contention: MonitorContentionDto[];
  contentionTimed: boolean;
}

export interface GcLiveRequestDto {
  pid: number;
  durationSeconds: number;
}

export interface GcAnalysisSummaryDto {
  analysisId: string;
  source: DiagnosticSource;
  pid: number | null;
  name: string;
  status: JobStatus;
  startedAt: string;
  durationSeconds: number | null;
  progressMessage: string | null;
  errorMessage: string | null;
  collector: string | null;
  jvmVersion: string | null;
  pauseCount: number;
  coveredMs: number;
}

export interface GcKpiDto {
  throughputPercent: number;
  totalPauseMs: number;
  maxPauseMs: number;
  avgPauseMs: number;
  p95PauseMs: number;
  p99PauseMs: number;
  pauseCount: number;
  youngCount: number;
  mixedCount: number;
  fullCount: number;
  allocationRateMbPerSec: number | null;
  promotionRateMbPerSec: number | null;
  peakHeapMb: number | null;
  avgHeapAfterGcMb: number | null;
  safepointTotalMs: number | null;
}

export interface GcPauseDto {
  gcId: number;
  uptimeMs: number;
  category: GcPauseCategory;
  name: string;
  cause: string | null;
  pauseMs: number;
  heapBeforeMb: number | null;
  heapAfterMb: number | null;
  heapTotalMb: number | null;
}

export interface GcRateSampleDto {
  uptimeMs: number;
  allocationMbPerSec: number;
  promotionMbPerSec: number | null;
  pauseMs: number;
}

export interface GcCauseStatDto {
  cause: string;
  count: number;
  totalPauseMs: number;
  maxPauseMs: number;
  percentOfPauses: number;
  severity: Severity;
  advice: string | null;
}

export interface SafepointEventDto {
  uptimeMs: number;
  operation: string;
  ttspMs: number;
  operationMs: number;
  totalMs: number;
  slowTtsp: boolean;
}

export interface SafepointOperationStatDto {
  operation: string;
  count: number;
  totalMs: number;
  maxTotalMs: number;
  maxTtspMs: number;
}

export interface SafepointSummaryDto {
  count: number;
  totalMs: number;
  maxTtspMs: number;
  avgTtspMs: number;
  ttspWarningMs: number;
  slowTtspCount: number;
  operations: SafepointOperationStatDto[];
  slowest: SafepointEventDto[];
}

export interface GcFindingDto {
  severity: Severity;
  code: string;
  title: string;
  detail: string;
  advice: string | null;
}

export interface GcAnalysisDto {
  summary: GcAnalysisSummaryDto;
  kpi: GcKpiDto;
  pauses: GcPauseDto[];
  rates: GcRateSampleDto[];
  causes: GcCauseStatDto[];
  safepoints: SafepointSummaryDto;
  findings: GcFindingDto[];
  concurrentCycles: number;
  parsedLines: number;
  unparsedLines: number;
  truncated: boolean;
}

export interface OqlHistoryEntryDto {
  snapshotId: string;
  query: string;
  executedAt: string;
  rowCount: number;
  durationMs: number;
  truncated: boolean;
  columns: string[];
  preview: string[][];
}

export interface ProfileMetricReportDto {
  metric: ProfileMetric;
  flame: FlameGraphDto | null;
  hotspots: HotspotDto[];
  subsystems: SubsystemBreakdownDto | null;
}

export interface ProfileReportDto {
  summary: ProfileSummaryDto;
  timeline: TimelineDto;
  metrics: ProfileMetricReportDto[];
}
