package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 操作日志。由 AOP 切面自动写入，业务代码不感知。
 *
 * 字段 status 有三种取值，区分它们很有必要：
 *
 *   成功 —— 方法正常返回，事务也正常提交
 *   失败 —— 方法抛了异常（业务校验没过、参数不合法等）
 *   回滚 —— 方法正常返回了，但外层事务最后回滚了
 *
 * 「成功」和「回滚」的区别是这套设计里最容易被忽略的一点：
 * 方法没抛异常不等于数据真的落库了 —— 事务提交失败一样会回滚。
 * 只记「成功/失败」两种状态的系统，会把回滚的操作误记成成功。
 */
@TableName("sys_operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作名称，直接取 @AuditLog 注解里的 value。 */
    private String operation;

    private Long operatorId;

    /** 被拦截的方法签名，如 LeaveService.cancelLeave(..)。 */
    private String method;

    private String params;

    private String status;

    private String errorMsg;

    private Long costMillis;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Long getCostMillis() {
        return costMillis;
    }

    public void setCostMillis(Long costMillis) {
        this.costMillis = costMillis;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
