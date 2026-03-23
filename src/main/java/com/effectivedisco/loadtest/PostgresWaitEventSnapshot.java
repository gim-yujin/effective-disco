package com.effectivedisco.loadtest;

public record PostgresWaitEventSnapshot(
        String waitEventType,
        String waitEvent,
        int sessionCount
) {
}
