package com.example.leavemanagement.exception;

import java.util.List;

/** Thrown when an attendance upload fails resource/leave-policy validation (mapped to HTTP 400). */
public class AttendanceValidationException extends RuntimeException {

    private final List<String> errors;

    public AttendanceValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
