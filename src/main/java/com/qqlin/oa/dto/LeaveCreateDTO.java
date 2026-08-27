package com.qqlin.oa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public class LeaveCreateDTO {

    @NotBlank(message = "请假类型不能为空")
    @Pattern(regexp = "^(ANNUAL|SICK|PERSONAL|OTHER)$",
            message = "请假类型不合法")
    private String leaveType;
    @NotNull(message = "请假开始时间不能为空")
    private LocalDateTime startTime;
    @NotNull(message = "请假结束时间不能为空")
    private LocalDateTime endTime;
    @NotBlank(message = "请假原因不能为空")
    @Size(max = 500,message = "请假原因不能超过500个字符")
    private String reason;

    public String getLeaveType() {
        return leaveType;
    }

    public void setLeaveType(String leaveType) {
        this.leaveType = leaveType;
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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
