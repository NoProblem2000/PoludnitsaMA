# JVM Memory & Leak Inspector — архитектура

Standalone веб-приложение (аналог dotMemory) для анализа памяти Java 17+ процессов
(WildFly / Jakarta EE 10, Hazelcast Client, standalone Hazelcast), запущенных **на том же хосте**.

```
PoludnitsaMA/
├── backend/            Spring Boot 3.x (Java 17, Maven) — один исполняемый jar
├── frontend/           React 18 + TypeScript + Vite + Tailwind
├── start-inspector.sh  запуск на Linux (JDK 17+, -Xms256m -Xmx1024m, --add-opens), проверки JDK/jdk.attach
├── start-inspector.bat то же для Windows
├── LICENSE, NOTICE     Apache License 2.0 и атрибуции встроенных библиотек (кладутся в META-INF/ jar)
├── README.md           требования, сборка из ветки release, флаги запуска
├── .gitlab-ci.yml      релизный пайплайн ветки release (shell runner, без Docker)
└── ARCHITECTURE.md
```

`cd backend && mvn clean package` ставит Node 20 в `backend/target/node`, выполняет `npm ci` и `npm run build`,
копирует `frontend/dist` в `target/classes/static` и собирает **один** jar `backend/target/jvm-leak-inspector.jar`
(UI отдаётся Spring Boot на `/`). `-DskipUi` — сборка без фронтенда. На сервер копируются jar и скрипт запуска:
`./start-inspector.sh` (или `java -jar`: `Add-Opens` прописаны и в манифесте jar).

---

## 1. Поток данных

```
 ┌──────────────── тот же хост (bare-metal / VM) ─────────────────────────────────┐
 │                                                                                │
 │  WildFly JVM (PID 4711)        Hazelcast JVM (PID 4800)                        │
 │    ▲ Attach API → startLocalManagementAgent() → локальный JMX URL              │
 │    │                                                                           │
 │  ┌─┴───────────────────────── Inspector JVM ────────────────────────────────┐  │
 │  │ discovery ─► attach/jmx ─► live (sampler, histogram, leak scoring)       │  │
 │  │                     │       └─► WebSocket /ws/live (pid)   ───────────► │──┼─► Browser
 │  │                     ├─► jfr  (FlightRecorderMXBean → .jfr файл → parse)  │  │   (React)
 │  │                     └─► dump (HotSpotDiagnosticMXBean.dumpHeap)          │  │
 │  │                              └─► heap (NetBeans profiler lib, disk index)│  │
 │  │                                     └─► inspection (rules) ─► report     │  │
 │  └──────────────────────────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────────────────────────┘
```

Анализатор **никогда** не измеряет сам себя: все метрики берутся через `MBeanServerConnection`
целевого PID. `jdk.jfr.consumer.RecordingStream` запрещён (он стримит события *текущей* JVM).
Для JFR используется `jdk.management.jfr:type=FlightRecorder` целевого процесса:
`newRecording → setRecordingOptions/Settings → startRecording`, затем каждый poll
`cloneRecording(id, stop=true) → openStream(startTime) / readStream` → файл на диске →
офлайн-разбор через `jdk.jfr.consumer.RecordingFile` (читает файл, а не текущую JVM).
Сам анализатор защищён от самомониторинга явно: `JmxConnectionManager` отклоняет собственный PID
(и remote-endpoint, указывающий на него) с кодом `SELF_MONITORING_FORBIDDEN`.

### Live Leak Radar
`GC.class_histogram -all` (через `DiagnosticCommandMBean.gcClassHistogram`) **не** вызывает Full GC, но
считает и недостижимые объекты. Объекты освобождаются только сборкой, поэтому между двумя GC класс может
только расти: первая гистограмма после каждого GC — лучшая оценка live-set. Тренды (OLS, монотонность, R²)
строятся только по post-GC сэмплам в окнах 1/5/15 мин; сэмплы после old-gen reclamation (old/full GC или
падение `CollectionUsage` Old-пула — mixed GC в G1) дают «streak выживания». Без двух old-GC в окне
score ограничен 80. Обход кучи — safepoint-пауза: её длительность в каждом payload, а интервал радара
автоматически удваивается, если гистограмма дольше `histogram-pause-budget`.

