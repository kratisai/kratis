package com.kratisai.controlplane.service;

import org.springframework.http.HttpStatus;

public class PublishException extends RuntimeException {

    private final ErrorCode errorCode;

    public enum ErrorCode {
        ENVIRONMENT_NOT_RUNNING(HttpStatus.CONFLICT),
        EXECUTION_RUNNING(HttpStatus.CONFLICT),
        REBASE_REQUIRED(HttpStatus.CONFLICT),
        REBASE_CONFLICT(HttpStatus.CONFLICT),
        PLAN_CHANGED(HttpStatus.CONFLICT),
        PUBLISH_IN_PROGRESS(HttpStatus.CONFLICT),
        PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
        GIT_REMOTE_UNAVAILABLE(HttpStatus.BAD_GATEWAY);

        private final HttpStatus httpStatus;

        ErrorCode(HttpStatus httpStatus) {
            this.httpStatus = httpStatus;
        }

        public HttpStatus httpStatus() {
            return httpStatus;
        }
    }

    public PublishException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PublishException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
