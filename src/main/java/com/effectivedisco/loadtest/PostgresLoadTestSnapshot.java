package com.effectivedisco.loadtest;

import java.util.List;

/**
 * 문제 해결:
 * 애플리케이션 내부 pool 수치만으로는 "왜 커넥션이 오래 점유되는지"를 알기 어렵다.
 * PostgreSQL wait event 와 현재 장기 실행 query 를 같은 스냅샷으로 남겨야
 * pool 포화와 DB 내부 대기 원인을 같은 실행 기준으로 해석할 수 있다.
 */
public record PostgresLoadTestSnapshot(
        boolean available,
        String availabilityReason,
        String databaseProduct,
        String applicationName,
        int activeSessions,
        int waitingSessions,
        int lockWaitingSessions,
        int longRunningTransactions,
        int longRunningQueries,
        long longestTransactionMs,
        long longestQueryMs,
        List<PostgresWaitEventSnapshot> topWaitEvents,
        List<PostgresActiveQuerySnapshot> slowActiveQueries
) {
    public static PostgresLoadTestSnapshot unavailable(String availabilityReason,
                                                       String databaseProduct,
                                                       String applicationName) {
        return new PostgresLoadTestSnapshot(
                false,
                availabilityReason,
                databaseProduct,
                applicationName,
                0,
                0,
                0,
                0,
                0,
                0L,
                0L,
                List.of(),
                List.of()
        );
    }
}
