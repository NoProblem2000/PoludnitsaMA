package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.ButterflyDto;
import com.poludnitsa.inspector.api.dto.DiffFlameGraphDto;
import com.poludnitsa.inspector.api.dto.FlameGraphDto;
import com.poludnitsa.inspector.api.dto.HotspotDto;
import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.ProfileRequestDto;
import com.poludnitsa.inspector.api.dto.ProfileSummaryDto;
import com.poludnitsa.inspector.api.dto.ProfilerCapabilitiesDto;
import com.poludnitsa.inspector.api.dto.SubsystemBreakdownDto;
import com.poludnitsa.inspector.api.dto.TimelineDto;
import com.poludnitsa.inspector.profiler.CpuAndTimelineProfilerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CPU / wall-clock / allocation profiles and their timeline. Every call tree query takes an optional window
 * ({@code from} / {@code to}, milliseconds from the profile start) and thread, for cross-filtering by a brushed
 * interval of the timeline.
 */
@RestController
@Validated
@RequestMapping("/api/profiles")
public class ProfilerController {

    private static final String ID = "^[A-Za-z0-9-]{1,64}$";

    private final CpuAndTimelineProfilerService profiler;

    public ProfilerController(CpuAndTimelineProfilerService profiler) {
        this.profiler = profiler;
    }

    @GetMapping("/capabilities")
    public ProfilerCapabilitiesDto capabilities() {
        return profiler.capabilities();
    }

    @GetMapping
    public List<ProfileSummaryDto> list() {
        return profiler.list();
    }

    @PostMapping
    public ResponseEntity<ProfileSummaryDto> start(@Valid @RequestBody ProfileRequestDto request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(profiler.start(request));
    }

    @GetMapping("/{id}")
    public ProfileSummaryDto get(@PathVariable @Pattern(regexp = ID) String id) {
        return profiler.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = ID) String id) {
        profiler.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/timeline")
    public TimelineDto timeline(@PathVariable @Pattern(regexp = ID) String id,
                                @RequestParam(required = false) @Min(10) @Max(2000) Integer buckets) {
        return profiler.timeline(id, buckets);
    }

    @GetMapping("/{id}/flame")
    public FlameGraphDto flame(@PathVariable @Pattern(regexp = ID) String id,
                               @RequestParam(defaultValue = "CPU") ProfileMetric metric,
                               @RequestParam(required = false) Double from,
                               @RequestParam(required = false) Double to,
                               @RequestParam(required = false) @Min(0) Integer thread) {
        return profiler.flame(id, metric, from, to, thread);
    }

    @GetMapping("/diff/flame")
    public DiffFlameGraphDto diff(@RequestParam @Pattern(regexp = ID) String baseline,
                                  @RequestParam @Pattern(regexp = ID) String target,
                                  @RequestParam(defaultValue = "CPU") ProfileMetric metric) {
        return profiler.diff(baseline, target, metric);
    }

    @GetMapping("/{id}/hotspots")
    public List<HotspotDto> hotspots(@PathVariable @Pattern(regexp = ID) String id,
                                     @RequestParam(defaultValue = "CPU") ProfileMetric metric,
                                     @RequestParam(required = false) Double from,
                                     @RequestParam(required = false) Double to,
                                     @RequestParam(required = false) @Min(0) Integer thread,
                                     @RequestParam(required = false) @Min(1) @Max(1000) Integer limit) {
        return profiler.hotspots(id, metric, from, to, thread, limit);
    }

    @GetMapping("/{id}/butterfly")
    public ButterflyDto butterfly(@PathVariable @Pattern(regexp = ID) String id,
                                  @RequestParam @NotBlank @Size(max = 1024) String className,
                                  @RequestParam @NotBlank @Size(max = 512) String method,
                                  @RequestParam(defaultValue = "CPU") ProfileMetric metric,
                                  @RequestParam(required = false) Double from,
                                  @RequestParam(required = false) Double to,
                                  @RequestParam(required = false) @Min(0) Integer thread) {
        return profiler.butterfly(id, className, method, metric, from, to, thread);
    }

    @GetMapping("/{id}/subsystems")
    public SubsystemBreakdownDto subsystems(@PathVariable @Pattern(regexp = ID) String id,
                                            @RequestParam(defaultValue = "WALL") ProfileMetric metric,
                                            @RequestParam(required = false) Double from,
                                            @RequestParam(required = false) Double to,
                                            @RequestParam(required = false) @Min(0) Integer thread) {
        return profiler.subsystems(id, metric, from, to, thread);
    }
}
