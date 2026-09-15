package com.portfolio.fanevent.waitingroom;

public class AdmissionTokenException extends RuntimeException {

    private final String code;

    public AdmissionTokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
