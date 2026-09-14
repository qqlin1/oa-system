package com.qqlin.oa.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「这个操作需要记录操作日志」。
 *
 * 两个元注解都不能省：
 *
 *   @Target(METHOD)             —— 限制只能标在方法上。标到类上或字段上是没有意义的操作，
 *                                  加上它能在编译期就拦住误用。
 *   @Retention(RUNTIME)         —— 让注解在运行时仍然存在，能被反射读到。
 *                                  默认的 CLASS 策略只保留到 class 文件里，
 *                                  JVM 运行时读不到，AOP 就找不到它了 —— 切面会静默失效。
 *
 * 这个注解本身不包含任何逻辑，它只是一个标记。
 * 真正干活的是 {@link com.qqlin.oa.aspect.AuditLogAspect}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /** 操作名称，会写进日志表的 operation 字段，例如「审批请假」。 */
    String value();
}
