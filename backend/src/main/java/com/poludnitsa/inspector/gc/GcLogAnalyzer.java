package com.poludnitsa.inspector.gc;

import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.GcAnalysisSummaryDto;
import com.poludnitsa.inspector.api.dto.GcCauseStatDto;
import com.poludnitsa.inspector.api.dto.GcFindingDto;
import com.poludnitsa.inspector.api.dto.GcKpiDto;
import com.poludnitsa.inspector.api.dto.GcPauseCategory;
import com.poludnitsa.inspector.api.dto.GcPauseDto;
import com.poludnitsa.inspector.api.dto.GcRateSampleDto;
import com.poludnitsa.inspector.api.dto.SafepointEventDto;
import com.poludnitsa.inspector.api.dto.SafepointOperationStatDto;
import com.poludnitsa.inspector.api.dto.SafepointSummaryDto;
import com.poludnitsa.inspector.api.dto.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KPIs, rates, causes, safepoints and findings of a {@link GcLogData}, whatever its source and collector.
 *
 * <ul>
 *   <li><b>Throughput</b> = 1 − stop-the-world pause time / observed time.</li>
 *   <li><b>Allocation rate</b>: heap growth between the end of one collection and the start of the next.</li>
 *   <li><b>Promotion rate</b>: growth of the old generation during young / mixed pauses.</li>
 *   <li><b>Safepoints</b>: time to safepoint (TTSP) against the VM operation; a long TTSP means threads that do
 *   not reach a safepoint poll (counted loops, large array copies) or a host that swaps.</li>
 * </ul>
 */
public final class GcLogAnalyzer {

    public record Settings(int maxPausePoints, int rateWindows, double ttspWarningMs) {
    }

    private record HeapPoint(double endMs, double beforeMb, double afterMb, Double totalMb, Double promotedMb,
                             double pauseMs) {
    }

    private static final class CauseAcc {
        final Set<Integer> gcIds = new HashSet<>();
        int pauses;
        double totalMs;
        double maxMs;
        boolean full;
    }

    private GcLogAnalyzer() {
    }

