package com.effectivedisco.loadtest;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

@Component
public class LoadTestSqlProfiler {

    private final ThreadLocal<Deque<SqlScope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);

    public ScopeToken beginScope() {
        SqlScope scope = new SqlScope();
        scopes.get().push(scope);
        return new ScopeToken(scope);
    }

    public SqlProfileSnapshot endScope(ScopeToken token) {
        Deque<SqlScope> stack = scopes.get();
        if (!stack.isEmpty() && stack.peek() == token.scope()) {
            stack.pop();
        } else {
            stack.remove(token.scope());
        }

        if (stack.isEmpty()) {
            scopes.remove();
        }

        return token.scope().snapshot();
    }

    /**
     * 문제 해결:
     * JDBC statement 단위 실행 시간을 스레드 로컬 scope에 누적해야
     * 서비스 메서드의 전체 시간 중 실제 SQL이 차지한 비율과 statement 수를 분리해서 볼 수 있다.
     * 중첩 scope가 있더라도 바깥 scope가 안쪽 SQL 비용을 함께 포함하도록 전부에 더한다.
     */
    public void recordStatement(long executionNanos) {
        Deque<SqlScope> stack = scopes.get();
        if (stack.isEmpty()) {
            return;
        }

        for (SqlScope scope : stack) {
            scope.recordStatement(executionNanos);
        }
    }

    public record ScopeToken(SqlScope scope) {}

    public record SqlProfileSnapshot(long sqlStatementCount, long sqlExecutionNanos) {}

    static final class SqlScope {
        private long sqlStatementCount;
        private long sqlExecutionNanos;

        void recordStatement(long executionNanos) {
            sqlStatementCount += 1;
            sqlExecutionNanos += Math.max(executionNanos, 0L);
        }

        SqlProfileSnapshot snapshot() {
            return new SqlProfileSnapshot(sqlStatementCount, sqlExecutionNanos);
        }
    }
}
