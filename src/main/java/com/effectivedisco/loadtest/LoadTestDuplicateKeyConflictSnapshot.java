package com.effectivedisco.loadtest;

/**
 * 문제 해결:
 * duplicate-key 총량만으로는 어떤 HTTP 경로와 어떤 unique constraint가 충돌했는지 알 수 없다.
 * 경로/constraint별 스냅샷을 같이 남겨야 broad mixed 실패 원인을 정확한 write path로 좁힐 수 있다.
 */
public record LoadTestDuplicateKeyConflictSnapshot(
        String requestSignature,
        String constraintName,
        long count,
        String sampleMessage
) {
}
