package com.qqlin.oa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class MeetingRoomCreateDTO {

    @NotBlank(message = "会议室名称不能为空")
    @Size(max = 50, message = "会议室名称不能超过50个字符")
    private String name;

    @PositiveOrZero(message = "可容纳人数不能为负数")
    private Integer capacity;

    @Size(max = 100, message = "位置描述不能超过100个字符")
    private String location;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
