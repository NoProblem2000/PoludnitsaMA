package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.GcAnalysisDto;
import com.poludnitsa.inspector.api.dto.GcAnalysisSummaryDto;
import com.poludnitsa.inspector.api.dto.GcLiveRequestDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.gc.GcLogParserAndAnalyzerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** GC &amp; safepoints: uploaded gc.log / JFR files and live GC recordings of a target. */
@RestController
@Validated
@RequestMapping("/api/gc")
public class GcController {

    private static final String ID = "^[A-Za-z0-9-]{1,64}$";

    private final GcLogParserAndAnalyzerService gc;

    public GcController(GcLogParserAndAnalyzerService gc) {
        this.gc = gc;
    }

    @GetMapping
    public List<GcAnalysisSummaryDto> list() {
        return gc.list();
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public GcAnalysisSummaryDto upload(@RequestPart("file") MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return gc.upload(file.getOriginalFilename(), input);
        } catch (IOException e) {
            throw new InspectorException(400, "GC_LOG_UNREADABLE", "Cannot read the upload: " + e.getMessage(), e);
        }
    }

    @PostMapping("/live")
    public ResponseEntity<GcAnalysisSummaryDto> startLive(@Valid @RequestBody GcLiveRequestDto request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(gc.startLive(request));
    }

    @PostMapping("/{id}/stop")
    public GcAnalysisSummaryDto stop(@PathVariable @Pattern(regexp = ID) String id) {
        return gc.stop(id);
    }

    @GetMapping("/{id}")
    public GcAnalysisSummaryDto get(@PathVariable @Pattern(regexp = ID) String id) {
        return gc.summary(id);
    }

    @GetMapping("/{id}/analysis")
    public GcAnalysisDto analysis(@PathVariable @Pattern(regexp = ID) String id) {
        return gc.analysis(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = ID) String id) {
        gc.delete(id);
        return ResponseEntity.noContent().build();
    }
}
