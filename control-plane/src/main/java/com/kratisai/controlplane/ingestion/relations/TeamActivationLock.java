package com.kratisai.controlplane.ingestion.relations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

@Component
class TeamActivationLock {
    private static final String LOCK_SQL = "SELECT pg_advisory_lock(?, ?)";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(?, ?)";

    private final DataSource dataSource;
    private final ConcurrentHashMap<UUID, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    TeamActivationLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    TeamLock acquire(UUID teamId) {
        Objects.requireNonNull(teamId, "teamId is required");
        ReentrantLock localLock = localLocks.computeIfAbsent(teamId, ignored -> new ReentrantLock());
        try {
            localLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for team activation lock for " + teamId, e);
        }
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            lock(connection, teamId);
            return new TeamLock(connection, teamId, localLock);
        } catch (SQLException e) {
            closeQuietly(connection);
            localLock.unlock();
            throw new IllegalStateException("Failed to acquire team activation lock for " + teamId, e);
        } catch (RuntimeException | Error e) {
            closeQuietly(connection);
            localLock.unlock();
            throw e;
        }
    }

    private static void lock(Connection connection, UUID teamId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_SQL)) {
            statement.setInt(1, hi(teamId));
            statement.setInt(2, lo(teamId));
            statement.execute();
        }
    }

    private static int hi(UUID id) {
        return (int) (id.getMostSignificantBits() >>> 32);
    }

    private static int lo(UUID id) {
        return (int) id.getLeastSignificantBits();
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Original failure is rethrown by caller.
        }
    }

    static final class TeamLock implements AutoCloseable {
        private final Connection connection;
        private final UUID teamId;
        private final ReentrantLock localLock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TeamLock(Connection connection, UUID teamId, ReentrantLock localLock) {
            this.connection = connection;
            this.teamId = teamId;
            this.localLock = localLock;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try (Connection held = connection;
                    PreparedStatement statement = held.prepareStatement(UNLOCK_SQL)) {
                statement.setInt(1, hi(teamId));
                statement.setInt(2, lo(teamId));
                statement.execute();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to release team activation lock for " + teamId, e);
            } finally {
                localLock.unlock();
            }
        }
    }
}
