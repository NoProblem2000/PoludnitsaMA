package com.poludnitsa.inspector.live;

/** Handle of a {@link LiveMetricsListener} registration. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

    @Override
    void close();
}