---

## 2. Backend — пакеты (`com.poludnitsa.inspector`)

| Пакет | Ответственность |
|---|---|
| `InspectorApplication` | Точка входа Spring Boot. |
| `config` | `InspectorProperties` (`@ConfigurationProperties`: интервалы семплинга, каталог дампов, коэффициент запаса диска 2.5), `WebSocketConfig` (сырой WebSocket `/ws/live`), `LocalOriginFilter` (отклоняет запросы и WS-handshake с чужим `Origin`: вкладка браузера не может дёрнуть GC/дамп), `WebConfig` (SPA fallback на `index.html`). |
| `api.dto` | Все DTO (Java `record`) и enum'ы контракта backend ↔ frontend. **Единственный** пакет, который «видит» фронтенд. |
| `api.rest` | REST-контроллеры: `ProcessController` (`/api/processes`), `LiveController` (start/stop семплинга), `SnapshotController` (`/api/snapshots`: снять дамп, загрузить .hprof, статус индексации), `HeapController` (dominator tree, instances, GC root paths — постранично), `InspectionController`, `ReportController` (экспорт/импорт JSON/HTML). `GlobalExceptionHandler` → `ApiErrorDto`. |
| `api.ws` | `LiveMetricsWebSocketHandler` — `/ws/live`, один PID на соединение: `{"action":"subscribe","pid":N}` / `unsubscribe` / `ping` → `LiveStreamMessageDto` (SUBSCRIBED, METRICS, STATUS, ERROR, PONG). Отправка через `ConcurrentWebSocketSessionDecorator` (DROP старых сэмплов у медленного клиента). |
| `discovery` | `JvmDiscoveryService` (`VirtualMachine.list()`, параллельные attach-пробы системных свойств без запуска агента, кеш по PID + start instant, ручные remote JMX `host:port`), `ProcessClassifier` (WILDFLY → HAZELCAST по main class → SPRING_BOOT → HAZELCAST по `hazelcast.*` member-свойствам → STANDARD_JVM), `ManifestReader` (manifest `java -jar`). Собственный PID скрыт. |
| `jmx` | `JmxConnectionManager` — attach + `startLocalManagementAgent()` или RMI-коннектор, одно соединение на PID, heartbeat + `JMXConnectionNotification`, реконнект с экспоненциальным backoff, защита от переиспользования PID, таймауты. `TargetMetadata` — пулы/коллекторы/наличие опциональных MBean (читается раз на соединение). `GcCollectorClassifier`, `MemoryPoolClassifier`, `AttachErrorHints` (подсказки: другой OS-пользователь, PrivateTmp, jboss-logmanager). |
| `live` | `LiveMemoryCollectorService` (мониторит PID, пока есть подписчики или он выбран активным, + grace), `LiveTargetMonitor` (1 c: Memory/Pools/GC bulk `getAttributes`, GC overhead и частота за минуту, allocation rate из `TotalThreadAllocatedBytes`), `HistogramFetcher` + `ClassHistogramParser`, `LeakRadar` + `LeakScorer` (см. выше). |
| `jfr` | `JfrAllocationSampler` — запись **в целевом** процессе (`ThreadAllocationStatistics` + `ObjectAllocationSample` без стеков, `maxAge`/`maxSize`, закрытие осиротевших записей), `JfrAllocationAggregator` — allocation rate и топ аллоцирующих классов. |
| `snapshot` | `SafeHeapDumpService` — проверка `usableSpace >= 2.5 × heapUsed` (507 `INSUFFICIENT_DISK_SPACE`, повторно прямо перед дампом), `HotSpotDiagnosticMXBean.dumpHeap(path, live)` в личную директорию снапшота, один deep-снапшот за раз, финальный статус публикуется только после удаления файлов. `HprofSanitizer` — однопроходный ремонт висячих `ROOT_STICKY_CLASS` (баг NetBeans RELEASE210–310 на дампах JDK 17+). `ObjectGraphAnalyzer` — `HeapFactory.createHeap` (mmap + дисковые индексы), топ-200 объектов по retained, retained по классам, `GcRootPathResolver` (путь с именами полей / `static` / индексами массивов), группы памяти. `DumpFileCleaner` — удаление `work/<id>/` (`heap.hprof` + `heap.hprof.nbcache/NBProfiler*`) в `finally`, shutdown hook, чистка остатков при старте, ретраи (на Windows mmap держит файл до GC). `SnapshotStore` — сессия в памяти + `summaries/<id>.json.gz` (переживает рестарт). `HistogramSnapshotService`, `SnapshotDiffService` (дельты и ранжирование по «аномальному выживанию»). |
| `heap` | `HeapReader` — типизированное чтение открытого дампа: поля, `String` (Java 9+ `byte[]`+`coder` и Java 8 `char[]`), `byte[]`, все JDK-коллекции без запуска их кода (`size`, обход `Map`/`List`/очередей), входящие ссылки, «владелец» поверх внутренностей коллекций. `GcRootPathResolver`, `LongIntHashMap` (примитивный счётчик для миллионов строк). |
| `inspection` | `LeakInspectionRule` (SPI: Spring-бин = новое правило), `LeakHeuristicsEngine` (бюджет времени на правило, изоляция ошибок, сортировка, удаление общих JVM-находок, дублирующих специализированные), `InspectionContext`, `FindingBuilder`. Правила: `rules.jakarta` — `WildFlyClassLoaderLeakInspector`, `ThreadLocalLeakInspector`, `CdiScopeMismatchInspector`, `HibernatePersistenceContextBloatInspector`, `UndertowHttpSessionBloatInspector`; `rules.hazelcast` — `HazelcastSerializedHeapDataInspector` (+ `HazelcastPayloadDecoder`), `OrphanedHazelcastListenerInspector`; `rules.jvm` — `UnboundedCollectionAndCacheInspector`, `DuplicateStringsAndByteArraysInspector`. Запускаются внутри `ObjectGraphAnalyzer`, пока дамп открыт. |
| `report` | `ReportExportImportService` — `SessionReportBundle` (процесс, история графиков, снапшоты с dominators/классами/группами/findings, diff'ы, с формата 2 — CPU-профили, серии thread dump-ов, live-контеншн, GC-анализы, история OQL) в `.json.gz` и обратно (лимит 512 MiB после распаковки, импортированные снапшоты получают свои id), `HtmlReportRenderer` + `DiagnosticsHtmlSections` — автономный HTML без внешних ресурсов, всё из кучи экранируется. |
| `threads` | `ThreadContentionAndSeriesService`, `ContentionAggregator`, `DeadlockDetector`, `ThreadDumpSeriesAnalyzer`, `JstackParser` — см. «Шаг 10». |
| `gc` | `GcLogParserAndAnalyzerService`, `UnifiedGcLogParser`, `JfrGcParser`, `GcLogData`, `GcLogAnalyzer` — см. «Шаг 10». |
| `common` | Исключения (`TargetNotAttachableException`, `InsufficientDiskSpaceException`), `Bytes` (форматирование), `Ids`. |

### Важные эксплуатационные детали
* Attach API работает только от **того же OS-пользователя**, что и целевая JVM. Запускать анализатор под пользователем `wildfly`/`hazelcast` (или отдельными инстансами).
* WildFly (JBoss Modules + jboss-logmanager): для старта локального management-агента в `standalone.conf` обычно нужно
  `-Djboss.modules.system.pkgs=org.jboss.byteman,org.jboss.logmanager` и `-Djava.util.logging.manager=org.jboss.logmanager.LogManager`
  + logmanager на bootclasspath. Сканер помечает такие процессы как `attachable=false` с подсказкой.
* Целевые JVM не должны иметь `-XX:+DisableAttachMechanism`.
* Дамп пишется **целевой JVM** — каталог дампов должен быть доступен ей на запись и анализатору на чтение.
* Индексы NetBeans создаются рядом с .hprof (`heap.hprof.nbcache/`) и удаляются вместе с ним; запас 2.5× их покрывает.
* Запись дампа — всегда safepoint: цель стоит всё время записи (`live=true` добавляет Full GC). Длительность паузы
  сохраняется в снапшоте (`dumpDurationMs`).
* Анализатору нужна своя RAM под индексы NetBeans (не под сам дамп): для дампов в десятки GB запускать с `-Xmx4g+`.

---

## 3. Frontend — структура (`frontend/src`)

```
src/
├── main.tsx, App.tsx, index.css
├── api/
│   ├── types.ts            # TS-зеркало Java DTO (1:1 с api.dto)
│   ├── http.ts             # fetch-обёртка, ApiError из ApiErrorDto, скачивание файлов
│   ├── endpoints.ts        # типизированные вызовы REST (processes, snapshots, diff, reports, import), постраничные классы
│   ├── liveSocket.ts       # WebSocket /ws/live: subscribe/unsubscribe, ping, реконнект с backoff
│   └── offlineReport.ts    # чтение .json.gz/.json в браузере (DecompressionStream), без бэкенда
├── store/
│   ├── useInspectorStore.ts  # Zustand: процессы, live/offline режим, поток, снапшоты, findings, действия тулбара
│   ├── liveSeries.ts       # кольцевой буфер 300 с (ChartPoint, GcMarker) из payload/истории
│   ├── radarRows.ts        # слияние live-подозреваемых, классов гистограммы и offline-diff в строки радара
│   └── useSnapshotData.ts  # выбор deep-снапшота и загрузка доминаторов/диагнозов (кэш; offline — из отчёта)
├── theme/palette.ts        # палитра GitKraken для ECharts/inline-стилей, градиент score emerald→amber→coral
├── components/
│   ├── layout/             # AppShell, TopToolbar, ProcessSelector, Sidebar, SnapshotTimeline, ToastStack
│   ├── common/             # Badge, Tooltip, ToolbarButton, ConfirmDialog, Modal, EmptyState, ScoreBar,
│   │                       # CodeBlock (подсветка + копирование), SnapshotSelect
│   ├── heap/GcRootPathGraph.tsx  # вертикальная цепочка GC Root → ссылки (поля/static/[i]) → объект
│   └── charts/EChart.tsx   # модульный ECharts (line, scatter, sunburst), resize/dispose, click-события
├── features/
│   ├── live/               # LiveDashboardView, StatCards, MemoryPoolsChart, LeakRadarTable (@tanstack/react-virtual)
│   ├── heuristics/         # HeuristicsAdvisorView: диагнозы по severity/категориям, виновник, путь, why, fix
│   ├── heap/               # DominatorTreeView (sunburst + виртуализированное дерево), dominatorTree.ts
│   ├── diff/               # SnapshotDiffView (+Δ/−Δ, виртуализация), localDiff.ts (diff отчёта в браузере)
│   ├── oql/                # OqlConsoleView (+ история запросов офлайн-отчёта)
│   ├── cpu/                # DotTraceCpuAndTimelineView, ProfileTimeline, FlameGraphView, OfflineProfilesView
│   ├── threads/            # ThreadsAndContentionView, DeadlockGraph, ContentionTable, ThreadMatrix
│   ├── gc/                 # GcLogAnalyzerView, GcCharts
│   └── reports/            # ReportModal: drag-and-drop импорт, экспорт JSON/HTML
├── hooks/                  # useNow, useClickOutside
└── utils/                  # format (байты, скорости, аптайм), categories (группы, цвета), gcRoots (тип корня)
```

Разработка: `npm run dev` (Vite на 5173, проксирует `/api` и `/ws` на бэкенд 8080). Node можно не ставить:
`mvn clean package` скачивает Node 20 в `backend/target/node`, собирает UI и кладёт его в jar.

**Контракт фронтенд ↔ бэкенд** проверяет `FrontendContractTest` (в `mvn verify`): каждый интерфейс и union в
`types.ts` должен совпадать с JSON-свойствами Jackson / константами enum Java-DTO того же имени, каждый вызов в
`endpoints.ts` — с методом, путём, query-параметрами, multipart-частью и полями тела маппинга контроллера, протокол
`/ws/live` — с обработчиком.

### Экраны глубокого анализа
* **Leak Heuristics Advisor** — слева диагнозы `InspectionFindingDto`, сгруппированные по severity (coral / amber /
  cyan) и категориям (WildFly / Jakarta EE, Hazelcast Client, JVM Collections); справа «Виновник и ущерб»
  (класс, поле, retained, % кучи, факты), граф пути до GC Root (тип корня: Thread / Static Field / CDI Singleton /
  ModuleClassLoader), `whyItHappens` и `howToFixCodeSnippet` с копированием.
* **Dominator Tree & Sunburst** — бэкенд отдаёт плоский топ объектов по retained size; клиент вкладывает их друг в
  друга по путям до GC Root (доминатор лежит на каждом пути к объекту, в том числе на кратчайшем), поэтому sunburst
  (подсистема → класс → объект → вложенные доминаторы) не считает байты дважды. Остаток кучи — серый сектор.
* **Snapshot Comparison** — `GET /api/snapshots/diff?includeShrinking=true&limit=5000`; в офлайн-отчёте тот же
  алгоритм (`localDiff.ts`, зеркало `SnapshotDiffService`) считается в браузере для любой пары снапшотов.

### Шаг 8: OQL, ссылки, группировки, стеки аллокаций
* `GcRootPathResolver`: `STRONG_ONLY` — указатели NetBeans (они пропускают `referent` у Weak/Soft/Final/Phantom);
  без сильного пути — обратный BFS с `referent`, шаги с `ReferenceKind`, текст «softly reachable only». `ALL_REFERENCES` —
  BFS по входящим ссылкам с лимитами (`inspector.oql.path-search-limit`).
* `OpenHeapRegistry` держит дампы с `keepForQueries`; `OqlExecutionService` — `OQLEngine` NetBeans на `SandboxedJavaScript`
  (Nashorn + `ClassFilter`, `engine`/`context` закрыты; регистрация через `EngineProvider`, проверка при каждом движке).
* `ClassLoaderGrouping`, `RetentionPathSampler` (пути удержания топ-классов, используются в diff), инспекции
  `EmptyAndSparseCollectionsInspector` и `BoxedPrimitivesOverheadInspector`, `JfrAllocationStackAggregator`.

### Шаг 9: CPU & Timeline (`profiler`, `ProfilerController` → `/api/profiles`)
* Данные цели — только через JMX: `JfrProfileRecorder` создаёт запись в целевой JVM (`FlightRecorderMXBean`),
  останавливает её и переносит `.jfr` потоком; `jdk.jfr.consumer.RecordingStream` не используется (он смотрел бы
  на JVM анализатора). `AsyncProfilerBridge` вызывает `asprof … -o jfr`, результат разбирается тем же парсером.
* `JfrProfileParser` (`RecordingFile`) → `ProfileBuilder` → `ProfileData`: интернированные фреймы и стеки, сэмплы
  CPU / NATIVE / WALL / ALLOC, события с длительностью (Park, MonitorEnter/Wait, Sleep, Socket/File I/O), GC-паузы,
  CPULoad, исключения. Нативные сэмплы внутри события того же потока помечаются `NATIVE_COVERED`: Wall-Clock не
  считает ожидание дважды. Веса: микросекунды для времени, байты для аллокаций.
* `CallTreeEngine` — flame graph (обрезка по `flame-min-share` / `flame-max-nodes`), hotspots (self — листовой фрейм,
  total — каждый фрейм раз на стек), butterfly (callers от ближайшего к листу вхождения, callees от корневого),
  diff двух деревьев по ключу `класс.метод`. `SubsystemClassifier` идёт от листа к корню: JDBC-драйверы и пулы →
  JDBC, `org.hibernate` → HIBERNATE, код приложения → BUSINESS, Undertow / сервлеты / RESTEasy → HTTP.
  `TimelineBuilder` раскладывает всё по колонкам, потоки пулов WildFly выбираются в дорожки первыми.
* Каждый запрос дерева принимает `from` / `to` (мс от начала профиля) и `thread` — это кросс-фильтр выделенного
  на таймлайне интервала. Frontend: `features/cpu/DotTraceCpuAndTimelineView.tsx` (форма, карточки профилей, вкладки),
  `ProfileTimeline` (SVG-дорожки с brush), `FlameGraphView` (обычный и differential), `HotspotsButterflyPanel`,
  `SubsystemsPanel`.

### Шаг 10: Threads & Contention, GC & Safepoints, единый отчёт
* **Только JMX цели.** `ThreadContentionAndSeriesService` работает с `ThreadMXBean` целевой JVM через
  `MBeanServerConnection` (`invoke` / `getAttribute` по `java.lang:type=Threading`, `ThreadInfo.from(CompositeData)`
  локально); live-GC — JFR-запись в цели через `FlightRecorderMXBean` (`JfrProfileRecorder` с настройками
  `JfrGcParser.settings()`), файл переносится по JMX и читается `RecordingFile`. `RecordingStream` нигде не
  используется. Свой PID отклоняется (`400 SELF_MONITORING_FORBIDDEN`).
* **Live (`/api/threads/{pid}/live`).** Сэмплер PID стартует при первом GET и живёт, пока UI его опрашивает
  (`live-idle-timeout`). Каждые `sample-interval` — `dumpAllThreads(false,false,depth)` → `ContentionAggregator`:
  ключ «класс lock'а + строка, где ждут», время — по дельтам `blockedTime` (`waitedTime` для
  `java.util.concurrent`-синхронизаторов), при выключенном contention monitoring — оценка по сэмплам (≈);
  потоки WildFly / Undertow (`default task-*`, `XNIO-*`) считаются отдельно. Каждые `deadlock-check-interval` —
  `findDeadlockedThreads` + `findMonitorDeadlockedThreads`, `DeadlockDetector` строит кольца
  «поток → lock → владелец», CPU потоков и счётчики. Флаги `ThreadContentionMonitoringEnabled` /
  `ThreadCpuTimeEnabled` включаются со счётчиком ссылок и возвращаются в исходное значение.
