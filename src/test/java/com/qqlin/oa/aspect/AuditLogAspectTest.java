package com.qqlin.oa.aspect;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.entity.OperationLog;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.exception.LeaveNotFoundException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.mapper.OperationLogMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.service.LeaveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 AOP 操作日志切面。
 *
 * 四个用例，缺一不可：
 *
 *   1. 标了 @AuditLog 的方法执行成功 → 记一条「成功」
 *   2. 标了 @AuditLog 的方法抛异常   → 记一条「失败」
 *   3. 没标注解的方法               → 一条都不记（防止切点写太宽）
 *   4. 外层事务回滚                 → 记一条「回滚」，且日志本身不被回滚掉
 *
 * 第 3 个用例和第 1 个一样重要：如果切点写成了「所有方法都拦」，
 * 第 1 个用例照样通过，但切面是错的。只有第 3 个能抓到这种错。
 *
 * 测试刻意避开 approveLeave（它会发 MQ 消息，broker 没启动时同步发送会阻塞重试，
 * 让用例变慢且不稳定）。成功路径用 cancelLeave 验证，效果等价。
 */
@SpringBootTest
class AuditLogAspectTest {

    @Autowired private LeaveService leaveService;
    @Autowired private OperationLogMapper operationLogMapper;
    @Autowired private LeaveRequestMapper leaveRequestMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long departmentId;
    private Long employeeId;
    private Long leaveId;

    @BeforeEach
    void setUp() {
        departmentId = createDepartment("日志测试部门_" + System.nanoTime());
        employeeId = createUser("audit_emp_" + System.nanoTime());
        leaveId = createPendingLeave(employeeId);
    }

    @AfterEach
    void tearDown() {
        operationLogMapper.delete(new LambdaQueryWrapper<OperationLog>()
                .eq(OperationLog::getOperatorId, employeeId));
        leaveRequestMapper.deleteById(leaveId);
        userMapper.deleteById(employeeId);
        departmentMapper.deleteById(departmentId);
    }

    @Test
    @DisplayName("标记了 @AuditLog 的方法执行成功，应当记一条「成功」日志")
    void shouldRecordSuccessLogWhenAnnotatedMethodReturns() {
        long before = countLogs("撤销请假");

        leaveService.cancelLeave(employeeId, leaveId);

        List<OperationLog> logs = queryLogs("撤销请假");
        assertEquals(before + 1, logs.size(),
                "撤销请假是标了 @AuditLog 的方法，执行后应当多一条日志");

        OperationLog latest = logs.get(logs.size() - 1);
        assertEquals("成功", latest.getStatus(), "方法正常返回，状态应当是「成功」");
        assertEquals(employeeId, latest.getOperatorId(),
                "操作人应当是调用方法的那个用户（取入参里第一个 Long）");
        assertEquals("撤销请假", latest.getOperation());
    }

    @Test
    @DisplayName("标记了 @AuditLog 的方法抛异常，应当记一条「失败」日志")
    void shouldRecordFailureLogWhenAnnotatedMethodThrows() {
        long before = countLogs("撤销请假");
        long notExistLeaveId = 999999999L;

        assertThrows(LeaveNotFoundException.class,
                () -> leaveService.cancelLeave(employeeId, notExistLeaveId),
                "撤销一张不存在的请假单，应当抛出「请假申请不存在」");

        List<OperationLog> logs = queryLogs("撤销请假");
        assertEquals(before + 1, logs.size(),
                "方法抛了异常也要记日志 —— 失败的操作往往比成功的更值得查");

        OperationLog latest = logs.get(logs.size() - 1);
        assertEquals("失败", latest.getStatus(), "方法抛异常，状态应当是「失败」");
        assertEquals("请假申请不存在", latest.getErrorMsg(), "应当记下异常信息");
    }

    @Test
    @DisplayName("没标记 @AuditLog 的方法，不应当产生任何日志")
    void shouldNotRecordLogForMethodWithoutAnnotation() {
        long before = operationLogMapper.selectCount(null);

        leaveService.getLeaveList(employeeId, 1L, 10L);

        long after = operationLogMapper.selectCount(null);
        assertEquals(before, after,
                "getLeaveList 没有标 @AuditLog，不应当产生日志。"
                        + "如果这里失败，说明切点写太宽，把所有方法都拦了");
    }

    @Test
    @DisplayName("外层事务回滚时，日志仍要落库，且状态标记为「回滚」")
    void shouldMarkRolledBackWhenOuterTransactionRollsBack() {
        long before = countLogs("撤销请假");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.execute(status -> {
            leaveService.cancelLeave(employeeId, leaveId);
            status.setRollbackOnly();
            return null;
        });

        // 先确认回滚真的发生了，否则这个用例会「假通过」
        LeaveRequest afterRollback = leaveRequestMapper.selectById(leaveId);
        assertEquals(LeaveStatus.PENDING, afterRollback.getStatus(),
                "外层事务回滚了，请假单状态应当回到 PENDING，而不是 CANCELED");

        List<OperationLog> logs = queryLogs("撤销请假");
        assertEquals(before + 1, logs.size(),
                "日志用的是独立事务（REQUIRES_NEW），外层回滚不会把它一起回滚掉");

        OperationLog latest = logs.get(logs.size() - 1);
        assertEquals("回滚", latest.getStatus(),
                "方法没抛异常但事务回滚了，状态应当是「回滚」而不是「成功」");
    }

    private long countLogs(String operation) {
        return operationLogMapper.selectCount(
                new LambdaQueryWrapper<OperationLog>()
                        .eq(OperationLog::getOperation, operation)
                        .eq(OperationLog::getOperatorId, employeeId));
    }

    private List<OperationLog> queryLogs(String operation) {
        return operationLogMapper.selectList(
                new LambdaQueryWrapper<OperationLog>()
                        .eq(OperationLog::getOperation, operation)
                        .eq(OperationLog::getOperatorId, employeeId)
                        .orderByAsc(OperationLog::getId));
    }

    private Long createDepartment(String name) {
        Department d = new Department();
        d.setName(name);
        d.setParentId(0L);
        d.setStatus(1);
        d.setSort(0);
        departmentMapper.insert(d);
        return d.getId();
    }

    private Long createUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setName(username);
        u.setPassword(passwordEncoder.encode("test123456"));
        u.setDepartmentId(departmentId);
        u.setStatus(1);
        u.setRole("USER");
        u.setTokenVersion(0);
        userMapper.insert(u);
        return u.getId();
    }

    private Long createPendingLeave(Long applicantId) {
        LeaveRequest leave = new LeaveRequest();
        leave.setApplicantId(applicantId);
        leave.setDepartmentId(departmentId);
        leave.setLeaveType("ANNUAL");
        leave.setStartTime(LocalDateTime.now().plusDays(1));
        leave.setEndTime(LocalDateTime.now().plusDays(2));
        leave.setReason("AOP 操作日志测试");
        leave.setStatus(LeaveStatus.PENDING);
        leaveRequestMapper.insert(leave);
        return leave.getId();
    }
}
