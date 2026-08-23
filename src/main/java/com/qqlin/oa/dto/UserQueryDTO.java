package com.qqlin.oa.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public class UserQueryDTO {
    private String username;
    @Min(value = 0,message = "用户的状态只能是0或1")
    @Max(value = 1,message = "用户的状态只能是0或1")
    private Integer status;


    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
