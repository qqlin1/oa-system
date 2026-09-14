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
        @ExceptionHandler(InvalidDepartmentHierarchyException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public  Result<Void> handlerInvalidDepartmentHierarchy(InvalidDepartmentHierarchyException e){
            return Result.fail(HttpStatus.CONFLICT.value(),e.getMessage());
        }
        @ExceptionHandler(DepartmentInUseException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Result<Void> handlerDepartmentInUse(DepartmentInUseException e){
            return Result.fail(HttpStatus.CONFLICT.value(), e.getMessage());
        }
        @ExceptionHandler(InvalidLeaveRequestException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Result<Void> handlerInvalidLeaveRequest(InvalidLeaveRequestException e){
            return Result.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage());
        }
        @ExceptionHandler(InvalidLeaveStatusException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Result<Void> handlerInvalidLeaveStatus(InvalidLeaveStatusException e){
            return Result.fail(HttpStatus.CONFLICT.value(), e.getMessage());
        }
        @ExceptionHandler(LeaveNotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        public Result<Void> handlerLeaveNotFound(LeaveNotFoundException e){
            return Result.fail(HttpStatus.NOT_FOUND.value(), e.getMessage());
        }
        @ExceptionHandler(InvalidBookingRequestException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Result<Void> handlerInvalidBookingRequest(InvalidBookingRequestException e){
            return Result.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage());
        }
        @ExceptionHandler(MeetingRoomNotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        public Result<Void> handlerMeetingRoomNotFound(MeetingRoomNotFoundException e){
            return Result.fail(HttpStatus.NOT_FOUND.value(), e.getMessage());
        }
        @ExceptionHandler(BookingConflictException.class)
        @ResponseStatus(HttpStatus.CONFLICT)
        public Result<Void> handlerBookingConflict(BookingConflictException e){
            return Result.fail(HttpStatus.CONFLICT.value(), e.getMessage());
        }

        @ExceptionHandler(FileNotFoundException.class)
        @ResponseStatus(HttpStatus.NOT_FOUND)
        public Result<Void> handlerFileNotFound(FileNotFoundException e){
            return Result.fail(HttpStatus.NOT_FOUND.value(), e.getMessage());
        }

        @ExceptionHandler(InvalidFileException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        public Result<Void> handlerInvalidFile(InvalidFileException e){
            return Result.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage());
        }

        @ExceptionHandler(FileStorageException.class)
        @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        public Result<Void> handlerFileStorage(FileStorageException e){
            // 磁盘错误不把底层原因暴露给前端（可能含服务器路径），只回一句通用的
            return Result.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "文件存储失败，请稍后重试");
        }

}
