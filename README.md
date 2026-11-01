# JVM Memory & Leak Inspector

Автономный веб-инструмент в духе dotMemory для **Java 17+ микросервисов на WildFly (Jakarta EE 10) с Hazelcast
Client**, работающих на том же bare-metal хосте или VM. Находит Java-процессы на хосте, показывает память и GC в
реальном времени, ранжирует растущие классы по вероятности утечки, снимает heap dump без загрузки его в RAM и
объясняет утечки эвристиками, знающими WildFly, CDI, Hibernate, Undertow и Hazelcast, с примерами исправления.

* **Live Memory & Traffic** — пулы кучи, таймлайн GC, Leak Radar по `GC.class_histogram` (без Full GC).
* **Leak Heuristics Advisor** — 11 инспекций (ClassLoader деплоймента после redeploy, ThreadLocal в потоках пула,
  несовпадение CDI-скоупов, раздутый persistence context Hibernate, HTTP-сессии Undertow, сериализованные данные и
  слушатели Hazelcast, неограниченные коллекции, дубликаты строк и массивов, пустые и разреженные коллекции,
  упакованные примитивы): виновник, путь до GC Root, почему течёт и
  как исправить.
* **Dominator Tree & Sunburst** — плюс группировки By Package, By ClassLoader и By JBoss Module.
* **Snapshot Comparison** — что выросло и что освободилось между двумя снапшотами. Для выросших классов —
  пути удержания: через какие корни держится дельта.
* **OQL Console** — запросы `select … from … where …` (диалект VisualVM) к дампу, оставленному открытым, и пути
  до GC Root любого объекта с учётом или без weak / soft / phantom / finalizer-ссылок.
* **Кто создаёт объекты** — горячие точки и стеки аллокаций из JFR (`jdk.ObjectAllocationSample`, опционально TLAB-события)
  в деталях класса Leak Radar.
* **CPU & Timeline** (аналог dotTrace) — запись JFR в целевой JVM через её `FlightRecorderMXBean` или
  async-profiler (`cpu` / `wall` / `alloc`, Linux и macOS): таймлайн CPU, GC-пауз, блокировок, I/O и состояний
  потоков с выделением интервала мышью, flame graph с зумом и поиском, differential flame graph двух профилей,
  Hotspots с Butterfly (callers / callees) и разбивка времени по подсистемам: бизнес-код, JDBC / PostgreSQL,
  Hibernate, HTTP (Undertow, сервлеты), I/O, блокировки, GC.
* **Отчёты** — JSON (открывается в этом UI на любом компьютере, офлайн) и автономный HTML.

Лицензия — [Apache License 2.0](LICENSE); встроенные компоненты перечислены в [NOTICE](NOTICE).

## Архитектура

```
 браузер (127.0.0.1:8080)                     JVM инспектора (этот jar)                    целевые JVM (тот же хост)
┌────────────────────────┐  REST /api/*   ┌───────────────────────────────────┐  Attach API  ┌──────────────────┐
│ React 18 + TypeScript  │ ─────────────▶ │ Spring Boot 3.5, Java 17          │ ───────────▶ │ WildFly          │
│ Zustand, ECharts,      │  WS /ws/live   │ discovery · JMX · live-метрики    │ локальный JMX│ Hazelcast client │
│ TanStack Virtual       │ ◀───────────── │ leak radar · heap dump · индекс   │ ◀─────────── │ Spring Boot, ... │
└────────────────────────┘                │ NetBeans · инспекции · отчёты     │              └──────────────────┘
                                          └───────────────────────────────────┘
```

* Целевой процесс наблюдается **только через его MBean'ы** (`MemoryMXBean`, `MemoryPoolMXBean`,
  `GarbageCollectorMXBean`, `DiagnosticCommand` → `GC.class_histogram`, `FlightRecorderMXBean`,
  `HotSpotDiagnosticMXBean.dumpHeap`). В него ничего не внедряется.
* Heap dump разбирается Apache NetBeans Profiler API по индексам на диске (memory-mapped): дамп в несколько ГБ
  анализируется с кучей анализатора 1 ГБ. Перед дампом проверяется свободное место (≥ 2.5 × занятой кучи цели); сырой
  `.hprof` и индексы удаляются сразу после анализа.
* Базы данных нет: результат анализа снапшота хранится небольшим gzip-JSON в рабочем каталоге.

Подробности — [ARCHITECTURE.md](ARCHITECTURE.md).

```
backend/    приложение Spring Boot (Maven) — единственный артефакт: backend/target/jvm-leak-inspector.jar
frontend/   React UI, Maven собирает его внутрь jar
start-inspector.sh / start-inspector.bat   скрипты запуска
.gitlab-ci.yml                             релизный пайплайн (shell runner, без Docker)
```

## Системные требования

