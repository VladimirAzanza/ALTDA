package ru.acr.altda.go;

/**
 * Failure from the Go skills service. {@link #getErrorCode()} is one of the fixed contract codes
 * ({@code bad_request}, {@code node_locked}, {@code llm_timeout}, {@code llm_error}, {@code schema_invalid})
 * when the Go body provided it.
 */
public class GoClientException extends RuntimeException {

    public static final String BAD_REQUEST = "bad_request";
    public static final String NODE_LOCKED = "node_locked";
    public static final String LLM_TIMEOUT = "llm_timeout";
    public static final String LLM_ERROR = "llm_error";
    public static final String SCHEMA_INVALID = "schema_invalid";

    private final String errorCode;
    private final int httpStatus;

    public GoClientException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode == null ? LLM_ERROR : errorCode;
        this.httpStatus = httpStatus;
    }

    public GoClientException(String errorCode, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode == null ? LLM_ERROR : errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isNodeLocked() {
        return NODE_LOCKED.equals(errorCode);
    }
}