    public static GcAnalysisDto analyze(GcAnalysisSummaryDto base, GcLogData data, Settings settings) {
        List<GcLogData.Pause> pauses = new ArrayList<>(data.pauses);
        pauses.sort(Comparator.comparingDouble(p -> p.uptimeMs));
        Map<Integer, String> cycleCauses = new HashMap<>();
        data.collections.forEach(c -> cycleCauses.putIfAbsent(c.gcId(), c.cause()));
        for (GcLogData.Pause pause : pauses) {
            String cycle = cycleCauses.get(pause.gcId);
            if (cycle != null && (pause.cause == null || pause.category == GcPauseCategory.CONCURRENT_PAUSE)) {
                pause.cause = cycle;
            }
        }

        // ---- pause statistics
        double total = 0;
        double max = 0;
        int young = 0;
        int mixed = 0;
        int full = 0;
        double[] durations = new double[pauses.size()];
        for (int i = 0; i < pauses.size(); i++) {
            GcLogData.Pause pause = pauses.get(i);
            durations[i] = pause.pauseMs;
            total += pause.pauseMs;
            max = Math.max(max, pause.pauseMs);
            switch (pause.category) {
                case YOUNG, HUMONGOUS -> young++;
                case MIXED -> mixed++;
                case FULL -> full++;
                default -> {
                }
            }
        }
        java.util.Arrays.sort(durations);
        double covered = data.covered();
        if (covered < total) {
            covered = total;
        }
        double throughput = covered > 0 ? Math.max(0, 100 * (1 - total / covered)) : 100;

        // ---- heap points: the heap after each collection and before the next
        List<HeapPoint> points = new ArrayList<>();
        boolean promotionKnown = false;
        for (GcLogData.Pause pause : pauses) {
            if (pause.heapBeforeMb != null && pause.heapAfterMb != null) {
                Double promoted = null;
                if (pause.oldBeforeMb != null && pause.oldAfterMb != null && (pause.category == GcPauseCategory.YOUNG
                        || pause.category == GcPauseCategory.MIXED || pause.category == GcPauseCategory.HUMONGOUS)) {
                    promoted = Math.max(0, pause.oldAfterMb - pause.oldBeforeMb);
                    promotionKnown = true;
                }
                points.add(new HeapPoint(pause.uptimeMs + pause.pauseMs, pause.heapBeforeMb, pause.heapAfterMb,
                        pause.heapTotalMb, promoted, pause.pauseMs));
            }
        }
        for (GcLogData.Collection collection : data.collections) {
            points.add(new HeapPoint(collection.uptimeMs(), collection.heapBeforeMb(), collection.heapAfterMb(),
                    collection.heapTotalMb(), null, 0));
        }
        points.sort(Comparator.comparingDouble(HeapPoint::endMs));
        Double allocationRate = null;
        Double promotionRate = null;
        List<GcRateSampleDto> rates = new ArrayList<>();
        if (points.size() >= 2) {
            double span = points.get(points.size() - 1).endMs() - points.get(0).endMs();
            double allocated = 0;
            double promoted = 0;
            for (int i = 1; i < points.size(); i++) {
                allocated += Math.max(0, points.get(i).beforeMb() - points.get(i - 1).afterMb());
                promoted += points.get(i).promotedMb() == null ? 0 : points.get(i).promotedMb();
            }
            if (span > 0) {
                allocationRate = round(allocated / span * 1000);
                promotionRate = promotionKnown ? round(promoted / span * 1000) : null;
            }
            rates = rateSamples(points, settings.rateWindows(), promotionKnown);
        }
        Double peakHeap = points.stream().map(p -> Math.max(p.beforeMb(), p.afterMb())).max(Double::compare)
                .map(v -> round(v.doubleValue())).orElse(null);
        Double avgAfter = points.isEmpty() ? null
                : round(points.stream().mapToDouble(HeapPoint::afterMb).average().orElse(0));

        // ---- causes
        Map<String, CauseAcc> causes = new LinkedHashMap<>();
        for (GcLogData.Pause pause : pauses) {
            CauseAcc acc = causes.computeIfAbsent(pause.cause != null ? pause.cause : pause.name, k -> new CauseAcc());
            acc.gcIds.add(pause.gcId);
            acc.pauses++;
            acc.totalMs += pause.pauseMs;
            acc.maxMs = Math.max(acc.maxMs, pause.pauseMs);
            acc.full |= pause.category == GcPauseCategory.FULL;
        }
        for (GcLogData.Collection collection : data.collections) {
            causes.computeIfAbsent(collection.cause(), k -> new CauseAcc()).gcIds.add(collection.gcId());
        }
        List<GcCauseStatDto> causeStats = new ArrayList<>();
        for (Map.Entry<String, CauseAcc> entry : causes.entrySet()) {
            CauseAcc acc = entry.getValue();
            String[] advice = advice(entry.getKey(), acc.full, data.regionSizeMb);
            causeStats.add(new GcCauseStatDto(entry.getKey(), Math.max(acc.gcIds.size(), 1), round(acc.totalMs),
                    round(acc.maxMs), total > 0 ? round(acc.totalMs / total * 100) : 0,
                    Severity.valueOf(advice[0]), advice[1]));
        }
        causeStats.sort(Comparator.comparingDouble(GcCauseStatDto::totalPauseMs)
                .thenComparingInt(GcCauseStatDto::count).reversed());

        SafepointSummaryDto safepoints = safepoints(data.safepoints, settings.ttspWarningMs());
        double safepointTotal = safepoints.totalMs();

        GcKpiDto kpi = new GcKpiDto(round2(throughput),
                round(total), round(max), pauses.isEmpty() ? 0 : round(total / pauses.size()),
                percentile(durations, 0.95), percentile(durations, 0.99), pauses.size(), young, mixed, full,
                allocationRate, promotionRate, peakHeap, avgAfter, data.safepoints.isEmpty() ? null : round(safepointTotal));

        List<GcFindingDto> findings = findings(data, kpi, causeStats, safepoints, points);

        GcAnalysisSummaryDto summary = new GcAnalysisSummaryDto(base.analysisId(), base.source(), base.pid(),
                base.name(), base.status(), base.startedAt(), base.durationSeconds(), base.progressMessage(),
                base.errorMessage(), data.collector, data.jvmVersion, pauses.size(), round(covered));
        return new GcAnalysisDto(summary, kpi, pausePoints(pauses, settings.maxPausePoints()), rates, causeStats,
                safepoints, findings, data.concurrentCycles, data.parsedLines, data.unparsedLines, data.truncated);
    }

