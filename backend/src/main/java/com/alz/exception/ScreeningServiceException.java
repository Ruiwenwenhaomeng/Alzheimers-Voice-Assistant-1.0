package com.alz.exception;

public class ScreeningServiceException extends RuntimeException {

    public ScreeningServiceException(String message) {
        super(message);
    }

    public ScreeningServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
