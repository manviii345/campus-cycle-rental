package com.cyclerental;

/**
 * An error that should be returned to the API caller with a specific HTTP
 * status code and a human-readable message (e.g. 404 "Cycle not found").
 */
public class ApiException extends RuntimeException {
    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
