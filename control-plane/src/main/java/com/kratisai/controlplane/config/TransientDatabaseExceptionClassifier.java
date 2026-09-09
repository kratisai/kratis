package com.kratisai.controlplane.config;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

public final class TransientDatabaseExceptionClassifier {

    private static final Set<String> TRANSIENT_SQL_STATES = Set.of(
            "57P01", // admin_shutdown
            "57P02", // crash_shutdown
            "57P03", // cannot_connect_now
            "08000", // connection_exception
            "08001", // sqlclient_unable_to_establish_sqlconnection
            "08003", // connection_does_not_exist
            "08004", // sqlserver_rejected_establishment_of_sqlconnection
            "08006", // connection_failure
            "08007", // transaction_resolution_unknown
            "40001", // serialization_failure
            "40P01", // deadlock_detected
            "53300" // too_many_connections
            );

    private TransientDatabaseExceptionClassifier() {}

    public static boolean isTransient(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TransientDataAccessException
                    || current instanceof CannotCreateTransactionException
                    || current instanceof ConcurrencyFailureException
                    || current instanceof QueryTimeoutException) {
                return true;
            }

            if (current instanceof SQLException sqlEx) {
                String sqlState = sqlEx.getSQLState();
                if (sqlState != null && TRANSIENT_SQL_STATES.contains(sqlState)) {
                    return true;
                }
                String msg = sqlEx.getMessage();
                if (msg != null && isConnectionClosedMessage(msg)) {
                    return true;
                }
            }

            String msg = current.getMessage();
            if (msg != null && isConnectionClosedMessage(msg)) {
                return true;
            }

            current = current.getCause();
        }
        return false;
    }

    private static boolean isConnectionClosedMessage(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("connection is closed")
                || lower.contains("connection closed")
                || lower.contains("terminating connection")
                || lower.contains("broken pipe")
                || lower.contains("connection reset")
                || lower.contains("connection refused");
    }
}
