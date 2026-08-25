package com.qqlin.oa.exception;

import com.qqlin.oa.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
        @ExceptionHandler(UserNotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        public Result<Void> handleUserNotFound(UserNotFoundException e){
            return Result.fail(HttpStatus.NOT_FOUND.value(),e.getMessage());
        }
        @ExceptionHandler(UsernameAlreadyExistsException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Result<Void> handleUsernameAlreadyExists(UsernameAlreadyExistsException e){
            return Result.fail(HttpStatus.CONFLICT.value(), e.getMessage());
        }
        @ExceptionHandler(MethodArgumentNotValidException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Result<Void> handleValidation(MethodArgumentNotValidException e){
            FieldError fieldError=e.getBindingResult().getFieldError();
            String message;
            if(fieldError==null){
                 message= "请求参数不合法";
            }else {
                 message=fieldError.getDefaultMessage();
            }
            return Result.fail(HttpStatus.BAD_REQUEST.value(), message);
        }
        @ExceptionHandler(HandlerMethodValidationException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Result<Void> handleMethodValidation(HandlerMethodValidationException e) {
            String message = "请求参数不合法";
            if (!e.getAllErrors().isEmpty()) {
                String defaultMessage =
                        e.getAllErrors()
                                .getFirst()
                                .getDefaultMessage();
                if (defaultMessage != null) {
                    message = defaultMessage;
                }

            }

            return Result.fail(HttpStatus.BAD_REQUEST.value(), message);
        }
        @ExceptionHandler(UnauthorizedException.class)
        @ResponseStatus(HttpStatus.UNAUTHORIZED)
        public Result<Void> handleUnauthoriezd(UnauthorizedException e){
            return Result.fail(HttpStatus.UNAUTHORIZED.value(), e.getMessage());
        }
        @ExceptionHandler(ForbiddenException.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        public Result<Void>  handleForbidden(ForbiddenException e){
            return Result.fail(HttpStatus.FORBIDDEN.value(), e.getMessage());
        }
        @ExceptionHandler(DepartmentNotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        public Result<Void> handlerDepartmentNotFound(DepartmentNotFoundException e){
            return Result.fail(HttpStatus.NOT_FOUND.value(), e.getMessage());
        }
        @ExceptionHandler(DepartmentAlreadyExistsException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Result<Void> handlerDepartmentAlreadyExists(DepartmentAlreadyExistsException e){
            return Result.fail(HttpStatus.CONFLICT.value(), e.getMessage());
        }
}
