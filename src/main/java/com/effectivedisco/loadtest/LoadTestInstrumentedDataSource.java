package com.effectivedisco.loadtest;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import java.util.logging.Logger;

class LoadTestInstrumentedDataSource implements DataSource {

    private final DataSource delegate;
    private final LoadTestSqlProfiler sqlProfiler;

    LoadTestInstrumentedDataSource(DataSource delegate, LoadTestSqlProfiler sqlProfiler) {
        this.delegate = Objects.requireNonNull(delegate);
        this.sqlProfiler = Objects.requireNonNull(sqlProfiler);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    private Connection wrapConnection(Connection connection) {
        InvocationHandler handler = new ConnectionInvocationHandler(connection, sqlProfiler);
        return (Connection) Proxy.newProxyInstance(
                connection.getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                handler
        );
    }

    private static final class ConnectionInvocationHandler extends DelegatingInvocationHandler {

        private final LoadTestSqlProfiler sqlProfiler;

        private ConnectionInvocationHandler(Connection delegate, LoadTestSqlProfiler sqlProfiler) {
            super(delegate);
            this.sqlProfiler = sqlProfiler;
        }

        @Override
        protected Object invokeDelegate(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = super.invokeDelegate(proxy, method, args);
            if (result instanceof PreparedStatement preparedStatement) {
                return wrapStatement(preparedStatement, PreparedStatement.class, sqlProfiler);
            }
            if (result instanceof CallableStatement callableStatement) {
                return wrapStatement(callableStatement, CallableStatement.class, sqlProfiler);
            }
            if (result instanceof Statement statement) {
                return wrapStatement(statement, Statement.class, sqlProfiler);
            }
            return result;
        }
    }

    private static Statement wrapStatement(Statement statement,
                                           Class<?> primaryInterface,
                                           LoadTestSqlProfiler sqlProfiler) {
        InvocationHandler handler = new StatementInvocationHandler(statement, sqlProfiler);
        return (Statement) Proxy.newProxyInstance(
                statement.getClass().getClassLoader(),
                new Class<?>[]{primaryInterface},
                handler
        );
    }

    private static final class StatementInvocationHandler extends DelegatingInvocationHandler {

        private final LoadTestSqlProfiler sqlProfiler;

        private StatementInvocationHandler(Statement delegate, LoadTestSqlProfiler sqlProfiler) {
            super(delegate);
            this.sqlProfiler = sqlProfiler;
        }

        @Override
        protected Object invokeDelegate(Object proxy, Method method, Object[] args) throws Throwable {
            if (!isSqlExecutionMethod(method)) {
                return super.invokeDelegate(proxy, method, args);
            }

            long startedAt = System.nanoTime();
            try {
                return super.invokeDelegate(proxy, method, args);
            } finally {
                sqlProfiler.recordStatement(System.nanoTime() - startedAt);
            }
        }

        private boolean isSqlExecutionMethod(Method method) {
            String name = method.getName();
            return name.equals("execute")
                    || name.equals("executeQuery")
                    || name.equals("executeUpdate")
                    || name.equals("executeBatch")
                    || name.equals("executeLargeUpdate")
                    || name.equals("executeLargeBatch");
        }
    }

    private abstract static class DelegatingInvocationHandler implements InvocationHandler {

        private final Object delegate;

        protected DelegatingInvocationHandler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return delegate.toString();
            }
            if ("unwrap".equals(name) && args != null && args.length == 1 && args[0] instanceof Class<?> iface) {
                if (iface.isInstance(proxy)) {
                    return proxy;
                }
                if (delegate instanceof java.sql.Wrapper wrapper) {
                    return wrapper.unwrap(iface);
                }
            }
            if ("isWrapperFor".equals(name) && args != null && args.length == 1 && args[0] instanceof Class<?> iface) {
                return iface.isInstance(proxy)
                        || (delegate instanceof java.sql.Wrapper wrapper && wrapper.isWrapperFor(iface));
            }

            return invokeDelegate(proxy, method, args);
        }

        protected Object invokeDelegate(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException exception) {
                throw exception.getTargetException();
            }
        }
    }
}
