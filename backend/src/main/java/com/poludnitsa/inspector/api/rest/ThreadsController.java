package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.ThreadLiveDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesRequestDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesSummaryDto;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.threads.ThreadContentionAndSeriesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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

/**
 * Threads &amp; contention: the live contention / deadlock picture of a target and series of thread dumps (taken
 * live or uploaded as a jstack text).
 */
@RestController
@Validated
@RequestMapping("/api/threads")
public class ThreadsController {

    private static final String ID = "^[A-Za-z0-9-]{1,64}$";

    private final ThreadContentionAndSeriesService threads;

    public ThreadsController(ThreadContentionAndSeriesService threads) {
        this.threads = threads;
    }

    @GetMapping("/{pid}/live")
    public ThreadLiveDto live(@PathVariable @Positive long pid) {
        return threads.live(pid);
    }

    @DeleteMapping("/{pid}/live")
    public ResponseEntity<Void> resetLive(@PathVariable @Positive long pid) {
        threads.resetLive(pid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/series")
    public List<ThreadSeriesSummaryDto> list() {
        return threads.list();
    }

    @PostMapping("/series")
    public ResponseEntity<ThreadSeriesSummaryDto> start(@Valid @RequestBody ThreadSeriesRequestDto request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(threads.startSeries(request));
    }

    @PostMapping(path = "/series/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ThreadSeriesSummaryDto upload(@RequestPart("file") MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return threads.upload(file.getOriginalFilename(), input);
        } catch (IOException e) {
            throw new InspectorException(400, "THREAD_DUMP_UNREADABLE", "Cannot read the upload: " + e.getMessage(), e);
        }
    }

    @GetMapping("/series/{id}")
    public ThreadSeriesSummaryDto get(@PathVariable @Pattern(regexp = ID) String id) {
        return threads.summary(id);
    }

    @GetMapping("/series/{id}/analysis")
    public ThreadSeriesAnalysisDto analysis(@PathVariable @Pattern(regexp = ID) String id) {
        return threads.analysis(id);
    }

    @DeleteMapping("/series/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = ID) String id) {
        threads.delete(id);
        return ResponseEntity.noContent().build();
    }
}
