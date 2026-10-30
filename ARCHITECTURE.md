# JVM Memory & Leak Inspector — архитектура

Standalone веб-приложение (аналог dotMemory) для анализа памяти Java 17+ процессов
(WildFly / Jakarta EE 10, Hazelcast Client, standalone Hazelcast), запущенных **на том же хосте**.

```
PoludnitsaMA/
├── backend/            Spring Boot 3.x (Java 17, Maven) — один исполняемый jar
├── frontend/           React 18 + TypeScript + Vite + Tailwind
└── ARCHITECTURE.md
```

В production фронтенд собирается в `backend/target/classes/static` (Maven-профиль `bundle-ui`),
и на сервер копируется **один** jar: `java -jar jvm-leak-inspector.jar`.

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
 │  │                     │       └─► STOMP /topic/metrics/{pid}  ───────────► │──┼─► Browser
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
`newRecording → startRecording → stopRecording → openStream/readStream` → файл на диске →
офлайн-разбор через `jdk.jfr.consumer.RecordingFile` (читает файл, а не текущую JVM).

---

## 2. Backend — пакеты (`com.poludnitsa.inspector`)

| Пакет | Ответственность |
|---|---|
| `InspectorApplication` | Точка входа Spring Boot. |
| `config` | `InspectorProperties` (`@ConfigurationProperties`: интервалы семплинга, каталог дампов, коэффициент запаса диска 2.5), `WebSocketConfig` (STOMP, simple broker `/topic`), `JacksonConfig`, `WebConfig` (SPA fallback на `index.html`, CORS для `vite dev`). |
| `api.dto` | Все DTO (Java `record`) и enum'ы контракта backend ↔ frontend. **Единственный** пакет, который «видит» фронтенд. |
| `api.rest` | REST-контроллеры: `ProcessController` (`/api/processes`), `LiveController` (start/stop семплинга), `SnapshotController` (`/api/snapshots`: снять дамп, загрузить .hprof, статус индексации), `HeapController` (dominator tree, instances, GC root paths — постранично), `InspectionController`, `ReportController` (экспорт/импорт JSON/HTML). `GlobalExceptionHandler` → `ApiErrorDto`. |
| `api.ws` | Публикация в STOMP-топики: `/topic/metrics/{pid}`, `/topic/snapshots/{id}/progress`, `/topic/inspections/{id}`. |
| `discovery` | `JvmProcessScanner` (`VirtualMachine.list()` + `ProcessHandle`), `ProcessClassifier` (WILDFLY: `jboss-modules.jar` / `org.jboss.as.*`; HAZELCAST: `com.hazelcast.core.server.HazelcastMemberStarter`, `hazelcast*.jar`; иначе OTHER_JAVA). Исключает собственный PID. |
| `jmx` | `JmxConnectionManager` — attach по PID, `startLocalManagementAgent()`, кеш `JMXConnector` на PID, реконнект, закрытие при остановке процесса. `TargetMBeans` — типизированные прокси: `MemoryMXBean`, `MemoryPoolMXBean`, `GarbageCollectorMXBean`, `HotSpotDiagnosticMXBean`, `DiagnosticCommandMBean` (`gcClassHistogram`), `FlightRecorderMXBean`. |
| `live` | `LiveMetricsSampler` (`@Scheduled`, раз в N сек), `ClassHistogramParser` (разбор текста `GC.class_histogram`), `HistogramRingBuffer` (скользящее окно снимков на PID), `ClassGrowthAnalyzer` (линейная регрессия bytes/min, монотонность роста, рост после old-GC) → `LeakProbabilityScorer` (0..100). |
| `jfr` | `RemoteJfrService` — управление записью **в целевом** процессе через `FlightRecorderMXBean`, выгрузка чанков в файл; `JfrFileAnalyzer` — `RecordingFile` (allocation samples, `jdk.OldObjectSample`, GC pauses). |
| `dump` | `HeapDumpService` — `DiskSpaceGuard` (`usableSpace > 2.5 × heapUsed` целевой JVM, иначе 507), `HotSpotDiagnosticMXBean.dumpHeap(path, live)`: `live=false` — без Full GC (безопасно для Hazelcast heartbeat), `live=true` — с Full GC (точнее, но STW-пауза). Предупреждение UI об ожидаемой паузе. |
| `heap` | `HprofIndexService` — `HeapFactory.createHeap(file)` (NetBeans profiler lib: дисковые индексы + mmap, без загрузки .hprof в RAM), асинхронная индексация в отдельном executor'е; `SnapshotRegistry` (in-memory: id → `Heap` + summary); `DominatorTreeService` (retained size, ленивые дети, пагинация); `GcRootPathResolver` (кратчайший путь до GC root, с ограничением глубины); `PackageCategorizer`. |
| `inspection` | SPI `HeapInspection` (`id()`, `category()`, `run(HeapContext) → List<InspectionFindingDto>`), `InspectionEngine`. Правила по подпакетам: `rules.jakarta` (ThreadLocal leak в пуле `default-threads`, ClassLoader leak после redeploy — `ModuleClassLoader` без живого deployment, рост `HttpSession`, CDI `@Dependent` в `@ApplicationScoped`, незакрытые `EntityManager`), `rules.hazelcast` (near-cache без eviction, `ClientMessage`/invocation backlog, неснятые `EntryListener`/`MembershipListener`, `HazelcastInstance` на запрос), `rules.jvm` (дубликаты `String`, пустые коллекции, `byte[]` overhead, Finalizer queue, разреженные `HashMap`, DirectByteBuffer). |
| `report` | `ReportExporter` (JSON — Jackson; HTML — самодостаточный шаблон с встроенными данными), `ReportImporter` (загрузка JSON-отчёта для офлайн-просмотра без .hprof). |
| `session` | `InspectorSession` — всё состояние текущего запуска в памяти (без БД): подключённые PID, буферы метрик, снапшоты, findings. TTL-очистка временных индексов на диске. |
| `common` | Исключения (`TargetNotAttachableException`, `InsufficientDiskSpaceException`), `Bytes` (форматирование), `Ids`. |

