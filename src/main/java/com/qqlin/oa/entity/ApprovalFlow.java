package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 审批链配置的一个节点。
 *
 * 请假单要走的路径（几级、每级谁审）存在这张表里，而不是写在代码里。
 *
 * 为什么这样设计：
 *   如果写死成「一级主管、二级总监」，那么公司改组织架构（加个 HR 备案）就要改代码发版。
 *   做成配置之后，加一级审批就是往这张表插一行数据。
 *   这和 RBAC 把权限做成数据是同一个思路 —— 把【会变的规则】从代码里挪到数据里。
 *
 * (biz_type, step) 上有唯一索引，保证同一个业务的同一级只能配一个节点。
 */
@TableName("sys_approval_flow")
public class ApprovalFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型，如 LEAVE */
    private String bizType;

    /** 第几级，从 1 开始 */
    private Integer step;

    /** 节点名称，如「直属主管审批」 */
    private String name;

    /** 审批人需要具备的角色编码，如 DEPT_MANAGER */
    private String approverRoleCode;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApproverRoleCode() {
        return approverRoleCode;
    }

    public void setApproverRoleCode(String approverRoleCode) {
        this.approverRoleCode = approverRoleCode;
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
}
