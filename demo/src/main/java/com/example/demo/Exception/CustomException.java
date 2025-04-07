package com.example.demo.Exception;

public class CustomException extends RuntimeException {

    private String code;
    private String msg;

    public CustomException(String code, String message) {
        this.code = code;
        this.msg = message;
    }

    @Override
    public String getMessage() {
        return msg;
    }

    public void setMessage(String message) {
        this.msg = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
