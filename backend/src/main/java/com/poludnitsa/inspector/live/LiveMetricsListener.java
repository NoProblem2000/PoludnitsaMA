package com.poludnitsa.inspector.live;

import com.poludnitsa.inspector.api.dto.LiveMetricsPayloadDto;
import com.poludnitsa.inspector.api.dto.TargetStatusDto;

/** Receives samples of one monitored target. Called on sampler threads; implementations must not block. */
public interface LiveMetricsListener {

    void onMetrics(LiveMetricsPayloadDto payload);

    void onStatus(TargetStatusDto status);
}
