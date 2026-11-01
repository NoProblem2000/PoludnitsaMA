package com.poludnitsa.inspector.report;

import static com.poludnitsa.inspector.report.HtmlReportRenderer.esc;

import com.poludnitsa.inspector.api.dto.DeadlockDto;
import com.poludnitsa.inspector.api.dto.DeadlockNodeDto;
import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.GcCauseStatDto;
import com.poludnitsa.inspector.api.dto.GcFindingDto;
import com.poludnitsa.inspector.api.dto.GcKpiDto;
import com.poludnitsa.inspector.api.dto.GcPauseDto;
import com.poludnitsa.inspector.api.dto.HotspotDto;
import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.MonitorContentionDto;
import com.poludnitsa.inspector.api.dto.OqlHistoryEntryDto;
import com.poludnitsa.inspector.api.dto.ProfileMetricReportDto;
import com.poludnitsa.inspector.api.dto.ProfileReportDto;
import com.poludnitsa.inspector.api.dto.ProfileSummaryDto;
import com.poludnitsa.inspector.api.dto.SafepointEventDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.api.dto.StackClusterDto;
import com.poludnitsa.inspector.api.dto.StuckThreadDto;
import com.poludnitsa.inspector.api.dto.SubsystemShareDto;
import com.poludnitsa.inspector.api.dto.ThreadCellDto;
import com.poludnitsa.inspector.api.dto.ThreadLiveDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesRowDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Report sections of the diagnostics of format 2: CPU &amp; Timeline profiles, threads &amp; contention, GC &amp;
 * safepoints and OQL queries. Static HTML and inline SVG only, every text escaped.
 */
final class DiagnosticsHtmlSections {

    private static final int ROWS = 30;
    private static final int MATRIX_ROWS = 80;

    private DiagnosticsHtmlSections() {
    }

    // ---------------------------------------------------------------- CPU

    static void profiles(StringBuilder html, List<ProfileReportDto> profiles) {
        for (ProfileReportDto profile : profiles) {
            ProfileSummaryDto s = profile.summary();
            html.append("<section><h2>CPU &amp; Timeline — ").append(esc(s.profileId())).append("</h2><p class=\"muted\">")
                    .append(esc(s.source().name())).append(" · ").append(esc(s.displayName())).append(" (PID ")
                    .append(s.pid()).append(") · ").append(s.recordedMs() / 1000).append(" s · ")
                    .append(String.format(Locale.ROOT, "%,d", s.sampleCount())).append(" сэмплов · ")
                    .append(s.threadCount()).append(" потоков").append(s.tracing() ? " · tracing" : "")
                    .append("</p>");
            for (ProfileMetricReportDto metric : profile.metrics()) {
                html.append("<div class=\"panel\"><h3>").append(esc(metric.metric().name())).append("</h3>");
                if (metric.subsystems() != null) {
                    for (SubsystemShareDto share : metric.subsystems().subsystems()) {
                        if (share.percent() < 0.1) {
                            continue;
                        }
                        html.append("<div class=\"bar-row\"><span class=\"bar-label\">").append(esc(share.label()))
                                .append("</span><span class=\"bar\"><span class=\"fill g-3\" style=\"width:")
                                .append(pct(share.percent())).append("%\"></span></span><span class=\"bar-value\">")
                                .append(String.format(Locale.ROOT, "%.1f %%", share.percent())).append("</span></div>");
                    }
                }
                html.append("<table><thead><tr><th>Метод</th><th>Подсистема</th><th class=\"num\">Self %</th>")
                        .append("<th class=\"num\">Total %</th><th class=\"num\">Self сэмплов</th></tr></thead><tbody>");
                for (HotspotDto hot : metric.hotspots().subList(0, Math.min(ROWS, metric.hotspots().size()))) {
                    html.append("<tr><td><code>").append(esc(hot.className())).append('.').append(esc(hot.methodName()))
                            .append("</code></td><td>").append(hot.subsystem() == null ? "" : esc(hot.subsystem().name()))
                            .append("</td><td class=\"num\">").append(String.format(Locale.ROOT, "%.1f", hot.selfPercent()))
                            .append("</td><td class=\"num\">").append(String.format(Locale.ROOT, "%.1f", hot.totalPercent()))
                            .append("</td><td class=\"num\">").append(String.format(Locale.ROOT, "%,d", hot.selfSamples()))
                            .append("</td></tr>");
                }
                html.append("</tbody></table></div>");
            }
            html.append("</section>\n");
        }
    }

