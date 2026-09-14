package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 角色。
 *
 * 角色存在的意义是「权限的容器」：权限点本身是固定的（比如「审批请假」），
 * 但哪些人能审批是会变的。把权限打包成角色，改的时候改角色就行，不用改代码。
 *
 * data_scope 是数据权限，回答的是另一个问题：能对「谁的数据」做这件事。
 *   1 = 全部数据
 *   2 = 本部门及以下
 *   3 = 本部门
 *   4 = 仅本人
 * 一个人有多个角色时，取最宽的那个（数字最小的）。
 */
@TableName("sys_role")
public class Role {

    /** 数据权限：全部 */
    public static final int SCOPE_ALL = 1;
    /** 数据权限：本部门及以下 */
    public static final int SCOPE_DEPT_AND_BELOW = 2;
    /** 数据权限：本部门 */
    public static final int SCOPE_DEPT = 3;
    /** 数据权限：仅本人 */
    public static final int SCOPE_SELF = 4;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private Integer dataScope;
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getDataScope() {
        return dataScope;
    }

    public void setDataScope(Integer dataScope) {
        this.dataScope = dataScope;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
