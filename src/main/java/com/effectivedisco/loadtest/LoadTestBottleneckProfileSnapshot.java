package com.effectivedisco.loadtest;

/**
 * 문제 해결:
 * pool 포화가 보여도 어느 경로가 실제로 SQL 시간을 많이 쓰는지 없으면
 * Hikari 크기만 키우는 잘못된 대응으로 흐르기 쉽다.
 * loadtest 전용 병목 프로파일을 별도로 내보내 댓글 작성/알림 저장/목록 조회의
 * 벽시계 시간, SQL 실행 시간, SQL 문 수, 트랜잭션 길이를 같은 기준으로 비교한다.
 */
public record LoadTestBottleneckProfileSnapshot(
        String name,
        long sampleCount,
        double averageWallTimeMs,
        double maxWallTimeMs,
        double averageSqlExecutionTimeMs,
        double maxSqlExecutionTimeMs,
        double averageSqlStatementCount,
        long maxSqlStatementCount,
        double averageTransactionTimeMs,
        double maxTransactionTimeMs,
        long transactionObservedCount
) {
}
