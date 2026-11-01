package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.ProfileSource;
import com.poludnitsa.inspector.api.dto.ProfileStatus;
import com.poludnitsa.inspector.api.dto.ProfileSummaryDto;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;

/** One profile: mutable while recording and parsing, then holding its immutable {@link ProfileData}. */
final class ProfileRecord {

    final String id;
    final long pid;
    final String displayName;
    final ProfileSource source;
    final ProfileMetric mode;
    final Instant startedAt;
    final long durationMs;
    final boolean tracing;
    final double samplingIntervalMs;

    private ProfileStatus status = ProfileStatus.RECORDING;
    private String progressMessage = "Recording";
    private String errorMessage;
    private ProfileData data;
    private Future<?> task;

    ProfileRecord(String id, long pid, String displayName, ProfileSource source, ProfileMetric mode, Instant startedAt,
                  long durationMs, boolean tracing, double samplingIntervalMs) {
        this.id = id;
        this.pid = pid;
        this.displayName = displayName;
        this.source = source;
        this.mode = mode;
        this.startedAt = startedAt;
        this.durationMs = durationMs;
        this.tracing = tracing;
        this.samplingIntervalMs = samplingIntervalMs;
    }

    synchronized void phase(ProfileStatus newStatus, String message) {
        status = newStatus;
        progressMessage = message;
    }

    synchronized void ready(ProfileData parsed) {
        data = parsed;
        status = ProfileStatus.READY;
        progressMessage = "Ready";
    }

    synchronized void fail(String message) {
        status = ProfileStatus.FAILED;
        progressMessage = "Failed";
        errorMessage = message;
    }

    synchronized ProfileStatus status() {
        return status;
    }

    synchronized boolean finished() {
        return status == ProfileStatus.READY || status == ProfileStatus.FAILED;
    }

    synchronized ProfileData data() {
        return data;
    }

    synchronized void task(Future<?> future) {
        task = future;
    }

    synchronized Future<?> task() {
        return task;
    }

    synchronized ProfileSummaryDto toSummary() {
        ProfileData current = data;
        List<ProfileMetric> metrics = current == null ? List.of()
                : current.metrics().stream().sorted().toList();
        long events = current == null ? 0 : current.events().size();
        return new ProfileSummaryDto(id, pid, displayName, source, mode, status, startedAt, durationMs,
                current == null ? 0 : Math.round(current.durationMs()), progressMessage, errorMessage,
                current == null ? 0 : current.sampleCount(), events, current == null ? 0 : current.threads().size(),
                samplingIntervalMs, tracing, metrics, current != null && current.truncated());
    }
}
