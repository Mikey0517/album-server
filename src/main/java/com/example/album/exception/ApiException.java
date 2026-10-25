package com.example.album.exception;

/** 业务异常，status 直接作为 HTTP 状态码返回。 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
