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
│   ├── http.ts             # fetch-обёртка, ApiError
│   ├── liveSocket.ts       # WebSocket /ws/live: subscribe/unsubscribe, авто-реконнект
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