    /** Allocation / promotion rate per interval between collections, merged into at most {@code windows} samples. */
    private static List<GcRateSampleDto> rateSamples(List<HeapPoint> points, int windows, boolean promotionKnown) {
        int intervals = points.size() - 1;
        int group = Math.max(1, (int) Math.ceil(intervals / (double) Math.max(1, windows)));
        List<GcRateSampleDto> result = new ArrayList<>();
        for (int start = 1; start <= intervals; start += group) {
            double allocated = 0;
            double promoted = 0;
            double pause = 0;
            int end = Math.min(intervals, start + group - 1);
            for (int i = start; i <= end; i++) {
                allocated += Math.max(0, points.get(i).beforeMb() - points.get(i - 1).afterMb());
                promoted += points.get(i).promotedMb() == null ? 0 : points.get(i).promotedMb();
                pause += points.get(i).pauseMs();
            }
            double ms = points.get(end).endMs() - points.get(start - 1).endMs();
            if (ms <= 0) {
                continue;
            }
            result.add(new GcRateSampleDto(round(points.get(end).endMs()), round(allocated / ms * 1000),
                    promotionKnown ? round(promoted / ms * 1000) : null, round(pause)));
        }
        return result;
    }

    private static SafepointSummaryDto safepoints(List<GcLogData.Safepoint> events, double warningMs) {
        double total = 0;
        double maxTtsp = 0;
        double ttspSum = 0;
        int slow = 0;
        Map<String, double[]> operations = new LinkedHashMap<>();
        for (GcLogData.Safepoint sp : events) {
            total += sp.totalMs();
            maxTtsp = Math.max(maxTtsp, sp.ttspMs());
            ttspSum += sp.ttspMs();
            if (sp.ttspMs() >= warningMs) {
                slow++;
            }
            double[] op = operations.computeIfAbsent(sp.operation(), k -> new double[4]);
            op[0]++;
            op[1] += sp.totalMs();
            op[2] = Math.max(op[2], sp.totalMs());
            op[3] = Math.max(op[3], sp.ttspMs());
        }
        List<SafepointOperationStatDto> ops = new ArrayList<>();
        operations.forEach((name, op) -> ops.add(new SafepointOperationStatDto(name, (int) op[0], round(op[1]),
                round(op[2]), round(op[3]))));
        ops.sort(Comparator.comparingDouble(SafepointOperationStatDto::totalMs).reversed());
        // the slowest safepoints, and every slow time-to-safepoint among the first ones
        Set<GcLogData.Safepoint> slowest = new LinkedHashSet<>();
        events.stream().sorted(Comparator.comparingDouble(GcLogData.Safepoint::totalMs).reversed()).limit(30)
                .forEach(slowest::add);
        events.stream().sorted(Comparator.comparingDouble(GcLogData.Safepoint::ttspMs).reversed()).limit(20)
                .forEach(slowest::add);
        List<SafepointEventDto> top = new ArrayList<>();
        for (GcLogData.Safepoint sp : slowest) {
            top.add(new SafepointEventDto(round(sp.uptimeMs()), sp.operation(), round3(sp.ttspMs()),
                    round3(sp.operationMs()), round3(sp.totalMs()), sp.ttspMs() >= warningMs));
        }
        top.sort(Comparator.comparingDouble(SafepointEventDto::totalMs).reversed());
        return new SafepointSummaryDto(events.size(), round(total), round3(maxTtsp),
                events.isEmpty() ? 0 : round3(ttspSum / events.size()), warningMs, slow,
                ops.subList(0, Math.min(30, ops.size())), top);
    }

