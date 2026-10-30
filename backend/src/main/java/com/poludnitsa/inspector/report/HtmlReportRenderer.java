package com.poludnitsa.inspector.report;

import com.poludnitsa.inspector.api.dto.ClassDiffDto;
import com.poludnitsa.inspector.api.dto.ClassStatsDto;
import com.poludnitsa.inspector.api.dto.DominatorNodeDto;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.MemoryGroupStatsDto;
import com.poludnitsa.inspector.api.dto.MetricsPointDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.api.dto.SnapshotDiffDto;
import com.poludnitsa.inspector.api.dto.SnapshotSummaryDto;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.snapshot.SnapshotData;
import java.time.Instant;
import java.util.ArrayList;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;
import org.springframework.web.util.HtmlUtils;

/**
 * Renders a {@link SessionReportBundle} as one standalone HTML file in the GitKraken palette: no external scripts,
 * styles or fonts, so it opens offline and can be attached to an e-mail or ticket. Everything that comes from the
 * heap (class names, strings found in it, paths) is HTML-escaped.
 */
public final class HtmlReportRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int TABLE_ROWS = 50;

    public String render(SessionReportBundle bundle) {
        StringBuilder html = new StringBuilder(256 * 1024);
        html.append("<!doctype html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(bundle.title())).append("</title>\n<style>").append(CSS)
                .append("</style>\n</head>\n<body>\n");
        header(html, bundle);
        html.append("<main>\n");
        summaryCards(html, bundle);
        chart(html, bundle.metricsHistory());
        findings(html, bundle.findings());
        for (SessionReportBundle.SnapshotReport snapshot : bundle.snapshots()) {
            snapshot(html, snapshot);
        }
        for (SnapshotDiffDto diff : bundle.diffs()) {
            diff(html, diff);
        }
        html.append("</main>\n<footer>").append(esc(bundle.generator())).append(" · отчёт ")
                .append(esc(bundle.reportId())).append(" · ").append(esc(time(bundle.generatedAt())))
                .append("</footer>\n</body>\n</html>\n");
        return html.toString();
    }

    private static void header(StringBuilder html, SessionReportBundle bundle) {
        html.append("<header><div class=\"brand\">JVM Memory &amp; Leak Inspector</div><h1>")
                .append(esc(bundle.title())).append("</h1><div class=\"meta\">Сформирован ")
                .append(esc(time(bundle.generatedAt()))).append(" на хосте ").append(esc(bundle.hostName()));
        JvmProcessDto process = bundle.process();
        if (process != null) {
            html.append(" · <span class=\"badge meta-badge\">").append(esc(process.processType().name()))
                    .append("</span> ").append(esc(process.displayName())).append(" · PID ").append(process.pid());
            if (process.jvmVersion() != null) {
                html.append(" · JVM ").append(esc(process.jvmVersion()));
            }
        }
        html.append("</div></header>\n");
    }

    private static void summaryCards(StringBuilder html, SessionReportBundle bundle) {
        long critical = bundle.findings().stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long warning = bundle.findings().stream().filter(f -> f.severity() == Severity.WARNING).count();
        long info = bundle.findings().stream().filter(f -> f.severity() == Severity.INFO).count();
        long wasted = bundle.findings().stream().filter(f -> f.severity() != Severity.INFO)
                .mapToLong(InspectionFindingDto::wastedMemoryBytes).sum();
        html.append("<section class=\"cards\">");
        MetricsPointDto last = bundle.metricsHistory().isEmpty() ? null
                : bundle.metricsHistory().get(bundle.metricsHistory().size() - 1);
        if (last != null) {
            card(html, "Heap сейчас", Bytes.format(last.heapUsed()) + (last.heapMax() > 0
                    ? " / " + Bytes.format(last.heapMax()) : ""), "info");
            card(html, "GC overhead", String.format(Locale.ROOT, "%.1f %%", last.gcOverheadPercent()),
                    last.gcOverheadPercent() >= 10 ? "critical" : last.gcOverheadPercent() >= 3 ? "warning"
                            : "healthy");
        }
        card(html, "Критичных", String.valueOf(critical), critical > 0 ? "critical" : "healthy");
        card(html, "Предупреждений", String.valueOf(warning), warning > 0 ? "warning" : "healthy");
        card(html, "Информационных", String.valueOf(info), "info");
        card(html, "Под подозрением", Bytes.format(wasted), wasted > 0 ? "warning" : "healthy");
        card(html, "Снапшотов", String.valueOf(bundle.snapshots().size()), "meta");
        html.append("</section>\n");
    }

    private static void card(StringBuilder html, String label, String value, String tone) {
        html.append("<div class=\"card tone-").append(tone).append("\"><div class=\"label\">").append(esc(label))
                .append("</div><div class=\"value\">").append(esc(value)).append("</div></div>");
    }

    private static void chart(StringBuilder html, List<MetricsPointDto> history) {
        if (history.size() < 2) {
            return;
        }
        int step = Math.max(1, history.size() / 1000);
        List<MetricsPointDto> points = new ArrayList<>();
        for (int i = 0; i < history.size(); i += step) {
            points.add(history.get(i));
        }
        long t0 = points.get(0).timestampEpochMs();
        long t1 = Math.max(t0 + 1, points.get(points.size() - 1).timestampEpochMs());
        long yMax = 1;
        for (MetricsPointDto p : points) {
            yMax = Math.max(yMax, Math.max(p.heapCommitted(), p.heapUsed()));
        }
        int width = 1000;
        int height = 240;
        html.append("<section><h2>Память во времени</h2><div class=\"panel\"><svg viewBox=\"0 0 ").append(width)
                .append(' ').append(height + 24).append("\" class=\"chart\" role=\"img\" aria-label=\"Heap\">");
        for (int i = 0; i <= 4; i++) {
            int y = height - i * height / 4;
            html.append("<line x1=\"0\" x2=\"").append(width).append("\" y1=\"").append(y).append("\" y2=\"")
                    .append(y).append("\" class=\"grid\"/><text x=\"4\" y=\"").append(y - 4)
                    .append("\" class=\"axis\">").append(esc(Bytes.format(yMax * i / 4))).append("</text>");
        }
        polyline(html, points, t0, t1, yMax, width, height, MetricsPointDto::heapCommitted, "line-committed");
        polyline(html, points, t0, t1, yMax, width, height, MetricsPointDto::heapUsed, "line-used");
        polyline(html, points, t0, t1, yMax, width, height, MetricsPointDto::oldGenUsedAfterGc, "line-old");
        html.append("<text x=\"4\" y=\"").append(height + 18).append("\" class=\"axis\">")
                .append(esc(time(Instant.ofEpochMilli(t0)))).append("</text><text x=\"").append(width - 4)
                .append("\" y=\"").append(height + 18).append("\" class=\"axis end\">")
                .append(esc(time(Instant.ofEpochMilli(t1)))).append("</text></svg>")
                .append("<div class=\"legend\"><span class=\"k used\"></span>heap used <span class=\"k old\"></span>"
                        + "old gen после GC <span class=\"k committed\"></span>heap committed</div></div></section>\n");
    }

    private static void polyline(StringBuilder html, List<MetricsPointDto> points, long t0, long t1, long yMax,
                                 int width, int height, ToLongFunction<MetricsPointDto> value, String css) {
        StringBuilder coordinates = new StringBuilder();
        for (MetricsPointDto point : points) {
            long v = value.applyAsLong(point);
            if (v < 0) {
                continue;
            }
            double x = (point.timestampEpochMs() - t0) * (double) width / (t1 - t0);
            double y = height - v * (double) height / yMax;
            coordinates.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        if (!coordinates.isEmpty()) {
            html.append("<polyline class=\"").append(css).append("\" points=\"").append(coordinates).append("\"/>");
        }
    }

    private static void findings(StringBuilder html, List<InspectionFindingDto> findings) {
        html.append("<section><h2>Диагнозы (").append(findings.size()).append(")</h2>");
        if (findings.isEmpty()) {
            html.append("<p class=\"muted\">Инспекции не нашли проблем.</p>");
        }
        for (InspectionFindingDto finding : findings) {
            String tone = finding.severity().name().toLowerCase(Locale.ROOT);
            html.append("<article class=\"finding sev-").append(tone).append("\"><div class=\"finding-head\">")
                    .append("<span class=\"badge sev-").append(tone).append("\">").append(severity(finding.severity()))
                    .append("</span><span class=\"badge meta-badge\">").append(esc(finding.category().name()))
                    .append("</span><span class=\"rule\">").append(esc(finding.ruleId())).append("</span></div><h3>")
                    .append(esc(finding.title())).append("</h3><div class=\"facts\"><span>Память: <b>")
                    .append(esc(Bytes.format(finding.wastedMemoryBytes()))).append("</b></span><span>Затронуто: <b>")
                    .append(String.format(Locale.ROOT, "%,d", finding.affectedInstances())).append("</b></span>");
            if (finding.suspectClassName() != null) {
                html.append("<span>Виновник: <code>").append(esc(finding.suspectClassName()))
                        .append(finding.fieldName() != null ? "." + esc(finding.fieldName()) : "")
                        .append("</code></span>");
            }
            html.append("</div>");
            if (!finding.evidence().isEmpty()) {
                html.append("<ul class=\"evidence\">");
                finding.evidence().forEach(line -> html.append("<li>").append(esc(line)).append("</li>"));
                html.append("</ul>");
            }
            if (finding.gcRootPathText() != null) {
                html.append("<div class=\"path\"><span class=\"label\">Путь до GC Root</span><code>")
                        .append(esc(finding.gcRootPathText())).append("</code></div>");
            }
            html.append("<h4>Почему это происходит</h4><p class=\"why\">").append(esc(finding.whyItHappens()))
                    .append("</p><details open><summary>Как исправить</summary><pre><code>")
                    .append(esc(finding.howToFixCodeSnippet())).append("</code></pre></details></article>\n");
        }
        html.append("</section>\n");
    }

    private static void snapshot(StringBuilder html, SessionReportBundle.SnapshotReport report) {
        SnapshotSummaryDto summary = report.summary();
        SnapshotData data = report.data();
        if (summary == null || data == null) {
            return;
        }
        html.append("<section><h2>Снапшот ").append(esc(summary.snapshotId())).append("</h2><p class=\"muted\">")
                .append(esc(summary.kind().name())).append(" · ").append(esc(summary.sourceDisplayName()))
                .append(" (PID ").append(summary.sourcePid()).append(") · ").append(esc(time(summary.createdAt())))
                .append(summary.liveObjectsOnly() ? " · только живые объекты" : "").append(" · ")
                .append(String.format(Locale.ROOT, "%,d", data.totalInstances())).append(" объектов, ")
                .append(esc(Bytes.format(data.totalHeapBytes()))).append("</p>");

        html.append("<div class=\"panel\"><h3>Память по группам</h3>");
        for (MemoryGroupStatsDto group : data.groups()) {
            html.append("<div class=\"bar-row\"><span class=\"bar-label\">").append(esc(group.group().name()))
                    .append("</span><span class=\"bar\"><span class=\"fill g-").append(group.group().ordinal())
                    .append("\" style=\"width:").append(String.format(Locale.ROOT, "%.2f", group.percentOfHeap()))
                    .append("%\"></span></span><span class=\"bar-value\">")
                    .append(esc(Bytes.format(group.shallowBytes())))
                    .append(String.format(Locale.ROOT, " · %.1f %%", group.percentOfHeap())).append("</span></div>");
        }
        html.append("</div>");

        if (!data.dominators().isEmpty()) {
            html.append("<div class=\"panel\"><h3>Самые тяжёлые объекты (retained)</h3><table><thead><tr>")
                    .append("<th>Класс</th><th class=\"num\">Retained</th><th class=\"num\">% кучи</th>")
                    .append("<th class=\"num\">Shallow</th><th>Путь до GC Root</th></tr></thead><tbody>");
            for (DominatorNodeDto node : data.dominators().subList(0, Math.min(TABLE_ROWS, data.dominators().size()))) {
                html.append("<tr><td><code>").append(esc(node.className())).append("</code></td><td class=\"num\">")
                        .append(esc(Bytes.format(node.retainedSize()))).append("</td><td class=\"num\">")
                        .append(String.format(Locale.ROOT, "%.1f", node.percentOfHeap())).append("</td><td class=\"num\">")
                        .append(esc(Bytes.format(node.shallowSize()))).append("</td><td class=\"path-cell\"><code>")
                        .append(esc(node.gcRootPathText())).append("</code></td></tr>");
            }
            html.append("</tbody></table></div>");
        }

        html.append("<div class=\"panel\"><h3>Классы</h3><table><thead><tr><th>Класс</th><th>Группа</th>")
                .append("<th class=\"num\">Экземпляров</th><th class=\"num\">Shallow</th><th class=\"num\">Retained</th>")
                .append("</tr></thead><tbody>");
        for (ClassStatsDto stats : data.classes().subList(0, Math.min(TABLE_ROWS, data.classes().size()))) {
            html.append("<tr><td><code>").append(esc(stats.className())).append("</code>")
                    .append(stats.definitionCount() > 1 ? " <span class=\"badge sev-warning\">×" + stats.definitionCount()
                            + " ClassLoader</span>" : "")
                    .append("</td><td>").append(esc(stats.memoryGroup().name())).append("</td><td class=\"num\">")
                    .append(String.format(Locale.ROOT, "%,d", stats.instances())).append("</td><td class=\"num\">")
                    .append(esc(Bytes.format(stats.shallowBytes()))).append("</td><td class=\"num\">")
                    .append(stats.retainedBytes() == null ? "—" : esc(Bytes.format(stats.retainedBytes())))
                    .append("</td></tr>");
        }
        html.append("</tbody></table></div></section>\n");
    }

    private static void diff(StringBuilder html, SnapshotDiffDto diff) {
        html.append("<section><h2>Сравнение снапшотов</h2><p class=\"muted\">")
                .append(esc(diff.baseline().snapshotId())).append(" → ").append(esc(diff.current().snapshotId()))
                .append(" · Δ объектов ").append(String.format(Locale.ROOT, "%+,d", diff.deltaTotalInstances()))
                .append(" · Δ памяти ").append(signedBytes(diff.deltaTotalBytes())).append(" · новых классов ")
                .append(diff.newClasses()).append("</p><div class=\"panel\"><table><thead><tr><th>Класс</th>")
                .append("<th class=\"num\">Δ экземпляров</th><th class=\"num\">Δ shallow</th>")
                .append("<th class=\"num\">Δ retained</th><th class=\"num\">Выживание</th></tr></thead><tbody>");
        for (ClassDiffDto row : diff.classes().subList(0, Math.min(TABLE_ROWS, diff.classes().size()))) {
            String tone = row.survivalScore() >= 60 ? "critical" : row.survivalScore() >= 30 ? "warning" : "info";
            html.append("<tr><td><code>").append(esc(row.className())).append("</code>")
                    .append(row.newClass() ? " <span class=\"badge meta-badge\">новый</span>" : "")
                    .append("</td><td class=\"num\">").append(String.format(Locale.ROOT, "%+,d", row.deltaInstances()))
                    .append("</td><td class=\"num\">").append(signedBytes(row.deltaShallowBytes()))
                    .append("</td><td class=\"num\">")
                    .append(row.deltaRetainedBytes() == null ? "—" : signedBytes(row.deltaRetainedBytes()))
                    .append("</td><td class=\"num\"><span class=\"badge sev-").append(tone).append("\">")
                    .append(row.survivalScore()).append("</span></td></tr>");
        }
        html.append("</tbody></table></div></section>\n");
    }

    private static String signedBytes(long bytes) {
        return esc((bytes >= 0 ? "+" : "−") + Bytes.format(Math.abs(bytes)));
    }

    private static String severity(Severity severity) {
        return switch (severity) {
            case CRITICAL -> "КРИТИЧНО";
            case WARNING -> "ВНИМАНИЕ";
            case INFO -> "ИНФО";
        };
    }

    private static String time(Instant instant) {
        return instant == null ? "—" : TIME.format(instant);
    }

    static String esc(String text) {
        return text == null ? "" : HtmlUtils.htmlEscape(text, "UTF-8");
    }

    private static final String CSS = """
            :root{--bg:#1b1d23;--panel:#232730;--card:#2c313c;--border:#363c4a;--text:#e6e8ee;--muted:#9aa3b2;
            --cyan:#00e5ff;--emerald:#00c853;--amber:#ffab00;--coral:#ff3d71;--violet:#b388ff}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 Inter,"Segoe UI",system-ui,sans-serif}
            header{background:var(--panel);border-bottom:1px solid var(--border);padding:24px 32px}
            .brand{color:var(--cyan);font-weight:600;letter-spacing:.04em;font-size:12px;text-transform:uppercase}
            h1{margin:6px 0;font-size:22px}h2{font-size:18px;margin:32px 0 12px}h3{font-size:15px;margin:4px 0 10px}
            h4{margin:16px 0 4px;font-size:13px;color:var(--muted);text-transform:uppercase;letter-spacing:.05em}
            main{padding:8px 32px 40px;max-width:1400px;margin:0 auto}
            .meta,.muted{color:var(--muted)}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-top:20px}
            .card{background:var(--card);border:1px solid var(--border);border-radius:8px;padding:14px 16px;
            border-top:3px solid var(--border)}
            .card .label{color:var(--muted);font-size:12px}.card .value{font-size:22px;font-weight:600;margin-top:2px}
            .tone-critical{border-top-color:var(--coral)}.tone-warning{border-top-color:var(--amber)}
            .tone-healthy{border-top-color:var(--emerald)}.tone-info{border-top-color:var(--cyan)}
            .tone-meta{border-top-color:var(--violet)}
            .panel{background:var(--panel);border:1px solid var(--border);border-radius:8px;padding:16px;margin:12px 0;
            overflow-x:auto}
            .chart{width:100%;height:auto}.grid{stroke:var(--border);stroke-width:1}
            .axis{fill:var(--muted);font-size:11px}.axis.end{text-anchor:end}
            polyline{fill:none;stroke-width:2}.line-used{stroke:var(--cyan)}.line-old{stroke:var(--coral)}
            .line-committed{stroke:var(--border);stroke-dasharray:4 4}
            .legend{color:var(--muted);font-size:12px}.k{display:inline-block;width:14px;height:3px;margin:0 6px 3px 12px}
            .k.used{background:var(--cyan)}.k.old{background:var(--coral)}.k.committed{background:var(--border)}
            .finding{background:var(--panel);border:1px solid var(--border);border-left:4px solid var(--cyan);
            border-radius:8px;padding:16px 20px;margin:14px 0}
            .finding.sev-critical{border-left-color:var(--coral)}.finding.sev-warning{border-left-color:var(--amber)}
            .finding-head{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
            .rule{color:var(--muted);font-family:"JetBrains Mono",Consolas,monospace;font-size:12px}
            .badge{display:inline-block;border-radius:4px;padding:1px 8px;font-size:11px;font-weight:600;
            border:1px solid currentColor}
            .badge.sev-critical{color:var(--coral)}.badge.sev-warning{color:var(--amber)}.badge.sev-info{color:var(--cyan)}
            .meta-badge{color:var(--violet)}
            .facts{display:flex;gap:20px;flex-wrap:wrap;color:var(--muted)}.facts b{color:var(--text)}
            .evidence{margin:10px 0;padding-left:18px;color:var(--text)}.evidence li{margin:2px 0}
            .path{background:var(--card);border:1px solid var(--border);border-radius:6px;padding:8px 12px;margin:10px 0}
            .path .label{display:block;color:var(--muted);font-size:11px;text-transform:uppercase}
            .why{white-space:pre-wrap;margin:4px 0}
            code,pre{font-family:"JetBrains Mono","Cascadia Code",Consolas,monospace;font-size:12.5px}
            pre{background:var(--bg);border:1px solid var(--border);border-radius:6px;padding:12px 14px;overflow-x:auto;
            color:#cfe8ff}
            details summary{cursor:pointer;color:var(--emerald);font-weight:600;margin-top:12px}
            table{width:100%;border-collapse:collapse;font-size:13px}
            th,td{border-bottom:1px solid var(--border);padding:6px 8px;text-align:left;vertical-align:top}
            th{color:var(--muted);font-weight:500}.num{text-align:right;white-space:nowrap}
            .path-cell code{white-space:normal;word-break:break-word;color:var(--muted)}
            .bar-row{display:grid;grid-template-columns:240px 1fr 170px;gap:10px;align-items:center;margin:6px 0}
            .bar{background:var(--card);border-radius:4px;height:10px;overflow:hidden}.fill{display:block;height:100%}
            .g-0{background:var(--emerald)}.g-1{background:var(--violet)}.g-2{background:var(--amber)}
            .g-3{background:var(--cyan)}.g-4{background:var(--muted)}.bar-value{color:var(--muted);text-align:right}
            footer{color:var(--muted);text-align:center;padding:24px;border-top:1px solid var(--border);font-size:12px}
            @media print{body{background:#fff;color:#111}.panel,.finding,.card,header{background:#fff;color:#111}}
            """;
}
