package com.ultra.dit.exception;

import org.springframework.http.HttpStatus;

public class SolverException extends RuntimeException {
    private final HttpStatus status;

    public SolverException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
