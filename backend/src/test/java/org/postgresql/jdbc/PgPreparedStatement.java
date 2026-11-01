package org.postgresql.jdbc;

/** Test double of the PostgreSQL driver: a query waits for the server (a sleep stands in for the socket read). */
public final class PgPreparedStatement {

    public long executeQuery(long serverMillis) {
        long rows = 0;
        try {
            Thread.sleep(serverMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < 20_000; i++) {
            rows += i % 7;
        }
        return rows;
    }
}
