package io.undertow.server;

/** Test double: the frame Undertow puts at the bottom of every request handled on a worker thread. */
public final class Connectors {

    private Connectors() {
    }

    public static void executeRootHandler(Runnable handler) {
        handler.run();
    }
}
