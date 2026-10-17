package ru.acr.altda.go;

public class GoClientException extends RuntimeException {

    public GoClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public GoClientException(String message) {
        super(message);
    }
}
