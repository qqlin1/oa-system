package com.qqlin.oa.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class BookingCreateDTO {

    @NotNull(message = "会议室ID不能为空")
    private Long roomId;
    @NotNull(message = "预订开始时间不能为空")
    private LocalDateTime startTime;
    @NotNull(message = "预订结束时间不能为空")
    private LocalDateTime endTime;

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }
}
