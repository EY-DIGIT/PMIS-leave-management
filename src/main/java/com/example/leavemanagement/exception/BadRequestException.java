package com.example.leavemanagement.exception;

/** Thrown when the client sends invalid data (mapped to HTTP 400). */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