* **Серии (`/api/threads/series`).** N дампов `dumpAllThreads(true,true)` с интервалом (между ними — сэмплы
  контеншна) или jstack / `jcmd Thread.print` (`JstackParser`, несколько дампов в одном файле, лимит 256 MiB).
  `ThreadDumpSeriesAnalyzer`: интернированные стеки, матрица Thread × Dump (`sameAsPrevious` → «=»), зависшие
  потоки — тот же стек в `stuck-min-dumps`+ дампах подряд в RUNNABLE / BLOCKED, вид (CPU_LOOP, DB_QUERY, IO_WAIT,
  LOCK_WAIT) по стеку и приросту CPU; кластеры одинаковых стеков; deadlock-и по lock'ам дампа.
* **GC (`/api/gc`).** `UnifiedGcLogParser` — потоковый разбор `-Xlog:gc*,safepoint*` с любыми декорациями
  (`[time]`, `[uptime]` s/ms/ns, `[level]`, `[tags]`): G1 / Parallel / Serial / ZGC (в т. ч. generational `Y:`/`O:`)
  / Shenandoah, safepoint-строки JDK 11 и 17+; `.gz` распаковывается, `.jfr` распознаётся по сигнатуре `FLR`.
  `JfrGcParser` — `GarbageCollection`, `GCPhasePause`, `G1/PS/GCHeapSummary`, `SafepointBegin` /
  `SafepointStateSynchronization` / `ExecuteVMOperation`, `EvacuationFailed`, `ZAllocationStall`. Оба дают
  `GcLogData`; `GcLogAnalyzer` считает throughput = 1 − Σpause / покрытое время, max / avg / p95 / p99, allocation
  rate по росту кучи между сборками, promotion rate по росту old gen в young / mixed паузах, окна для графика,
  причины с советами и severity, safepoint-ы (TTSP против операции, порог `ttsp-warning`) и findings (низкий
  throughput, Full GC, System.gc(), Humongous, Metadata GC Threshold, evacuation failure, allocation stall,
  degenerated GC, долгий TTSP, рост live set…).
