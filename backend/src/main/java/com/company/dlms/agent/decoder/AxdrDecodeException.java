package com.company.dlms.agent.decoder;

public class AxdrDecodeException extends RuntimeException {
    public AxdrDecodeException(String message) {
        super(message);
    }

    public AxdrDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}

