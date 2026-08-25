package com.qqlin.oa.exception;

public class DepartmentAlreadyExistsException extends RuntimeException{
    public DepartmentAlreadyExistsException(String message) {
        super(message);
    }
}
