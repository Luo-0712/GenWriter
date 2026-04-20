package com.example.genwriter.exception;

public class DocumentLoadException extends RuntimeException {
    public DocumentLoadException(String message) {
        super(message);
    }

    public DocumentLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}