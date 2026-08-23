package com.qqlin.oa.exception;
import java.lang.RuntimeException;
public class  UnauthorizedException extends RuntimeException{
    public UnauthorizedException(String message) {
        super(message);
    }
}