| | |
|---|---|
| Java | **JDK 17 или новее** (JRE не подойдёт: нужен модуль `jdk.attach`). Целевые процессы — Java 17+ (HotSpot). |
| Пользователь ОС | **Тот же пользователь ОС, что и у процесса WildFly / Hazelcast.** Механизм attach HotSpot подключается только к JVM того же пользователя: `sudo -u wildfly ./start-inspector.sh`. Из-под root к JVM других пользователей обычно не подключиться. |
| Целевые JVM | Без `-XX:+DisableAttachMechanism`. Каталог дампов должен быть доступен целевой JVM на запись и инспектору на чтение (не внутри systemd `PrivateTmp`). |
| WildFly | Attach запускает внутри WildFly платформенный JMX-агент; с JBoss Modules + jboss-logmanager для этого в `standalone.conf` нужны `-Djboss.modules.system.pkgs=org.jboss.byteman,org.jboss.logmanager` и `-Djava.util.logging.manager=org.jboss.logmanager.LogManager` с logmanager на boot class path. Процессы без этого показываются как недоступные для attach, с подсказкой. Альтернатива — удалённый JMX (`host:port`). |
| Память | Куча анализатора по умолчанию 256 МБ – 1 ГБ (`INSPECTOR_XMX`); для дампов в десятки ГБ — `-Xmx4g` и больше. |
| Диск | Во время анализа дампа — свободно 2.5 × занятой кучи цели в рабочем каталоге. |
| Сеть | Не нужна: UI и API слушают только `127.0.0.1`. С другой машины — через SSH-туннель: `ssh -L 8080:127.0.0.1:8080 user@host`. |
| Только для сборки | Maven 3.8+, доступ к Maven Central и nodejs.org (Node 20 сборка скачивает в `backend/target/node`). |

## Сборка из ветки `release`

```bash
git clone https://github.com/NoProblem2000/PoludnitsaMA.git
cd PoludnitsaMA
git checkout release
cd backend
mvn clean package -Prelease
```

Профиль `release` собирает продакшен-UI, пишет `META-INF/build-info.properties` и `git.properties`, кладёт `LICENSE`
и `NOTICE` в `META-INF/` jar'а и упаковывает дистрибутив:

```
backend/target/jvm-leak-inspector.jar               исполняемый jar (UI внутри)
backend/target/jvm-memory-inspector-release.zip
  jvm-memory-inspector-<version>/
    jvm-leak-inspector.jar  start-inspector.sh  start-inspector.bat  LICENSE  NOTICE  README.md
```

Другие варианты: `mvn clean package` — тот же jar без build/git-информации и без zip; `mvn verify -DskipUi` — только
бэкенд и тесты. С `-Prelease` флаг `-DskipUi` запрещён. Пайплайн `.gitlab-ci.yml` делает то же самое на каждый push
или merge в `release`: проверка лицензии и тесты, затем `mvn clean package -Prelease -DskipTests`; zip и jar
сохраняются артефактами задачи с тегом версии в названии.

## Установка и запуск

```bash
unzip jvm-memory-inspector-release.zip -d /opt
cd /opt/jvm-memory-inspector-*/
sudo -u wildfly ./start-inspector.sh
```

Открыть http://127.0.0.1:8080. В Windows — `start-inspector.bat` в консоли пользователя, под которым работают цели
(для службы WildFly под LocalSystem — консоль администратора).

Скрипты проверяют Java 17+ и `jdk.attach`, находят jar рядом с собой (или в `backend/target`) и запускают:

```
java -Xms256m -Xmx1024m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
     --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED   --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED \
     --add-opens java.management/sun.management=ALL-UNNAMED --add-exports java.management/sun.management=ALL-UNNAMED \
     --add-opens java.management/javax.management=ALL-UNNAMED \
     --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED \
     --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -jar jvm-leak-inspector.jar --server.port=8080
```

| Флаг | Зачем |
|---|---|
| `-Xms256m -Xmx1024m` | Куча анализатора. Индексы heap dump лежат в memory-mapped файлах, вне этой кучи. |
| `-XX:+ExitOnOutOfMemoryError` | Завершиться, а не работать дальше в сломанном состоянии; перезапуск — дело systemd или менеджера служб. |
| `--add-opens` / `--add-exports` | Модульная система Java 17: attach-провайдер, внутренности JMX и NIO-буферы индекса дампа. Сам инспектор использует только публичные API; флаги не дают библиотекам упасть с `InaccessibleObjectException`. Те же `Add-Opens` прописаны в манифесте jar, поэтому `java -jar` без флагов работает так же. |

Переменные окружения скриптов:

| Переменная | По умолчанию | |
|---|---|---|
| `JAVA_HOME` / `INSPECTOR_JAVA` | `java` из `PATH` | какой JDK использовать |
| `INSPECTOR_JAR` | рядом со скриптом, затем `backend/target` | какой jar запускать |
| `INSPECTOR_XMS` / `INSPECTOR_XMX` | `256m` / `1024m` | куча анализатора |
| `INSPECTOR_PORT` | `8080` | HTTP-порт (всегда на 127.0.0.1) |
| `INSPECTOR_OPTS` | | дополнительные опции JVM |

Любое свойство Spring Boot передаётся аргументом, например:

