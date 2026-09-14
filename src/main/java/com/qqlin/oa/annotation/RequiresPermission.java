package com.qqlin.oa.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明「调这个方法需要什么权限」。
 *
 * 为什么要把权限要求写在方法上，而不是在方法体里手动调 requireAdmin：
 *
 *   1. 【看得见】权限要求和业务代码在同一个地方，读代码时一眼能看到这个接口要什么权限。
 *      手动调用的写法，权限校验混在几十行业务逻辑里，很容易被忽略。
 *   2. 【漏不掉】新加一个方法忘了加注解，code review 时对着方法签名就能发现。
 *      手动调用的写法，漏了不会报错、不会崩溃、测试也发现不了 —— 直接是越权漏洞。
 *   3. 【改得动】权限编码是数据。把「谁能审批请假」从部门经理改成人事专员，
 *      是改 sys_role_permission 表里的一行，不是改代码。
 *
 * 用法：
 * <pre>
 *   &#64;RequiresPermission("leave:approve")
 *   public void approveLeave(Long currentUserId, Long leaveId, LeaveApprovalDTO dto) { ... }
 * </pre>
 *
 * 多个权限时是「满足任一即可」，比如：
 * <pre>
 *   &#64;RequiresPermission({"leave:approve", "leave:approve-all"})
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 需要的权限编码，满足其中任意一个即可通过。 */
    String[] value();
}
