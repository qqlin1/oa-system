package com.qqlin.oa.aspect;

import com.qqlin.oa.annotation.RequiresPermission;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.service.PermissionService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 权限切面：拦截所有标了 {@link RequiresPermission} 的方法，检查当前用户有没有对应权限。
 *
 * 为什么用 @Before 而不是 @Around：
 *   鉴权只需要「在方法执行前拦一道」，拦不住就抛异常。不需要耗时统计、不需要返回值、
 *   不需要在方法结束后做任何事 —— 这些都是 @Around 才能做的，这里用不上。
 *   选通知类型的原则是「够用就行」，多余的能力意味着多余的复杂度。
 *
 * @Order(2)：数值越大优先级越低。权限切面排在审计切面（@Order(1)）里面，
 * 这样「权限不足被拒绝」这个动作本身也会被记进操作日志 —— 越权尝试恰恰是最该留痕的。
 */
@Aspect
@Component
@Order(2)
public class PermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAspect.class);

    private final PermissionService permissionService;

    public PermissionAspect(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Pointcut("@annotation(requiresPermission)")
    public void permissionPointcut(RequiresPermission requiresPermission) {
    }

    @Before("permissionPointcut(requiresPermission)")
    public void check(JoinPoint joinPoint, RequiresPermission requiresPermission) {

        Long userId = resolveUserId(joinPoint.getArgs());

        if (userId == null) {
            // 拿不到操作人 = 无法鉴权。
            // 这里必须【拒绝】而不是放行 —— 「识别不出身份就当作有权限」是最典型的越权漏洞。
            // 安全相关的判断，默认值永远应该是「不允许」。
            log.warn("无法从入参识别当前用户，拒绝访问。method={}",
                    joinPoint.getSignature().toShortString());
            throw new ForbiddenException("无法识别当前用户，拒绝访问");
        }

        Set<String> owned = permissionService.getPermissions(userId);

        for (String required : requiresPermission.value()) {
            if (owned.contains(required)) {
                return;   // 满足任意一个即可
            }
        }

        log.warn("权限不足。userId={}, required={}, owned={}",
                userId, String.join("、", requiresPermission.value()), owned);
        throw new ForbiddenException("没有操作权限：" + String.join("、", requiresPermission.value()));
    }

    /**
     * 从方法入参里取出当前用户 ID。
     *
     * 本项目的约定：所有需要鉴权的 Service 方法，第一个参数都是 currentUserId，
     * 比如 approveLeave(Long currentUserId, Long leaveId, LeaveApprovalDTO dto)。
     *
     * 为什么按「类型 + 位置」取，而不是按参数名取：
     * 按参数名要靠反射读局部变量表，编译时必须加 -parameters 开关，
     * 否则拿到的是 arg0、arg1 这种名字。按类型取不依赖编译参数，更稳。
     *
     * 代价是这个约定必须靠人遵守。如果以后要支持更复杂的情况，
     * 可以加一个 @CurrentUserId 参数注解来显式标记，就不用靠位置猜了。
     */
    private Long resolveUserId(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Long value) {
                return value;
            }
        }
        return null;
    }
}