* **Отчёт формата 2.** `ReportExportRequestDto.includeDiagnostics` (по умолчанию да) добавляет в
  `SessionReportBundle` готовые профили (`CpuAndTimelineProfilerService.reportProfiles`), серии и live-снимок
  потоков, GC-анализы и историю OQL (`OqlExecutionService.history`: 50 запросов с первыми 20 строками).
  Отчёты формата 1 читаются (новые поля — пустые списки). `POST /api/reports/html` рендерит HTML из файла
  отчёта без импорта (офлайн-режим UI).
* **Frontend.** `features/threads` — `ThreadsAndContentionView` (live, серии, офлайн), `DeadlockGraph` (SVG-кольцо),
  `ContentionTable`, `ThreadMatrix`; `features/gc` — `GcLogAnalyzerView` (drop-зона, live-запись, KPI, findings,
  причины, safepoint-ы) и `GcCharts` (ECharts scatter / line). Сайдбар разделён на «Память» и «Производительность».
  Офлайн-отчёт показывает CPU-профили (`OfflineProfilesView`), историю OQL, потоки и GC из файла.

### Релизный контур
* `mvn clean package -Prelease` (профиль `release` в `backend/pom.xml`): UI всегда собирается (`-DskipUi`
  запрещён enforcer'ом), `spring-boot:build-info` → `META-INF/build-info.properties`, `git-commit-id-maven-plugin`
  → `git.properties` (ветка, коммит; `.git` на уровень выше `backend/`), `maven-assembly-plugin`
  (`src/assembly/release.xml`) → `target/jvm-memory-inspector-release.zip`.
* `GET /api/about` (`AboutController`) читает `BuildProperties` / `GitProperties`; без `-Prelease` версия берётся
  из манифеста. `GET /api/about/license`, `/api/about/notice` отдают `META-INF/LICENSE` и `NOTICE` из jar.
  В UI — статус-бар внизу (версия, коммит, бейдж *Apache 2.0 License*) и `AboutLicenseModal`.
* Зависимость `org-netbeans-lib-profiler` берётся из Maven Central; репозиторий `bits.netbeans.org/maven2`
  объявлен запасным после явно объявленного `central` (сейчас он отвечает 401, а первым в списке его спрашивали
  бы о каждой зависимости).

* Профиль `release` дополнительно копирует исполняемый jar в `target/jvm-memory-inspector-release.jar`
  (`maven-antrun-plugin`): отдельный файл релиза; внутри zip jar сохраняет имя `jvm-leak-inspector.jar`, его ищут
  скрипты запуска. Публикация: `.github/workflows/release.yml` (`gh release create`) и задача `release-job` в
  `.gitlab-ci.yml` (`release:` + `release-cli`) — на push в `release` и на теги `v*`.

## 4. Цветовая система (Tailwind `kraken.*`)

| Токен | HEX | Назначение |
|---|---|---|
| `kraken-bg` | `#1b1d23` | основной фон |
| `kraken-panel` | `#232730` | панели / сайдбар |
| `kraken-card` | `#2c313c` | карточки / hover |
| `kraken-border` | `#363c4a` | границы |
| `accent-cyan` | `#00e5ff` | активные элементы / info |
| `accent-emerald` (`mem-healthy`) | `#00c853` | здоровая память |
| `accent-amber` (`mem-suspect`) | `#ffab00` | подозрение на утечку |
| `accent-coral` (`mem-critical`) | `#ff3d71` | критическая утечка |
| `accent-violet` (`mem-meta`) | `#b388ff` | GC / Jakarta / Hazelcast метаданные |
