package com.qqlin.oa.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class UserStatusUpdateDTO {
    @NotNull(message = "用户状态不能为空")
    @Min(value = 0,message = "用户状态只能是0或者1")
    @Max(value = 1,message = "用户状态只能是0或者1")
    private Integer status;

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