    /** {severity, advice} of a GC cause. */
    static String[] advice(String cause, boolean full, Double regionSizeMb) {
        String c = cause == null ? "" : cause.toLowerCase(Locale.ROOT);
        if (c.contains("humongous")) {
            String threshold = regionSizeMb != null ? " (≥ " + fmt(regionSizeMb / 2) + " MB при регионе "
                    + fmt(regionSizeMb) + " MB)" : " (≥ половины региона G1)";
            return new String[] {"WARNING", "Humongous-объекты" + threshold + " выделяются сразу в старом поколении и"
                    + " провоцируют внеочередные GC: большие byte[]/char[] (буферы ответов, загрузка файлов),"
                    + " огромные JSON/XML DTO, выгрузки отчётов и массивы-бэкенды больших ArrayList/HashMap."
                    + " Стримьте данные частями, переиспользуйте буферы, задавайте разумный initialCapacity,"
                    + " либо увеличьте -XX:G1HeapRegionSize (до 32m), чтобы такие объекты перестали быть humongous."};
        }
        if (c.contains("metadata gc threshold") || c.contains("metaspace")) {
            return new String[] {"WARNING", "Metaspace достиг порога: классы продолжают загружаться."
                    + " Частые повторы после redeploy WildFly — признак утечки ClassLoader (ThreadLocal, драйверы JDBC,"
                    + " статические кеши). Проверьте вкладку ClassLoaders; для старта задайте -XX:MetaspaceSize."};
        }
        if (c.contains("system.gc")) {
            return new String[] {"WARNING", "Явный System.gc(): код приложения, RMI DGC"
                    + " (sun.rmi.dgc.server.gcInterval, по умолчанию раз в час) или освобождение DirectByteBuffer."
                    + " Отключите -XX:+DisableExplicitGC либо сделайте конкурентным -XX:+ExplicitGCInvokesConcurrent."};
        }
        if (c.contains("heap dump initiated") || c.contains("heap inspection initiated")) {
            return new String[] {"INFO", "GC, вызванный снятием heap dump или гистограммы классов"
                    + " (jmap, jcmd GC.class_histogram — в том числе Leak Radar этого инспектора)."};
        }
        if (c.contains("gclocker")) {
            return new String[] {"INFO", "GC отложен из-за JNI critical-секции (GetPrimitiveArrayCritical)."
                    + " Частые повторы — долгие нативные вызовы, удерживающие массивы."};
        }
        if (c.contains("to-space") || c.contains("evacuation failure")) {
            return new String[] {"CRITICAL", "Эвакуации не хватило места: увеличьте -Xmx или G1ReservePercent,"
                    + " проверьте рост старого поколения (утечка)."};
        }
        if (c.contains("allocation stall")) {
            return new String[] {"CRITICAL", "Потоки ждали память: ZGC не успевает за скоростью аллокаций."
                    + " Увеличьте -Xmx / -XX:SoftMaxHeapSize запас, ConcGCThreads, либо снизьте аллокации."};
        }
        if (c.contains("preventive")) {
            return new String[] {"WARNING", "Превентивная сборка G1: запаса свободных регионов мало,"
                    + " куча близка к исчерпанию."};
        }
        if (full) {
            return new String[] {"CRITICAL", "Full GC останавливает все потоки на всю кучу: старое поколение"
                    + " не успевает очищаться конкурентно. Возможна утечка памяти (растущий live set) или мал -Xmx;"
                    + " для G1 — снизьте InitiatingHeapOccupancyPercent или увеличьте ConcGCThreads."};
        }
        if (c.contains("g1 evacuation pause") || c.contains("allocation failure") || c.contains("allocation rate")
                || c.contains("warmup") || c.contains("proactive") || c.contains("high usage") || c.contains("timer")
                || c.contains("ergonomics") || c.contains("learning") || c.contains("free")) {
            return new String[] {"INFO", "Штатная сборка: заполнено молодое поколение / сработала эвристика"
                    + " сборщика. Частота определяется скоростью аллокаций."};
        }
        return new String[] {"INFO", null};
    }

