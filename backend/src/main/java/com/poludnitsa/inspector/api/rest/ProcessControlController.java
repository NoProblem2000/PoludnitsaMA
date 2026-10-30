package com.poludnitsa.inspector.api.rest;

import com.poludnitsa.inspector.api.dto.ActiveProcessDto;
import com.poludnitsa.inspector.api.dto.ClassHistogramDto;
import com.poludnitsa.inspector.api.dto.ConnectionState;
import com.poludnitsa.inspector.api.dto.GcTriggerRequestDto;
import com.poludnitsa.inspector.api.dto.GcTriggerResultDto;
import com.poludnitsa.inspector.api.dto.JfrAllocationStatsDto;
import com.poludnitsa.inspector.api.dto.JvmProcessDto;
import com.poludnitsa.inspector.api.dto.LiveMetricsPayloadDto;
import com.poludnitsa.inspector.api.dto.RemoteJmxConnectRequestDto;
import com.poludnitsa.inspector.api.dto.SelectProcessRequestDto;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.discovery.JvmDiscoveryService;
import com.poludnitsa.inspector.live.LiveMemoryCollectorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Process list, connections, active PID selection and on-demand operations on a target. */
@RestController
@RequestMapping("/api/processes")
@Validated
public class ProcessControlController {

    private final JvmDiscoveryService discovery;
    private final LiveMemoryCollectorService collector;

    public ProcessControlController(JvmDiscoveryService discovery, LiveMemoryCollectorService collector) {
        this.discovery = discovery;
        this.collector = collector;
    }

    @GetMapping
    public List<JvmProcessDto> list() {
        return discovery.listProcesses();
    }

    @GetMapping("/{pid}")
    public JvmProcessDto describe(@PathVariable long pid) {
        return discovery.describe(pid);
    }

    /** Attaches and starts the target's local JMX agent now, ignoring reconnect backoff. */
    @PostMapping("/{pid}/connect")
    public JvmProcessDto connect(@PathVariable long pid) {
        return discovery.connect(pid);
    }

    @PostMapping("/remote")
    public JvmProcessDto connectRemote(@Valid @RequestBody RemoteJmxConnectRequestDto request) {
        return discovery.connectRemote(request);
    }

    @DeleteMapping("/remote/{pid}")
    public ResponseEntity<Void> disconnectRemote(@PathVariable long pid) {
        collector.stopMonitoring(pid, ConnectionState.DISCONNECTED, "Remote endpoint removed");
        discovery.disconnectRemote(pid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    public ActiveProcessDto active() {
        return collector.activeProcess();
    }

    /** The active PID is sampled even without WebSocket subscribers. */
    @PutMapping("/active")
    public ActiveProcessDto select(@Valid @RequestBody SelectProcessRequestDto request) {
        return collector.select(request.pid());
    }

    @DeleteMapping("/active")
    public ActiveProcessDto clearSelection() {
        return collector.clearSelection();
    }

    /** Latest live sample; 204 while the PID is not monitored or before the first sample. */
    @GetMapping("/{pid}/metrics")
    public ResponseEntity<LiveMetricsPayloadDto> latestMetrics(@PathVariable long pid) {
        return collector.latest(pid).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Quick {@code GC.class_histogram -all}: no forced Full GC, but a safepoint heap walk on the target. */
    @PostMapping("/{pid}/histogram")
    public ClassHistogramDto histogram(@PathVariable long pid,
                                       @RequestParam(defaultValue = "500") @Min(1) @Max(10_000) int limit) {
        return collector.histogram(pid, limit);
    }

    /**
     * {@code MemoryMXBean.gc()}: a stop-the-world Full GC on most configurations. Requires {@code {"confirm":true}};
     * the JSON body also makes browsers send a CORS preflight, so other web pages cannot trigger it.
     */
    @PostMapping("/{pid}/gc")
    public GcTriggerResultDto gc(@PathVariable long pid, @RequestBody GcTriggerRequestDto request) {
        if (!request.confirm()) {
            throw new OperationRejectedException(400, "GC_CONFIRMATION_REQUIRED",
                    "MemoryMXBean.gc() runs a stop-the-world Full GC on the target (unless"
                            + " -XX:+ExplicitGCInvokesConcurrent or -XX:+DisableExplicitGC is set). Long pauses can"
                            + " drop Hazelcast client connections. Repeat with {\"confirm\": true}.");
        }
        return collector.triggerGc(pid);
    }

    @GetMapping("/{pid}/jfr")
    public ResponseEntity<JfrAllocationStatsDto> jfrStatus(@PathVariable long pid) {
        return collector.jfrStatus(pid).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{pid}/jfr")
    public JfrAllocationStatsDto startJfr(@PathVariable long pid) {
        return collector.startJfr(pid);
    }

    @DeleteMapping("/{pid}/jfr")
    public JfrAllocationStatsDto stopJfr(@PathVariable long pid) {
        return collector.stopJfr(pid);
    }
}
