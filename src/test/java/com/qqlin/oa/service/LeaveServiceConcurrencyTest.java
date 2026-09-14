package com.qqlin.oa.service;

import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.exception.InvalidLeaveStatusException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.support.ApprovalFlowTestSupport;
import com.qqlin.oa.support.TestRoleAssigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class LeaveServiceConcurrencyTest {

    @Autowired
    private LeaveService leaveService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private DepartmentMapper departmentMapper;
    @Autowired
    private LeaveRequestMapper leaveRequestMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TestRoleAssigner roleAssigner;
    @Autowired
    private ApprovalFlowTestSupport approvalFlowTestSupport;

    // 这四个 ID 都是测试自己造出来的，测试结束要删掉
    private Long applicantId;   // 提交请假的人
    private Long adminId;       // 审批请假的管理员
    private Long leaveId;       // 那一条待审批的请假单
    private Long departmentId;  // 测试用的部门

    /**
     * 每个测试方法跑之前自己造一批干净的数据，完全不依赖数据库里已有的任何一行。
     */
    @BeforeEach
    void setUp() {
        // 这个类测的是并发控制，不是「审批几级」。切成单级审批，断言保持原意。
        approvalFlowTestSupport.useSingleLevelFlow();

        departmentId = createDepartment("并发测试部门_" + System.nanoTime());
        applicantId = createUser("test_applicant_" + System.nanoTime(), "USER", departmentId);
        adminId = createUser("test_admin_" + System.nanoTime(), "ADMIN", departmentId);
        leaveId = createPendingLeave(applicantId, departmentId);
    }

    /**
     * 手动删掉自己造的数据。
     * 这里不能用 @Transactional 回滚 —— 子线程的操作不在主线程的事务里，回滚不了。
     */
    @AfterEach
    void tearDown() {
        if (leaveId != null) {
            leaveRequestMapper.deleteById(leaveId);
        }
        if (applicantId != null) {
            userMapper.deleteById(applicantId);
        }
        if (adminId != null) {
            userMapper.deleteById(adminId);
        }
        if (departmentId != null) {
            departmentMapper.deleteById(departmentId);
        }

        // 还原现场
        approvalFlowTestSupport.useTwoLevelFlow();
    }

    @Test
    @DisplayName("20 个管理员同时审批同一条请假单，只能有 1 个成功")
    void shouldOnlyOneApprovalSucceedUnderConcurrency() throws InterruptedException {

        int threadCount = 20;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        // 三个发令器：
        // readyLatch —— 确认 20 个线程全都在起跑线上
        // startLatch —— 发令枪，让 20 个线程真正同时开跑
        // doneLatch  —— 确认 20 个线程全都跑完
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 线程安全的计数器：成功的算一个，拿到状态冲突异常的算另一个
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();   // 我准备好了
                    startLatch.await();       // 卡在这里，等发令枪响

                    LeaveApprovalDTO dto = new LeaveApprovalDTO();
                    dto.setDecision("APPROVED");
                    dto.setApprovalComment("并发测试");

                    leaveService.approveLeave(adminId, leaveId, dto);

                    successCount.incrementAndGet();   // 没抛异常，说明审批成功了

                } catch (InvalidLeaveStatusException e) {
                    // 这是我们期待的结果：状态已经被别人改了，这次操作被挡回来了
                    conflictCount.incrementAndGet();

                } catch (Exception e) {
                    // 其他任何异常都要打出来，不然会把真正的问题吞掉
                    otherErrorCount.incrementAndGet();
                    e.printStackTrace();

                } finally {
                    doneLatch.countDown();    // 我跑完了
                }
            });
        }

        readyLatch.await();      // 等 20 个线程全部就位
        startLatch.countDown();  // 开枪！
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);  // 等全部跑完，最多等 30 秒

        executorService.shutdown();

        System.out.println("审批成功：" + successCount.get()
                + " 个，状态冲突：" + conflictCount.get()
                + " 个，其他异常：" + otherErrorCount.get() + " 个");

        assertTrue(allDone, "30 秒内所有线程应当都跑完");
        assertEquals(0, otherErrorCount.get(), "不应当出现预期之外的异常");

        // 核心断言
        assertEquals(1, successCount.get(),
                "20 个线程同时审批同一条请假单，应当恰好 1 个成功");
        assertEquals(threadCount - 1, conflictCount.get(),
                "其余 19 个应当拿到状态冲突异常");

        // 最后确认数据库里的状态是对的
        LeaveRequest after = leaveRequestMapper.selectById(leaveId);
        assertEquals(LeaveStatus.APPROVED, after.getStatus(), "数据库里的状态应当是 APPROVED");
        assertEquals(adminId, after.getApproverId(), "审批人应当记录为那个成功的管理员");
    }

    @Test
    @DisplayName("10 个线程审批 + 10 个线程撤销同时发起，最终只有 1 个成功")
    void shouldOnlyOneSucceedWhenApproveAndCancelRace() throws InterruptedException {

        int approveThreads = 10;
        int cancelThreads = 10;
        int total = approveThreads + cancelThreads;

        ExecutorService executorService = Executors.newFixedThreadPool(total);

        CountDownLatch readyLatch = new CountDownLatch(total);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(total);

        AtomicInteger approveSuccess = new AtomicInteger(0);
        AtomicInteger cancelSuccess = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        // 前 10 个线程：管理员点「批准」
        for (int i = 0; i < approveThreads; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    LeaveApprovalDTO dto = new LeaveApprovalDTO();
                    dto.setDecision("APPROVED");
                    dto.setApprovalComment("并发测试-审批");

                    leaveService.approveLeave(adminId, leaveId, dto);

                    approveSuccess.incrementAndGet();

                } catch (InvalidLeaveStatusException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 后 10 个线程：申请人点「撤销」
        for (int i = 0; i < cancelThreads; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    leaveService.cancelLeave(applicantId, leaveId);

                    cancelSuccess.incrementAndGet();

                } catch (InvalidLeaveStatusException e) {
                    conflictCount.incrementAndGet();
                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();      // 等 20 个线程全部就位
        startLatch.countDown();  // 开枪！
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);

        executorService.shutdown();

        System.out.println("审批成功：" + approveSuccess.get()
                + " 个，撤销成功：" + cancelSuccess.get()
                + " 个，状态冲突：" + conflictCount.get()
                + " 个，其他异常：" + otherErrorCount.get() + " 个");

        assertTrue(allDone, "30 秒内所有线程应当都跑完");
        assertEquals(0, otherErrorCount.get(), "不应当出现预期之外的异常");

        // 核心断言：不管最后是审批赢还是撤销赢，只能有一个赢
        assertEquals(1, approveSuccess.get() + cancelSuccess.get(),
                "审批和撤销同时发起，最终只能有 1 个成功");
        assertEquals(total - 1, conflictCount.get(),
                "其余 19 个应当拿到状态冲突异常");

        // 最终状态必须和「谁赢了」对得上，不能出现两边都改了一部分的情况
        LeaveRequest after = leaveRequestMapper.selectById(leaveId);
        if (approveSuccess.get() == 1) {
            assertEquals(LeaveStatus.APPROVED, after.getStatus(),
                    "审批赢了，数据库状态应当是 APPROVED");
            assertEquals(adminId, after.getApproverId(), "审批人应当记录成功");
        } else {
            assertEquals(LeaveStatus.CANCELED, after.getStatus(),
                    "撤销赢了，数据库状态应当是 CANCELED");
        }
    }

    // ---------- 下面是造数据的工具方法 ----------

    private Long createDepartment(String name) {
        Department department = new Department();
        department.setName(name);
        department.setParentId(0L);
        department.setStatus(1);
        department.setSort(0);
        departmentMapper.insert(department);
        return department.getId();
    }

    private Long createUser(String username, String role, Long departmentId) {
        User user = new User();
        user.setUsername(username);
        user.setName(username);
        user.setPassword(passwordEncoder.encode("test123456"));
        user.setDepartmentId(departmentId);
        user.setStatus(1);
        user.setRole(role);
        user.setTokenVersion(0);
        userMapper.insert(user);
        roleAssigner.assign(user.getId(), role);
        return user.getId();
    }

    private Long createPendingLeave(Long applicantId, Long departmentId) {
        LeaveRequest leave = new LeaveRequest();
        leave.setApplicantId(applicantId);
        leave.setDepartmentId(departmentId);
        leave.setLeaveType("ANNUAL");
        leave.setStartTime(LocalDateTime.now().plusDays(1));
        leave.setEndTime(LocalDateTime.now().plusDays(2));
        leave.setReason("并发测试用请假单");
        leave.setStatus(LeaveStatus.PENDING);
        leaveRequestMapper.insert(leave);
        return leave.getId();
    }
}