    private static List<GcFindingDto> findings(GcLogData data, GcKpiDto kpi, List<GcCauseStatDto> causes,
                                               SafepointSummaryDto safepoints, List<HeapPoint> points) {
        List<GcFindingDto> findings = new ArrayList<>();
        if (kpi.pauseCount() == 0 && data.collections.isEmpty()) {
            findings.add(new GcFindingDto(Severity.INFO, "NO_GC_EVENTS", "Паузы GC не найдены",
                    "В источнике нет событий GC в формате Unified Logging / JFR (" + data.parsedLines
                            + " строк с декораторами, " + data.unparsedLines + " без них).",
                    "Запускайте с -Xlog:gc*,safepoint*:file=gc.log:time,uptime,level,tags (Java 11+)."));
            return findings;
        }
        if (kpi.throughputPercent() < 90) {
            findings.add(new GcFindingDto(Severity.CRITICAL, "LOW_THROUGHPUT", "Низкий throughput: "
                    + fmt(kpi.throughputPercent()) + "%", "Приложение стоит в паузах GC " + fmt(kpi.totalPauseMs())
                    + " ms из наблюдаемых.", "Цель — ≥ 95%. Увеличьте -Xmx, снизьте аллокации, проверьте Full GC."));
        } else if (kpi.throughputPercent() < 95) {
            findings.add(new GcFindingDto(Severity.WARNING, "LOW_THROUGHPUT", "Throughput ниже 95%: "
                    + fmt(kpi.throughputPercent()) + "%", "Паузы GC заметно отнимают время приложения.",
                    "Проверьте скорость аллокаций и размер молодого поколения."));
        }
        if (kpi.maxPauseMs() >= 1000) {
            findings.add(new GcFindingDto(Severity.CRITICAL, "LONG_PAUSE", "Пауза " + fmt(kpi.maxPauseMs()) + " ms",
                    "Самая длинная stop-the-world пауза больше секунды: запросы WildFly висят всё это время.",
                    "Ищите Full GC и humongous-аллокации; для низких пауз рассмотрите ZGC / Shenandoah."));
        } else if (kpi.maxPauseMs() >= 200) {
            findings.add(new GcFindingDto(Severity.WARNING, "LONG_PAUSE", "Пауза " + fmt(kpi.maxPauseMs()) + " ms",
                    "Максимальная пауза выше 200 ms (MaxGCPauseMillis G1 по умолчанию).",
                    "Сравните паузы по причинам ниже; проверьте TTSP в таблице safepoint-ов."));
        }
        long fullNotExplicit = data.pauses.stream().filter(p -> p.category == GcPauseCategory.FULL
                && (p.cause == null || !p.cause.toLowerCase(Locale.ROOT).contains("system.gc"))).count();
        if (fullNotExplicit > 0) {
            findings.add(new GcFindingDto(Severity.CRITICAL, "FULL_GC", fullNotExplicit + " Full GC",
                    "Полные сборки не из-за System.gc(): старое поколение заполнилось быстрее конкурентной очистки.",
                    "Сравните кучу после GC (график Heap Before/After): растущий минимум — утечка;"
                            + " иначе увеличьте -Xmx."));
        }
        for (GcCauseStatDto cause : causes) {
            String c = cause.cause().toLowerCase(Locale.ROOT);
            String code = c.contains("system.gc") ? "SYSTEM_GC" : c.contains("humongous") ? "HUMONGOUS"
                    : c.contains("metadata") ? "METADATA_THRESHOLD" : null;
            if (code != null) {
                findings.add(new GcFindingDto(Severity.WARNING, code, cause.cause() + ": " + cause.count() + " GC",
                        fmt(cause.totalPauseMs()) + " ms пауз, максимум " + fmt(cause.maxPauseMs()) + " ms.",
                        cause.advice()));
            }
        }
        if (data.evacuationFailures() > 0) {
            findings.add(new GcFindingDto(Severity.CRITICAL, "EVACUATION_FAILURE",
                    data.evacuationFailures() + " evacuation failure / to-space exhausted",
                    "G1 не нашёл свободных регионов для копирования живых объектов — пауза резко удлиняется.",
                    "Увеличьте -Xmx или -XX:G1ReservePercent; проверьте рост старого поколения."));
        }
        if (data.allocationStalls > 0) {
            findings.add(new GcFindingDto(Severity.CRITICAL, "ALLOCATION_STALL",
                    data.allocationStalls + " allocation stall", "Потоки ждали память суммарно "
                    + fmt(data.allocationStallMs) + " ms: ZGC не успевает за аллокациями.",
                    "Добавьте запас кучи, ConcGCThreads, либо снизьте скорость аллокаций."));
        }
        if (data.degenerated > 0) {
            findings.add(new GcFindingDto(Severity.WARNING, "DEGENERATED_GC", data.degenerated + " degenerated GC",
                    "Shenandoah прервал конкурентный цикл и доделал его в паузе.",
                    "Начинайте цикл раньше (ShenandoahMinFreeThreshold) или добавьте памяти."));
        }
        if (safepoints.slowTtspCount() > 0) {
            findings.add(new GcFindingDto(safepoints.maxTtspMs() >= 1000 ? Severity.CRITICAL : Severity.WARNING,
                    "LONG_TTSP", safepoints.slowTtspCount() + " safepoint-ов с долгим TTSP (до "
                    + fmt(safepoints.maxTtspMs()) + " ms)", "Время достижения safepoint ≥ "
                    + fmt(safepoints.ttspWarningMs()) + " ms: все потоки ждут самый медленный.",
                    "Причины: counted loops без safepoint poll (длинные int-циклы, JIT убрал проверки;"
                            + " -XX:+UseCountedLoopSafepoints / LoopStripMiningIter), большие arraycopy/clone,"
                            + " свопинг хоста (vmstat si/so, -XX:+AlwaysPreTouch), CPU throttling контейнера."));
        }
        if (kpi.allocationRateMbPerSec() != null && kpi.allocationRateMbPerSec() >= 1000) {
            findings.add(new GcFindingDto(Severity.WARNING, "HIGH_ALLOCATION_RATE", "Аллокации "
                    + fmt(kpi.allocationRateMbPerSec()) + " MB/s", "Высокая скорость аллокаций — частые young GC.",
                    "Найдите горячие аллокации в CPU & Timeline (режим Allocations)."));
        }
        if (kpi.promotionRateMbPerSec() != null && kpi.allocationRateMbPerSec() != null
                && kpi.allocationRateMbPerSec() > 0 && kpi.promotionRateMbPerSec() >= 10
                && kpi.promotionRateMbPerSec() / kpi.allocationRateMbPerSec() > 0.3) {
            findings.add(new GcFindingDto(Severity.WARNING, "HIGH_PROMOTION_RATE", "Промоушен "
                    + fmt(kpi.promotionRateMbPerSec()) + " MB/s", "Больше 30% аллокаций переживают young GC и уходят"
                    + " в старое поколение (преждевременное старение или растущий кеш).",
                    "Увеличьте молодое поколение / MaxTenuringThreshold; проверьте кеши и сессии."));
        }
        GcFindingDto growth = liveSetGrowth(points);
        if (growth != null) {
            findings.add(growth);
        }
        if (data.truncated) {
            findings.add(new GcFindingDto(Severity.INFO, "TRUNCATED", "Источник обрезан",
                    "Достигнут лимит inspector.gc-log.max-events; анализ построен по началу файла.", null));
        }
        findings.sort(Comparator.comparing(GcFindingDto::severity).reversed());
        return findings;
    }

