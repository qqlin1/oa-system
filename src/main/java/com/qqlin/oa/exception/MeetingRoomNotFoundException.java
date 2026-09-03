package com.qqlin.oa.exception;

/**
 * 会议室不存在，或者存在但已停用。
 * 对应 HTTP 404。
 */
public class MeetingRoomNotFoundException extends RuntimeException {
    public MeetingRoomNotFoundException(String message) {
        super(message);
    }
}
