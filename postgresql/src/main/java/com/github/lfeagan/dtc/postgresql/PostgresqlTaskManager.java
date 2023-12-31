package com.github.lfeagan.dtc.postgresql;

import com.github.lfeagan.dtc.*;
import org.postgresql.util.PGInterval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threeten.extra.PeriodDuration;

import javax.sql.DataSource;
import java.sql.*;
import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.github.lfeagan.dtc.postgresql.JdbcUtils.closeWithoutException;

public class PostgresqlTaskManager implements TaskManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresqlTaskManager.class);

    protected final DataSource dataSource;
    protected final SqlBuilder sqlBuilder;

    public PostgresqlTaskManager(final DataSource dataSource) {
        this.dataSource = dataSource;
        this.sqlBuilder = new SqlBuilder("tasks", 32);
    }

    public void initialize() throws TaskManagerException {
        Connection conn = null;
        Statement stmt = null;
        try {
            conn = getConnection();
            stmt = conn.createStatement();
            stmt.execute(sqlBuilder.createTaskTable());
        } catch (SQLException e) {
            throw new TaskManagerException("Unable to initialize", e);
        } finally {
            closeWithoutException(stmt);
            closeWithoutException(conn);
        }
    }

    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public Task createTask(String name, Instant bucketTime, PeriodDuration bucketInterval, String createdBy) throws DuplicateTaskException, TaskManagerException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            pstmt = conn.prepareStatement(sqlBuilder.insertMinimalTask());
            pstmt.setString(1, name);
            pstmt.setTimestamp(2, Timestamp.from(bucketTime));
            org.postgresql.util.PGInterval interval = new PGInterval(bucketInterval.toString());
            pstmt.setObject(3, interval);
            pstmt.setString(4, TaskStatus.AVAILABLE.name());
            pstmt.setString(5, createdBy);
            pstmt.setTimestamp(6, java.sql.Timestamp.from(Instant.now()));
            pstmt.executeUpdate();
            conn.commit();
            return PostgresqlTask.builder()
                    .name(name)
                    .bucketTime(bucketTime)
                    .bucketInterval(bucketInterval)
                    .status(TaskStatus.AVAILABLE)
                    .ptm(this).build();
        } catch (SQLException e) {
            // unique constraint violation
            if (e.getSQLState().equals("23505")) {
                String message = MessageFormat.format("Task with name {0} and bucket_time {1} already exists", name, bucketTime);
                throw new DuplicateTaskException(message, e);
            }
            throw new TaskManagerException("Unable to create task", e);
        } finally {
            closeWithoutException(pstmt);
            closeWithoutException(conn);
        }
    }

    @Override
    public Task getAndAcquireFirstTask(TaskQuery taskQuery) throws TaskManagerException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet resultSet = null;
        final String sql = sqlBuilder.taskQueryToSql(taskQuery) + " FOR UPDATE SKIP LOCKED LIMIT 1";
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            pstmt = conn.prepareStatement(sql);
            resultSet = pstmt.executeQuery();
            if (resultSet.next()) {
                return currentRowToTask(resultSet, conn);
            } else {
                closeWithoutException(conn);
                return null;
            }
        } catch (Exception e) {
            // on exception, close the connection
            closeWithoutException(conn);
            String message = MessageFormat.format("Unable to get tasks for query {0}", sql);
            throw new TaskManagerException(message, e);
        } finally {
            closeWithoutException(resultSet);
            closeWithoutException(pstmt);
        }
    }

    @Override
    public Task getTask(String name, Instant bucketTime) throws TaskManagerException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet resultSet = null;
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            pstmt = conn.prepareStatement(sqlBuilder.selectTask());
            pstmt.setString(1, name);
            pstmt.setTimestamp(2, Timestamp.from(bucketTime));
            resultSet = pstmt.executeQuery();
            if (resultSet.next()) {
                return currentRowToTask(resultSet);
            }
            return null;
        } catch (SQLException e) {
            String message = MessageFormat.format("Unable to get task with name {0} and bucket time {1}", name, bucketTime);
            throw new TaskManagerException(message, e);
        } finally {
            closeWithoutException(resultSet);
            closeWithoutException(pstmt);
            closeWithoutException(conn);
        }
    }

    protected PostgresqlTask currentRowToTask(ResultSet resultSet) throws SQLException {
        return currentRowToTask(resultSet, null);
    }

        /**
         * Converts the current row into a task.
         * Only supply the optional connection if you are running SQL that will result in immediately having a live object that has acquired the lock.
         * @param resultSet
         * @param conn
         * @return
         * @throws SQLException
         */
    protected PostgresqlTask currentRowToTask(ResultSet resultSet, Connection conn) throws SQLException {
        PostgresqlTask.PostgresqlTaskBuilder taskBuilder = PostgresqlTask.builder();
        taskBuilder.name(resultSet.getString(1));
        taskBuilder.bucketTime(resultSet.getTimestamp(2).toInstant());
        Object o = resultSet.getObject(3);
        if (o instanceof PGInterval) {
            taskBuilder.bucketInterval( PostgresqlTimeUtils.periodDurationFromPGInterval((PGInterval)o));
        }
        taskBuilder.status(TaskStatus.valueOf(resultSet.getString(4)));
        taskBuilder.createdBy(resultSet.getString(5));
        taskBuilder.createdAt(resultSet.getTimestamp(6).toInstant());
        taskBuilder.acquiredBy(resultSet.getString(7));
        taskBuilder.acquiredAt(resultSet.getTimestamp(8) == null ? null : resultSet.getTimestamp(8).toInstant());
        taskBuilder.ptm(this);
        if (conn != null) {
            taskBuilder.conn(conn);
        }
        return taskBuilder.build();
    }

    @Override
    public List<Task> getTasks(TaskQuery taskQuery) throws TaskManagerException {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet resultSet = null;
        final String sql = sqlBuilder.taskQueryToSql(taskQuery);
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            pstmt = conn.prepareStatement(sql);
            resultSet = pstmt.executeQuery();
            List<Task> tasks = new ArrayList<>();
            while (resultSet.next()) {
                tasks.add(currentRowToTask(resultSet));
            }
            return tasks;
        } catch (SQLException e) {
            String message = MessageFormat.format("Unable to get tasks for query {0}", sql);
            throw new TaskManagerException(message, e);
        } finally {
            closeWithoutException(resultSet);
            closeWithoutException(pstmt);
            closeWithoutException(conn);
        }
    }

    @Override
    public void setTaskStatus(Set<Task> tasks, TaskStatus updatedStatus, String acquiredBy) throws TaskManagerException {
        if (tasks.stream().filter(t -> t.isAcquired()).count() > 0) {
            throw new IllegalArgumentException("Cannot call set task status on tasks that are already acquired");
        }
        Connection conn = null;
        Statement stmt = null;
        ResultSet resultSet = null;
        final String sql = sqlBuilder.selectTasks(tasks) + " FOR UPDATE NOWAIT";
        try {
            conn = getConnection();
            conn.setAutoCommit(false);
            stmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
            resultSet = stmt.executeQuery(sql);
            while (resultSet.next()) {
                resultSet.updateString("status", updatedStatus.name());
                resultSet.updateTimestamp("acquired_at", java.sql.Timestamp.from(Instant.now()));
                resultSet.updateString("acquired_by", acquiredBy);
                resultSet.updateRow();
            }
            conn.commit();
        } catch (SQLException e) {
            if (e.getSQLState().equals("")) {

            } else {
                String message = MessageFormat.format("Unable to get tasks for query {0}", sql);
                throw new TaskManagerException(message, e);
            }
        } finally {
            closeWithoutException(resultSet);
            closeWithoutException(stmt);
            closeWithoutException(conn);
        }
    }

    protected static void rollbackWithoutException(Statement stmt, Logger logger) {
        if (stmt != null) {
            try {
                stmt.execute("ROLLBACK");
            } catch (SQLException e) {
                if (logger != null) {
                    try {
                        logger.error("Unable to rollback transaction", e);
                    } catch (Exception inner_e) {
                        // do nothing
                    }
                }
            }
        }
    }
}
