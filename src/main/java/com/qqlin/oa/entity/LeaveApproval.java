package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 请假审批流水：每通过/拒绝一级，就往这里写一条。
 *
 * 【为什么必须有这张表】这是多级审批最该讲清楚的一点。
 *
 * 请假单主表上只有 approver_id / approval_comment / approval_time 三个字段，
 * 它们只能记录【最后一次】审批。走两级审批时：
 *
 *     第 1 级：主管 A 同意  → 主表记下 approver_id = A
 *     第 2 级：总监 B 同意  → 主表把 approver_id 覆盖成 B
 *
 * 结果就是：A 的审批记录【被覆盖没了】。事后要查「这一级是谁批的、什么时候批的」，
 * 主表上根本查不到。
 *
 * 流水表解决了三件事：
 *   ① 可追溯：每一级谁批的、什么时候批的、写了什么意见，一条一条都在。
 *   ② 可重放：万一主表字段被写坏了，能从流水里恢复出完整过程。
 *   ③ 可审计：这是 OA 系统里最常被查的数据，法务和 HR 都要用。
 *
 * (leave_id, step) 上有唯一索引，这是并发安全的最后一道保险：
 * 两个人同时审同一级，条件更新会先挡掉一个，万一都绕过了，
 * 唯一索引也会让第二条流水插不进去。
 */
@TableName("sys_leave_approval")
public class LeaveApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long leaveId;

    /** 第几级审批 */
    private Integer step;

    private Long approverId;

    /** APPROVED 同意 / REJECTED 拒绝 */
    private String decision;

    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLeaveId() {
        return leaveId;
    }

    public void setLeaveId(Long leaveId) {
        this.leaveId = leaveId;
    }

    public Integer getStep() {
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    public Long getApproverId() {
        return approverId;
    }

    public void setApproverId(Long approverId) {
        this.approverId = approverId;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
