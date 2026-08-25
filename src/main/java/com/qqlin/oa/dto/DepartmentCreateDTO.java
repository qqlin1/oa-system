package com.qqlin.oa.dto;

import jakarta.validation.constraints.*;

public class DepartmentCreateDTO {
    @NotBlank(message = "部门名称不能为空")
    @Size(max=50,message = "部门名称不能超过50个字符")
    private String name;

    @NotNull(message = "上级部门ID不能为空")
    @PositiveOrZero(message = "上级部门ID不能小于0")
    private Long parentId;

    @Positive(message = "负责人ID必须大于0")
    private Long leaderId;

    @NotNull(message = "排序值不能为空")
    @PositiveOrZero(message = "排序值不能小于0")
    @Max(value = 9999,message = "排序值不能超过9999")
    private Integer sort;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public Long getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(Long leaderId) {
        this.leaderId = leaderId;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
