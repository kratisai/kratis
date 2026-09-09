package com.kratisai.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

class TransientDatabaseExceptionClassifierTest {

    @Test
    void transientSpringDataExceptions_areIdentifiedAsTransient() {
        assertThat(TransientDatabaseExceptionClassifier.isTransient(
                        new TransientDataAccessResourceException("Connection dropped")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(
                        new CannotCreateTransactionException("Could not open JDBC Connection")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(
                        new ConcurrencyFailureException("Deadlock detected")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new CannotAcquireLockException("Lock timeout")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new QueryTimeoutException("Query timed out")))
                .isTrue();
    }

    @Test
    void transientSqlStates_areIdentifiedAsTransient() {
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new SQLException("Admin termination", "57P01")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new SQLException("Connection failure", "08006")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new SQLException("Serialization failure", "40001")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new SQLException("Deadlock detected", "40P01")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new SQLException("Too many connections", "53300")))
                .isTrue();
    }

    @Test
    void closedConnectionMessages_areIdentifiedAsTransient() {
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new RuntimeException("Connection is closed")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(
                        new RuntimeException("FATAL: terminating connection due to administrator command")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new RuntimeException("Broken pipe (Write failed)")))
                .isTrue();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new RuntimeException("Connection reset by peer")))
                .isTrue();
    }

    @Test
    void nestedExceptions_areCorrectlyTraversed() {
        SQLException root = new SQLException("FATAL: terminating connection due to administrator command", "57P01");
        JpaSystemException wrapper = new JpaSystemException(new RuntimeException("Could not execute query", root));

        assertThat(TransientDatabaseExceptionClassifier.isTransient(wrapper)).isTrue();
    }

    @Test
    void nonTransientExceptions_returnFalse() {
        assertThat(TransientDatabaseExceptionClassifier.isTransient(null)).isFalse();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new NullPointerException("Null reference")))
                .isFalse();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(new IllegalArgumentException("Bad argument")))
                .isFalse();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(
                        new DataIntegrityViolationException("Unique key violation")))
                .isFalse();
        assertThat(TransientDatabaseExceptionClassifier.isTransient(
                        new SQLException("Unique constraint violation", "23505")))
                .isFalse();
    }
}