### Важные эксплуатационные детали
* Attach API работает только от **того же OS-пользователя**, что и целевая JVM. Запускать анализатор под пользователем `wildfly`/`hazelcast` (или отдельными инстансами).
* WildFly (JBoss Modules + jboss-logmanager): для старта локального management-агента в `standalone.conf` обычно нужно
  `-Djboss.modules.system.pkgs=org.jboss.byteman,org.jboss.logmanager` и `-Djava.util.logging.manager=org.jboss.logmanager.LogManager`
  + logmanager на bootclasspath. Сканер помечает такие процессы как `attachable=false` с подсказкой.
* Целевые JVM не должны иметь `-XX:+DisableAttachMechanism`.
* Дамп пишется **целевой JVM** — каталог дампов должен быть доступен ей на запись и анализатору на чтение.
* Индексы NetBeans создаются рядом с .hprof (`*.hprof.nbcache`) — учитываются в `DiskSpaceGuard`.

---

## 3. Frontend — структура (`frontend/src`)

```
src/
├── main.tsx, App.tsx, index.css
├── api/
│   ├── types.ts            # TS-зеркало Java DTO (1:1 с api.dto)
│   ├── http.ts             # fetch-обёртка, ApiError
│   ├── stomp.ts            # @stomp/stompjs клиент, подписки на /topic/*
│   └── endpoints/          # processes.ts, snapshots.ts, heap.ts, inspections.ts, reports.ts
├── store/                  # Zustand
│   ├── processStore.ts     # список JVM, выбранный PID
│   ├── liveStore.ts        # ring-buffer метрик для графиков (без ре-рендера всего дерева)
│   ├── snapshotStore.ts    # снапшоты, прогресс индексации
│   └── inspectionStore.ts  # findings, фильтры по severity/category
├── theme/
│   └── echartsTheme.ts     # тема ECharts в палитре GitKraken
├── components/
│   ├── layout/             # AppShell, Sidebar, TopBar, StatusBar
│   ├── common/             # Card, Badge(Severity), ByteSize, EmptyState, Spinner, SplitPane
│   ├── charts/             # EChart (базовая обёртка с resize/dispose), HeapTimeline, GcPauseChart, RetainedSunburst
│   └── tables/             # VirtualTable (@tanstack/react-virtual), колонки, сортировка
├── features/
│   ├── processes/          # ProcessList, ProcessCard, AttachHint
│   ├── live/               # LiveDashboard, PoolGauges, GrowingClassesTable
│   ├── snapshots/          # TakeSnapshotDialog (live/non-live, disk check), SnapshotList, Upload
│   ├── heap/               # DominatorTree, InstancesView, GcRootPathView
│   ├── inspections/        # FindingsList, FindingDetails (why + fix snippet)
│   └── reports/            # Export/Import
├── hooks/                  # useStompTopic, useInterval, useElementSize
└── utils/                  # formatBytes, formatDuration, packageCategoryColor
```

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