    /** The heap after GC rising quarter by quarter: the live set grows (a leak candidate). */
    private static GcFindingDto liveSetGrowth(List<HeapPoint> points) {
        if (points.size() < 8) {
            return null;
        }
        int quarter = points.size() / 4;
        double[] floors = new double[4];
        for (int q = 0; q < 4; q++) {
            int end = q == 3 ? points.size() : (q + 1) * quarter;
            floors[q] = points.subList(q * quarter, end).stream().mapToDouble(HeapPoint::afterMb).min().orElse(0);
        }
        double capacity = points.stream().map(HeapPoint::totalMb).filter(t -> t != null).mapToDouble(t -> t).max()
                .orElse(floors[3]);
        boolean rising = floors[1] >= floors[0] && floors[2] >= floors[1] && floors[3] > floors[2];
        if (!rising || floors[3] - floors[0] < Math.max(10, capacity * 0.1)) {
            return null;
        }
        return new GcFindingDto(Severity.WARNING, "LIVE_SET_GROWTH", "Куча после GC растёт: " + fmt(floors[0])
                + " → " + fmt(floors[3]) + " MB", "Минимальный объём кучи после сборок увеличивается от четверти к"
                + " четверти наблюдения — объекты переживают GC и накапливаются.",
                "Подтвердите Leak Radar-ом и сравнением двух heap-дампов (Snapshots → Diff).");
    }

