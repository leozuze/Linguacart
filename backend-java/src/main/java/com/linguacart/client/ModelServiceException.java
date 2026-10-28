package com.linguacart.client;

import org.springframework.http.HttpStatus;

public class ModelServiceException extends RuntimeException {

    private final HttpStatus status;

    public ModelServiceException(String message) {
        this(message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public ModelServiceException(String message, HttpStatus status) {
        super(message);
        this.status = status != null ? status : HttpStatus.SERVICE_UNAVAILABLE;
    }

    public ModelServiceException(String message, Throwable cause) {
        this(message, cause, HttpStatus.SERVICE_UNAVAILABLE);
    }

    public ModelServiceException(String message, Throwable cause, HttpStatus status) {
        super(message, cause);
        this.status = status != null ? status : HttpStatus.SERVICE_UNAVAILABLE;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
