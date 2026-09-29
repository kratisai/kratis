package com.kratisai.controlplane.git.provider;

/**
 * Thrown when a remote repository with the requested name already exists and contains commits, so
 * creating it would either fail or push onto unrelated history.
 */
public class RemoteRepositoryExistsException extends RuntimeException {
    public RemoteRepositoryExistsException(String message) {
        super(message);
    }
}
