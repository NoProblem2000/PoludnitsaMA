package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.ImportedReportDto;
import com.poludnitsa.inspector.api.dto.ReportExportRequestDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.report.ReportExportImportService;
import com.poludnitsa.inspector.report.SessionReportBundle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Session reports as files: gzipped JSON (re-importable) and standalone HTML. */
@RestController
@RequestMapping("/api/reports")
@Validated
public class ReportController {

    public enum Format { JSON, HTML }

    private final ReportExportImportService reports;

    public ReportController(ReportExportImportService reports) {
        this.reports = reports;
    }

    /** Builds the report (validation errors surface before the download starts) and streams it as a file. */
    @PostMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(defaultValue = "JSON") Format format,
                                                        @Valid @RequestBody ReportExportRequestDto request) {
        SessionReportBundle bundle = reports.buildBundle(request);
        boolean json = format == Format.JSON;
        String fileName = ReportExportImportService.fileName(bundle, json ? ".json.gz" : ".html");
        return ResponseEntity.ok()
                .contentType(json ? MediaType.parseMediaType("application/gzip")
                        : new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(out -> {
                    if (json) {
                        reports.writeJson(bundle, out);
                    } else {
                        reports.writeHtml(bundle, out);
                    }
                });
    }

    /** Uploads a {@code .json.gz} / {@code .json} report; its snapshots become available in this session. */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportedReportDto importReport(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new OperationRejectedException(400, "REPORT_EMPTY", "The uploaded file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            return reports.importReport(in);
        }
    }

    /** Renders an uploaded {@code .json.gz} / {@code .json} report as standalone HTML (offline reports in the UI). */
    @PostMapping(path = "/html", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StreamingResponseBody> renderHtml(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new OperationRejectedException(400, "REPORT_EMPTY", "The uploaded file is empty");
        }
        SessionReportBundle bundle;
        try (InputStream in = file.getInputStream()) {
            bundle = reports.readBundle(in);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\""
                        + ReportExportImportService.fileName(bundle, ".html") + "\"")
                .body(out -> reports.writeHtml(bundle, out));
    }

    @GetMapping("/imported")
    public List<ImportedReportDto> imported() {
        return reports.importedReports();
    }

    /** The full imported bundle, for rendering a report from another machine in the UI. */
    @GetMapping("/imported/{id}")
    public SessionReportBundle importedReport(@PathVariable @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String id) {
        return reports.importedReport(id);
    }
}
