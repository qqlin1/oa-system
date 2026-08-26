package com.qqlin.oa.exception;

public class DepartmentInUseException extends RuntimeException{
    public DepartmentInUseException(String message) {
        super(message);
    }
}
