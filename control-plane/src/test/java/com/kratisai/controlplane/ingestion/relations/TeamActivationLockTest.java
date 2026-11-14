package com.kratisai.controlplane.ingestion.relations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

class TeamActivationLockTest {

    @Test
    void concurrentWaitersForSameTeamNeverHoldMoreThanOneJdbcConnection() throws Exception {
        FakeDataSource dataSource = new FakeDataSource();
        TeamActivationLock activationLock = new TeamActivationLock(dataSource);
        UUID teamId = UUID.randomUUID();
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch waitersStarted = new CountDownLatch(4);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> {
                try (TeamActivationLock.TeamLock lock = activationLock.acquire(teamId)) {
                    firstAcquired.countDown();
                    assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
                    lock.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?>[] waiters = new Future<?>[4];
            for (int i = 0; i < waiters.length; i++) {
                waiters[i] = executor.submit(() -> {
                    waitersStarted.countDown();
                    try (TeamActivationLock.TeamLock ignored = activationLock.acquire(teamId)) {
                        assertThat(dataSource.openConnections.get()).isEqualTo(1);
                    }
                });
            }
            assertThat(waitersStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            assertThat(dataSource.openConnections.get()).isEqualTo(1);
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            for (Future<?> waiter : waiters) {
                waiter.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(dataSource.peakConnections.get()).isEqualTo(1);
        assertThat(dataSource.openConnections.get()).isZero();
        assertThat(dataSource.totalCheckouts.get()).isEqualTo(5);
    }

    @Test
    void releasesLocalLockWhenJdbcAcquisitionFailsOrUnlockThrows() {
        FakeDataSource dataSource = new FakeDataSource();
        dataSource.scriptedModes.add(ConnectionMode.FAIL_PREPARE);
        dataSource.scriptedModes.add(ConnectionMode.FAIL_UNLOCK);
        dataSource.scriptedModes.add(ConnectionMode.HEALTHY);

        TeamActivationLock activationLock = new TeamActivationLock(dataSource);
        UUID teamId = UUID.randomUUID();

        assertThatThrownBy(() -> activationLock.acquire(teamId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to acquire team activation lock");
        assertThat(dataSource.openConnections.get()).isZero();

        TeamActivationLock.TeamLock held = activationLock.acquire(teamId);
        assertThatThrownBy(held::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to release team activation lock");
        assertThat(dataSource.openConnections.get()).isZero();

        try (TeamActivationLock.TeamLock next = activationLock.acquire(teamId)) {
            assertThat(next).isNotNull();
        }
        assertThat(dataSource.openConnections.get()).isZero();
    }

    @Test
    void interruptedWaiterRestoresInterruptFlagAndDoesNotTouchDataSource() throws Exception {
        FakeDataSource dataSource = new FakeDataSource();
        TeamActivationLock activationLock = new TeamActivationLock(dataSource);
        UUID teamId = UUID.randomUUID();

        try (TeamActivationLock.TeamLock ignored = activationLock.acquire(teamId);
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> interrupted = executor.submit(() -> {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> activationLock.acquire(teamId))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Interrupted while waiting");
                return Thread.currentThread().isInterrupted();
            });
            assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(dataSource.totalCheckouts.get()).isEqualTo(1);
        assertThat(dataSource.openConnections.get()).isZero();
    }

    private enum ConnectionMode {
        HEALTHY,
        FAIL_PREPARE,
        FAIL_UNLOCK
    }

    private static final class FakeDataSource extends AbstractDataSource {
        final AtomicInteger openConnections = new AtomicInteger();
        final AtomicInteger peakConnections = new AtomicInteger();
        final AtomicInteger totalCheckouts = new AtomicInteger();
        final Deque<ConnectionMode> scriptedModes = new ArrayDeque<>();

        @Override
        public Connection getConnection() {
            totalCheckouts.incrementAndGet();
            int current = openConnections.incrementAndGet();
            peakConnections.accumulateAndGet(current, Math::max);
            ConnectionMode mode = scriptedModes.isEmpty() ? ConnectionMode.HEALTHY : scriptedModes.removeFirst();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            if (mode == ConnectionMode.FAIL_PREPARE) {
                                throw new SQLException("lock failed");
                            }
                            String sql = (String) args[0];
                            yield proxyStatement(mode == ConnectionMode.FAIL_UNLOCK && sql.contains("unlock"));
                        }
                        case "close" -> {
                            openConnections.decrementAndGet();
                            yield null;
                        }
                        default -> defaultReturn(method.getReturnType());
                    });
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        private static PreparedStatement proxyStatement(boolean failExecute) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[] {PreparedStatement.class},
                    (proxy, method, args) -> {
                        if ("execute".equals(method.getName()) && failExecute) {
                            throw new SQLException("unlock failed");
                        }
                        return defaultReturn(method.getReturnType());
                    });
        }

        private static Object defaultReturn(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            return null;
        }
    }
}
