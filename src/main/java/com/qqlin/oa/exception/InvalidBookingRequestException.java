package com.qqlin.oa.exception;

/**
 * 预订请求本身不合法：时间为空、结束时间不晚于开始时间、开始时间早于当前时间。
 * 对应 HTTP 400。
 */
public class InvalidBookingRequestException extends RuntimeException {
    public InvalidBookingRequestException(String message) {
        super(message);
    }
}