    /** Every pause when they fit, else the longest ones, every Full GC and an even sample of the rest. */
    private static List<GcPauseDto> pausePoints(List<GcLogData.Pause> pauses, int max) {
        List<GcLogData.Pause> chosen;
        if (pauses.size() <= max) {
            chosen = pauses;
        } else {
            Set<GcLogData.Pause> keep = new LinkedHashSet<>();
            pauses.stream().filter(p -> p.category == GcPauseCategory.FULL).limit(max / 4).forEach(keep::add);
            pauses.stream().sorted(Comparator.comparingDouble((GcLogData.Pause p) -> p.pauseMs).reversed())
                    .limit(max / 4).forEach(keep::add);
            int remaining = max - keep.size();
            double step = pauses.size() / (double) Math.max(1, remaining);
            for (double i = 0; i < pauses.size() && keep.size() < max; i += step) {
                keep.add(pauses.get((int) i));
            }
            chosen = new ArrayList<>(keep);
            chosen.sort(Comparator.comparingDouble(p -> p.uptimeMs));
        }
        List<GcPauseDto> result = new ArrayList<>(chosen.size());
        for (GcLogData.Pause p : chosen) {
            result.add(new GcPauseDto(p.gcId, round(p.uptimeMs), p.category, p.name, p.cause, round3(p.pauseMs),
                    round(p.heapBeforeMb), round(p.heapAfterMb), round(p.heapTotalMb)));
        }
        return result;
    }

    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return round3(sorted[Math.max(0, Math.min(sorted.length - 1, index))]);
    }

    private static Double round(Double value) {
        return value == null ? null : round(value.doubleValue());
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static String fmt(double value) {
        double rounded = round2(value);
        return value >= 100 || rounded == Math.rint(rounded) ? String.valueOf(Math.round(value)) : String.valueOf(rounded);
    }
}
