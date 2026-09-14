package com.qqlin.oa.aspect;

import com.qqlin.oa.annotation.AuditLog;
import com.qqlin.oa.entity.OperationLog;
import com.qqlin.oa.service.OperationLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;

/**
 * 操作日志切面。
 *
 * 它做的事：凡是标记了 {@link AuditLog} 的方法，执行前后自动记一条日志，
 * 包括谁做的、做了什么、耗时多久、最后是成功还是失败。
 *
 * 两个注解缺一不可：
 *   @Aspect    —— 声明「我是一个切面」，里面的 @Pointcut / @Around 才会被解析
 *   @Component —— 把它交给 Spring 管理。少了这个，Spring 根本不知道有这个类，
 *                 切面不会生效，而且控制台不会有任何报错。
 */
@Aspect
@Component
@Order(1)
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);

    /** 入库字段有长度限制，超长截断，避免因为一条超长参数把插入搞失败。 */
    private static final int MAX_TEXT_LENGTH = 500;

    private final OperationLogService operationLogService;

    public AuditLogAspect(OperationLogService operationLogService) {
        this.operationLogService = operationLogService;
    }

    /**
     * 切点：凡是标注了 @AuditLog 的方法。
     *
     * 写法 @annotation(auditLog) 的意思是「匹配带这个注解的方法，并且把注解实例
     * 绑定成参数 auditLog」。绑定之后通知方法里就能直接读到注解的 value，
     * 不用再去反射拿一遍。
     */
    @Pointcut("@annotation(auditLog)")
    public void auditLogPointcut(AuditLog auditLog) {
    }

    /**
     * 通知：包住整个目标方法。
     *
     * 用 @Around 而不是 @Before，是因为要同时拿到三样东西：
     *   1. 方法开始前的时间戳（算耗时）
     *   2. 方法有没有抛异常（判断成败）
     *   3. 外层事务最后是提交还是回滚（判断「成功」还是「回滚」）
     * 只有 @Around 能一个方法全包住。
     */
    @Around("auditLogPointcut(auditLog)")
    public Object record(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {

        OperationLog record = new OperationLog();
        record.setOperation(auditLog.value());
        record.setMethod(joinPoint.getSignature().toShortString());
        record.setParams(safeParams(joinPoint.getArgs()));
        record.setOperatorId(resolveOperatorId(joinPoint.getArgs()));

        long start = System.currentTimeMillis();
        Throwable error = null;

        try {
            // 这一句就是「放行」：去执行真正的业务方法。
            // 不调它，业务方法根本不会执行 —— @Around 是唯一能阻止方法执行的通知。
            return joinPoint.proceed();

        } catch (Throwable e) {
            error = e;
            // 原样抛出，绝不吞掉。
            // 记日志是附加功能，不能因为它改变了业务的成败语义。
            throw e;

        } finally {
            record.setCostMillis(System.currentTimeMillis() - start);
            final Throwable failure = error;
            writeLog(record, failure);
        }
    }

    /**
     * 决定什么时候写、写什么状态。
     *
     * 这里分两种情况，原因是「方法没抛异常」并不等于「数据真的落库了」：
     *
     *   情况一：当前有事务在跑。
     *     方法的 finally 执行时，事务还没提交（要等方法返回后才提交）。
     *     这时候下结论「成功」为时过早 —— 提交阶段失败照样会回滚。
     *     所以注册一个回调，等事务真正结束（提交或回滚）之后再写。
     *
     *   情况二：当前没有事务。
     *     方法正常返回就是真的成功了，直接写。
     */
    private void writeLog(OperationLog record, Throwable error) {

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            applyResult(record, error, status);
                            saveQuietly(record);
                        }
                    });
        } else {
            // 没有事务时，方法正常返回就算成功。
            applyResult(record, error, TransactionSynchronization.STATUS_COMMITTED);
            saveQuietly(record);
        }
    }

    /**
     * 根据「有没有抛异常」和「事务结局」判断最终状态。
     *
     * 优先级：抛异常 > 事务结局。
     * 抛了异常一定是失败（哪怕事务最后是提交，业务上也已经报错了）。
     */
    private void applyResult(OperationLog record, Throwable error, int transactionStatus) {
        if (error != null) {
            record.setStatus("失败");
            record.setErrorMsg(truncate(error.getMessage()));
        } else if (transactionStatus == TransactionSynchronization.STATUS_COMMITTED) {
            record.setStatus("成功");
        } else {
            record.setStatus("回滚");
        }
    }

    /**
     * 写日志，失败也不能影响业务。
     *
     * 记日志是辅助功能。如果日志表写不进去（表不存在、字段超长、数据库抖动），
     * 正确的处理是打一行警告然后放过，绝不能让「审批请假」这种核心操作
     * 因为记不了日志而失败。
     */
    private void saveQuietly(OperationLog record) {
        try {
            operationLogService.save(record);
        } catch (Exception e) {
            log.warn("保存操作日志失败，不影响业务。operation={}", record.getOperation(), e);
        }
    }

    /**
     * 取操作人 ID：取入参里第一个 Long 类型的参数。
     *
     * 这是本项目的一个约定 —— 所有 Service 方法的第一个参数都是 currentUserId，
     * 比如 cancelLeave(Long currentUserId, long leaveId)。
     * 按约定取值比按参数名取值可靠：按参数名要靠反射读局部变量表，
     * 编译时没加 -parameters 开关就读不到名字。
     *
     * 找不到 Long 就返回 null（日志里 operator_id 为空），不抛异常。
     */
    private Long resolveOperatorId(Object[] args) {
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

    private String safeParams(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        try {
            return truncate(Arrays.toString(args));
        } catch (Exception e) {
            return "<参数无法转换为文本>";
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TEXT_LENGTH
                ? text
                : text.substring(0, MAX_TEXT_LENGTH);
    }
}