```bash
./start-inspector.sh --inspector.snapshots.work-dir=/data/inspector --inspector.live.histogram-interval=30s
```

| Свойство | По умолчанию | |
|---|---|---|
| `inspector.snapshots.work-dir` | `${java.io.tmpdir}/jvm-leak-inspector/snapshots` | дампы (временно) и сводки снапшотов |
| `inspector.snapshots.disk-space-factor` | `2.5` | свободное место перед дампом, × занятой кучи (не меньше 2.5) |
| `inspector.snapshots.dump-timeout` | `30m` | |
| `inspector.snapshots.max-snapshots` | `20` | самые старые завершённые снапшоты вытесняются |
| `inspector.live.sample-interval` | `1s` | опрос MBean'ов |
| `inspector.live.histogram-interval` | `10s` | гистограммы Leak Radar; удваивается до `histogram-max-interval` (`2m`), если гистограмма дольше `histogram-pause-budget` (`500ms`) |
| `inspector.oql.enabled` | `true` | разрешить открытые дампы и OQL |
| `inspector.oql.idle-timeout` | `30m` | открытый дамп закрывается и удаляется после простоя |
| `inspector.oql.max-open-heaps` | `1` | открытых дампов одновременно; лишний закрывает самый старый |
| `inspector.oql.query-timeout` | `60s` | запрос отменяется после этого |
| `inspector.jfr.tlab-events` | `false` | писать `ObjectAllocationInNewTLAB`/`OutsideTLAB` со стеками: полнее, но заметный оверхед |
| `inspector.profiler.work-dir` | `${java.io.tmpdir}/jvm-leak-inspector/profiles` | `.jfr` профиля, пока он разбирается (потом удаляется) |
| `inspector.profiler.cpu-sampling-interval` | `10ms` | период `jdk.ExecutionSample`; `native-sampling-interval` — `20ms` |
| `inspector.profiler.event-threshold` | `10ms` | порог Park / Monitor / Sleep / Socket / File в режиме Sampling; `tracing-threshold` — `1ms` |
| `inspector.profiler.max-duration` | `10m` | самая длинная запись |
| `inspector.profiler.max-profiles` | `8` | профилей в памяти; лишний вытесняет самый старый |
| `inspector.profiler.max-samples` | `3000000` | сэмплов и событий на профиль, остальное отбрасывается (профиль помечается «обрезан») |
| `inspector.profiler.async-profiler-path` | — | `asprof` или каталог async-profiler; иначе `ASYNC_PROFILER_HOME`, `/opt/async-profiler`, `PATH` |

## Безопасная работа в продакшене

* **OQL** выполняется как JavaScript (Nashorn) в JVM инспектора. Скрипты изолированы: доступны только классы модели
  кучи, `Java.type`, `Packages`, `java.*` и объект движка закрыты; при неактивной песочнице запросы отклоняются.
  Открытый дамп занимает диск (2.5 × кучи цели) до закрытия — кнопка «Закрыть дамп» или 30 минут простоя.
* **Quick Histogram** (`GC.class_histogram -all`): без Full GC, пауза safepoint — от миллисекунд до сотен мс.
* **Deep Heap Dump**: stop-the-world на всё время записи.
  *Safe Dump* (`live=false`) не вызывает Full GC — безопасный выбор рядом с heartbeat Hazelcast-клиента;
  *Exact Dump* (`live=true`) сначала делает Full GC: только живые объекты, точнее diff, но пауза дольше.
* **CPU-профиль через JFR** пишется в целевой JVM (`FlightRecorderMXBean.newRecording`), забирается потоком
  (`openStream` / `readStream`) и разбирается в анализаторе из файла. Оверхед Sampling — обычно 1–2 %; *Tracing*
  (события от 1 мс и каждое исключение со стеком) заметно дороже — включать на минуты, не на часы.
* **async-profiler** запускается как `asprof -e cpu|wall|alloc -d N -f profile.jfr <pid>`: тот же OS-пользователь, что
  у цели, для `cpu` нужен `kernel.perf_event_paranoid <= 1` (иначе async-profiler откатывается на таймерный режим без стеков ядра), в контейнере —
  `SYS_ADMIN` или `perf_event_open` в seccomp. На Windows недоступен.
* **Force GC** требует подтверждения: на больших кучах это пауза в секунды.

## Разработка

```bash
cd backend && mvn spring-boot:run          # API на 127.0.0.1:8080
cd frontend && npm ci && npm run dev       # UI на http://localhost:5173, проксирует /api и /ws на 8080
cd backend && mvn verify -DskipUi          # тесты, включая контрактный тест фронтенд ↔ бэкенд
```

## Лицензия

Copyright 2026 The PoludnitsaMA contributors. Распространяется по Apache License, Version 2.0 — см. [LICENSE](LICENSE)
и [NOTICE](NOTICE). Версия, коммит и лицензия запущенной сборки видны в статус-баре UI (кнопка *Apache 2.0 License*) и
по `GET /api/about`.