    // ---------------------------------------------------------------- threads

    static void threadsLive(StringBuilder html, ThreadLiveDto live) {
        if (live == null) {
            return;
        }
        html.append("<section><h2>Threads &amp; Contention (онлайн)</h2><p class=\"muted\">PID ").append(live.pid())
                .append(" · ").append(live.threadCount()).append(" потоков · ").append(live.samples())
                .append(" сэмплов каждые ").append(live.sampleIntervalMs()).append(" ms · contention monitoring ")
                .append(live.contentionMonitoring() ? "включён (время точное)" : "недоступен (время — оценка)")
                .append("</p><div class=\"cards\">");
        for (Map.Entry<JvmThreadState, Integer> state : live.states().entrySet()) {
            card(html, state.getKey().name(), String.valueOf(state.getValue()),
                    state.getKey() == JvmThreadState.BLOCKED && state.getValue() > 0 ? "warning" : "info");
        }
        card(html, "Deadlock-ов", String.valueOf(live.deadlocks().size()),
                live.deadlocks().isEmpty() ? "healthy" : "critical");
        html.append("</div>");
        deadlocks(html, live.deadlocks());
        contention(html, live.contention());
        html.append("</section>\n");
    }

    static void threadSeries(StringBuilder html, List<ThreadSeriesAnalysisDto> series) {
        for (ThreadSeriesAnalysisDto analysis : series) {
            var s = analysis.summary();
            html.append("<section><h2>Серия thread dump-ов — ").append(esc(s.name())).append("</h2><p class=\"muted\">")
                    .append(esc(s.source().name())).append(" · ").append(s.dumpsTaken()).append(" дампов")
                    .append(s.intervalMs() != null ? " · интервал " + s.intervalMs() + " ms" : "").append(" · ")
                    .append(s.threadCount()).append(" потоков · зависших ").append(s.stuckCount())
                    .append(" · кластеров ").append(s.clusterCount()).append("</p>");
            deadlocks(html, analysis.deadlocks());
            if (!analysis.stuck().isEmpty()) {
                html.append("<div class=\"panel\"><h3>Зависшие потоки</h3><table><thead><tr><th>Поток</th>")
                        .append("<th>Состояние</th><th>Причина</th><th class=\"num\">Дампы</th><th>Стек</th>")
                        .append("</tr></thead><tbody>");
                for (StuckThreadDto stuck : analysis.stuck().subList(0, Math.min(ROWS, analysis.stuck().size()))) {
                    html.append("<tr><td><code>").append(esc(stuck.name())).append("</code></td><td>")
                            .append(state(stuck.state())).append("</td><td>").append(esc(stuck.reason()))
                            .append("</td><td class=\"num\">#").append(stuck.fromDump() + 1).append("–#")
                            .append(stuck.toDump() + 1).append("</td><td class=\"path-cell\"><code>");
                    stuck.stack().stream().limit(6).forEach(f -> html.append(esc(f.className())).append('.')
                            .append(esc(f.methodName())).append(":").append(f.lineNumber()).append("<br>"));
                    html.append("</code></td></tr>");
                }
                html.append("</tbody></table></div>");
            }
            matrix(html, analysis);
            if (!analysis.clusters().isEmpty()) {
                html.append("<div class=\"panel\"><h3>Кластеры одинаковых стеков (последний дамп)</h3><table><thead>")
                        .append("<tr><th class=\"num\">Потоков</th><th>Состояние</th><th>Верхний фрейм</th>")
                        .append("<th>Потоки</th></tr></thead><tbody>");
                for (StackClusterDto c : analysis.clusters().subList(0, Math.min(ROWS, analysis.clusters().size()))) {
                    html.append("<tr><td class=\"num\">").append(c.threadCount()).append("</td><td>")
                            .append(state(c.state())).append("</td><td><code>").append(esc(c.topFrame()))
                            .append("</code></td><td class=\"path-cell\"><code>")
                            .append(esc(String.join(", ", c.threads().subList(0, Math.min(8, c.threads().size())))))
                            .append(c.threads().size() > 8 ? " …" : "").append("</code></td></tr>");
                }
                html.append("</tbody></table></div>");
            }
            contention(html, analysis.contention());
            html.append("</section>\n");
        }
    }

