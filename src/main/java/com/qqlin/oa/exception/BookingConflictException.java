package com.qqlin.oa.exception;

/**
 * 会议室时段冲突：想订的时段已经被别人订走了。
 * 对应 HTTP 409。
 */
public class BookingConflictException extends RuntimeException {
    public BookingConflictException(String message) {
        super(message);
    }
}
