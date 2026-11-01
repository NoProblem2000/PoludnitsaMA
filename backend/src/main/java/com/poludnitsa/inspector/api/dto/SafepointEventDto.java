package com.poludnitsa.inspector.api.dto;

/**
 * One safepoint: reaching it (TTSP) and the operation run in it.
 *
 * @param operation VM operation, e.g. G1CollectForAllocation, RevokeBias, Cleanup
 * @param ttspMs time to safepoint: from the request until every thread stopped
 * @param slowTtsp TTSP above inspector.gc-log.ttsp-warning
 */
public record SafepointEventDto(
        double uptimeMs,
        String operation,
        double ttspMs,
        double operationMs,
        double totalMs,
        boolean slowTtsp
) {
}