    private static void matrix(StringBuilder html, ThreadSeriesAnalysisDto analysis) {
        List<ThreadSeriesRowDto> rows = analysis.rows().stream().filter(r -> !r.idle() || r.stuck()).toList();
        if (rows.isEmpty()) {
            return;
        }
        html.append("<div class=\"panel\"><h3>Матрица состояний (поток × дамп, «=» — стек как в предыдущем)</h3>")
                .append("<table class=\"matrix\"><thead><tr><th>Поток</th>");
        analysis.dumps().forEach(d -> html.append("<th class=\"num\">#").append(d.index() + 1).append("</th>"));
        html.append("</tr></thead><tbody>");
        for (ThreadSeriesRowDto row : rows.subList(0, Math.min(MATRIX_ROWS, rows.size()))) {
            html.append("<tr><td><code>").append(esc(row.name())).append("</code>")
                    .append(row.stuck() ? " <span class=\"badge sev-critical\">stuck</span>" : "").append("</td>");
            for (ThreadCellDto cell : row.cells()) {
                if (cell == null) {
                    html.append("<td class=\"cell\"></td>");
                    continue;
                }
                html.append("<td class=\"cell st-").append(cell.state().name().toLowerCase(Locale.ROOT))
                        .append("\" title=\"").append(esc(cell.topFrame())).append("\">")
                        .append(cell.sameAsPrevious() ? "=" : abbreviation(cell.state())).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>").append(rows.size() > MATRIX_ROWS ? "<p class=\"muted\">Показаны первые "
                + MATRIX_ROWS + " из " + rows.size() + " активных потоков.</p>" : "").append("</div>");
    }

    private static void deadlocks(StringBuilder html, List<DeadlockDto> deadlocks) {
        for (DeadlockDto deadlock : deadlocks) {
            html.append("<article class=\"finding sev-critical\"><div class=\"finding-head\"><span class=\"badge ")
                    .append("sev-critical\">DEADLOCK</span><span class=\"badge meta-badge\">")
                    .append(esc(deadlock.kind().name())).append("</span></div><h3>Взаимная блокировка ")
                    .append(deadlock.cycle().size()).append(" потоков</h3><ul class=\"evidence\">");
            for (DeadlockNodeDto node : deadlock.cycle()) {
                html.append("<li><code>").append(esc(node.threadName())).append("</code> ждёт <code>")
                        .append(esc(node.waitingForLock())).append("</code>, который держит <code>")
                        .append(esc(node.heldByThreadName())).append("</code>");
                if (!node.stack().isEmpty()) {
                    var top = node.stack().get(0);
                    html.append(" — в <code>").append(esc(top.className())).append('.').append(esc(top.methodName()))
                            .append(":").append(top.lineNumber()).append("</code>");
                }
                html.append("</li>");
            }
            html.append("</ul></article>");
        }
    }

    private static void contention(StringBuilder html, List<MonitorContentionDto> contention) {
        if (contention.isEmpty()) {
            return;
        }
        html.append("<div class=\"panel\"><h3>Monitor Contention Hotspots</h3><table><thead><tr><th>Lock</th>")
                .append("<th>Где ждут</th><th class=\"num\">Blocked, ms</th><th class=\"num\">Раз</th>")
                .append("<th class=\"num\">Потоков</th><th>Держатель</th></tr></thead><tbody>");
        for (MonitorContentionDto c : contention.subList(0, Math.min(ROWS, contention.size()))) {
            html.append("<tr><td><code>").append(esc(c.lockClass())).append("</code> <span class=\"badge meta-badge\">")
                    .append(esc(c.lockKind().name())).append("</span></td><td class=\"path-cell\"><code>")
                    .append(esc(c.applicationFrame() != null ? c.applicationFrame() : c.blockedAt()))
                    .append("</code></td><td class=\"num\">").append(c.timeMeasured() ? "" : "≈")
                    .append(String.format(Locale.ROOT, "%,.0f", c.blockedTimeMs())).append("</td><td class=\"num\">")
                    .append(c.blockedCount()).append("</td><td class=\"num\">").append(c.threads())
                    .append(c.serverThreads() > 0 ? " (" + c.serverThreads() + " worker)" : "").append("</td><td><code>")
                    .append(esc(String.join(", ", c.topOwners()))).append("</code></td></tr>");
        }
        html.append("</tbody></table></div>");
    }

    // ---------------------------------------------------------------- GC

    static void gc(StringBuilder html, List<GcAnalysisDto> analyses) {
        for (GcAnalysisDto analysis : analyses) {
            var s = analysis.summary();
            GcKpiDto k = analysis.kpi();
            html.append("<section><h2>GC &amp; Safepoints — ").append(esc(s.name())).append("</h2><p class=\"muted\">")
                    .append(esc(s.source().name())).append(" · ").append(esc(s.collector() == null ? "?" : s.collector()))
                    .append(s.jvmVersion() != null ? " · JVM " + esc(s.jvmVersion()) : "").append(" · ")
                    .append(String.format(Locale.ROOT, "%.1f", s.coveredMs() / 1000)).append(" s наблюдения</p>")
                    .append("<div class=\"cards\">");
            card(html, "Throughput", String.format(Locale.ROOT, "%.2f %%", k.throughputPercent()),
                    k.throughputPercent() < 90 ? "critical" : k.throughputPercent() < 95 ? "warning" : "healthy");
            card(html, "Max pause", ms(k.maxPauseMs()), k.maxPauseMs() >= 1000 ? "critical"
                    : k.maxPauseMs() >= 200 ? "warning" : "healthy");
            card(html, "Avg pause", ms(k.avgPauseMs()), "info");
            card(html, "Allocation rate", rate(k.allocationRateMbPerSec()), "info");
            card(html, "Promotion rate", rate(k.promotionRateMbPerSec()), "meta");
            card(html, "Пауз (young / mixed / full)", k.pauseCount() + " (" + k.youngCount() + " / " + k.mixedCount()
                    + " / " + k.fullCount() + ")", k.fullCount() > 0 ? "critical" : "info");
            html.append("</div>");
            for (GcFindingDto finding : analysis.findings()) {
                String tone = finding.severity().name().toLowerCase(Locale.ROOT);
                html.append("<article class=\"finding sev-").append(tone).append("\"><div class=\"finding-head\">")
                        .append("<span class=\"badge sev-").append(tone).append("\">").append(severity(finding.severity()))
                        .append("</span><span class=\"rule\">").append(esc(finding.code())).append("</span></div><h3>")
                        .append(esc(finding.title())).append("</h3><p class=\"why\">").append(esc(finding.detail()))
                        .append("</p>").append(finding.advice() == null ? "" : "<p class=\"why\"><b>Что делать:</b> "
                                + esc(finding.advice()) + "</p>").append("</article>");
            }
            scatter(html, analysis.pauses());
            html.append("<div class=\"panel\"><h3>Причины GC</h3><table><thead><tr><th>Причина</th>")
                    .append("<th class=\"num\">GC</th><th class=\"num\">Паузы, ms</th><th class=\"num\">Max, ms</th>")
                    .append("<th class=\"num\">% пауз</th><th>Совет</th></tr></thead><tbody>");
            for (GcCauseStatDto cause : analysis.causes()) {
                html.append("<tr><td>").append(esc(cause.cause())).append(cause.severity() != Severity.INFO
                                ? " <span class=\"badge sev-" + cause.severity().name().toLowerCase(Locale.ROOT) + "\">"
                                + severity(cause.severity()) + "</span>" : "")
                        .append("</td><td class=\"num\">").append(cause.count()).append("</td><td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%,.1f", cause.totalPauseMs())).append("</td><td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%,.1f", cause.maxPauseMs())).append("</td><td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%.1f", cause.percentOfPauses())).append("</td><td class=\"why\">")
                        .append(esc(cause.advice())).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
            var sp = analysis.safepoints();
            if (sp != null && sp.count() > 0) {
                html.append("<div class=\"panel\"><h3>Самые медленные safepoint-ы</h3><p class=\"muted\">")
                        .append(sp.count()).append(" safepoint-ов, ").append(ms(sp.totalMs())).append(" всего · TTSP max ")
                        .append(ms(sp.maxTtspMs())).append(", среднее ").append(ms(sp.avgTtspMs())).append(" · порог ")
                        .append(ms(sp.ttspWarningMs())).append("</p><table><thead><tr><th class=\"num\">Uptime, s</th>")
                        .append("<th>Операция</th><th class=\"num\">TTSP, ms</th><th class=\"num\">Операция, ms</th>")
                        .append("<th class=\"num\">Всего, ms</th></tr></thead><tbody>");
                for (SafepointEventDto e : sp.slowest().subList(0, Math.min(ROWS, sp.slowest().size()))) {
                    html.append("<tr><td class=\"num\">").append(String.format(Locale.ROOT, "%.3f", e.uptimeMs() / 1000))
                            .append("</td><td><code>").append(esc(e.operation())).append("</code></td><td class=\"num")
                            .append(e.slowTtsp() ? " hot" : "").append("\">")
                            .append(String.format(Locale.ROOT, "%.3f", e.ttspMs())).append("</td><td class=\"num\">")
                            .append(String.format(Locale.ROOT, "%.3f", e.operationMs())).append("</td><td class=\"num\">")
                            .append(String.format(Locale.ROOT, "%.3f", e.totalMs())).append("</td></tr>");
                }
                html.append("</tbody></table></div>");
            }
            html.append("</section>\n");
        }
    }

    /** Pause duration over uptime, one dot per pause, coloured by category. */
    private static void scatter(StringBuilder html, List<GcPauseDto> pauses) {
        if (pauses.isEmpty()) {
            return;
        }
        double t0 = pauses.get(0).uptimeMs();
        double t1 = Math.max(t0 + 1, pauses.get(pauses.size() - 1).uptimeMs());
        double yMax = Math.max(1, pauses.stream().mapToDouble(GcPauseDto::pauseMs).max().orElse(1));
        int width = 1000;
        int height = 220;
        html.append("<div class=\"panel\"><h3>Pause Duration</h3><svg viewBox=\"0 0 ").append(width).append(' ')
                .append(height + 20).append("\" class=\"chart\" role=\"img\" aria-label=\"GC pauses\">");
        for (int i = 0; i <= 4; i++) {
            int y = height - i * height / 4;
            html.append("<line x1=\"0\" x2=\"").append(width).append("\" y1=\"").append(y).append("\" y2=\"").append(y)
                    .append("\" class=\"grid\"/><text x=\"4\" y=\"").append(y - 4).append("\" class=\"axis\">")
                    .append(String.format(Locale.ROOT, "%.1f ms", yMax * i / 4)).append("</text>");
        }
        int step = Math.max(1, pauses.size() / 4000);
        for (int i = 0; i < pauses.size(); i += step) {
            GcPauseDto p = pauses.get(i);
            double x = (p.uptimeMs() - t0) * width / (t1 - t0);
            double y = height - p.pauseMs() * height / yMax;
            html.append(String.format(Locale.ROOT, "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"3\" class=\"pc-%s\"/>", x, y,
                    p.category().name().toLowerCase(Locale.ROOT)));
        }
        html.append("</svg><div class=\"legend\"><span class=\"k pc-young\"></span>young <span class=\"k pc-mixed\">")
                .append("</span>mixed <span class=\"k pc-full\"></span>full <span class=\"k pc-humongous\"></span>humongous")
                .append(" <span class=\"k pc-other\"></span>прочие</div></div>");
    }

    // ---------------------------------------------------------------- OQL

    static void oql(StringBuilder html, List<OqlHistoryEntryDto> queries) {
        if (queries.isEmpty()) {
            return;
        }
        html.append("<section><h2>OQL-запросы (").append(queries.size()).append(")</h2>");
        for (OqlHistoryEntryDto q : queries) {
            html.append("<div class=\"panel\"><p class=\"muted\">Снапшот ").append(esc(q.snapshotId())).append(" · ")
                    .append(q.rowCount()).append(" строк").append(q.truncated() ? " (обрезано)" : "").append(" · ")
                    .append(q.durationMs()).append(" ms</p><pre><code>").append(esc(q.query())).append("</code></pre>");
            if (!q.preview().isEmpty()) {
                html.append("<table><thead><tr>");
                q.columns().forEach(c -> html.append("<th>").append(esc(c)).append("</th>"));
                html.append("</tr></thead><tbody>");
                for (List<String> row : q.preview()) {
                    html.append("<tr>");
                    row.forEach(cell -> html.append("<td><code>").append(esc(cell)).append("</code></td>"));
                    html.append("</tr>");
                }
                html.append("</tbody></table>");
            }
            html.append("</div>");
        }
        html.append("</section>\n");
    }

    // ---------------------------------------------------------------- helpers

    private static void card(StringBuilder html, String label, String value, String tone) {
        html.append("<div class=\"card tone-").append(tone).append("\"><div class=\"label\">").append(esc(label))
                .append("</div><div class=\"value\">").append(esc(value)).append("</div></div>");
    }

    private static String state(JvmThreadState state) {
        return "<span class=\"badge st-" + state.name().toLowerCase(Locale.ROOT) + "\">" + state.name() + "</span>";
    }

    private static String abbreviation(JvmThreadState state) {
        return switch (state) {
            case RUNNABLE -> "R";
            case BLOCKED -> "B";
            case WAITING -> "W";
            case TIMED_WAITING -> "T";
            case NEW -> "N";
            case TERMINATED -> "×";
        };
    }

    private static String severity(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "КРИТИЧНО";
            case WARNING -> "ВНИМАНИЕ";
            case INFO -> "ИНФО";
        };
    }

    private static String ms(double value) {
        return String.format(Locale.ROOT, value >= 100 ? "%,.0f ms" : "%.2f ms", value);
    }

    private static String rate(Double value) {
        return value == null ? "—" : String.format(Locale.ROOT, "%,.1f MB/s", value);
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.2f", Math.min(100, Math.max(0, value)));
    }

    static final String CSS = """
            .matrix td.cell{text-align:center;font-family:"JetBrains Mono",Consolas,monospace;width:34px;padding:3px}
            .st-runnable{color:var(--emerald)}.st-blocked{color:var(--coral)}.st-waiting{color:var(--amber)}
            .st-timed_waiting{color:var(--violet)}.st-new,.st-terminated{color:var(--muted)}
            td.cell.st-runnable{background:rgba(0,200,83,.12)}td.cell.st-blocked{background:rgba(255,61,113,.18)}
            td.cell.st-waiting{background:rgba(255,171,0,.10)}td.cell.st-timed_waiting{background:rgba(179,136,255,.10)}
            td.hot{color:var(--coral);font-weight:600}
            .pc-young{fill:var(--cyan);background:var(--cyan)}.pc-mixed{fill:var(--violet);background:var(--violet)}
            .pc-full{fill:var(--coral);background:var(--coral)}.pc-humongous{fill:var(--amber);background:var(--amber)}
            .pc-remark,.pc-cleanup,.pc-concurrent_pause,.pc-other{fill:var(--emerald);background:var(--emerald)}
            """;
}
