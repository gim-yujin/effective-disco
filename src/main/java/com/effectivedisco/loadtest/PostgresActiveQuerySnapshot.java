package com.effectivedisco.loadtest;

public record PostgresActiveQuerySnapshot(
        long runtimeMs,
        String state,
        String waitEventType,
        String waitEvent,
        String queryText
) {
}
