package com.recommend.client;

/** Raised when the FastAPI AI service is unreachable, misconfigured or failing. */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
