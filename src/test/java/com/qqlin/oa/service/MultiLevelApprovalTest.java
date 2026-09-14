package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.entity.ApprovalFlow;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.LeaveApproval;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.entity.UserRole;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.InvalidLeaveStatusException;
import com.qqlin.oa.mapper.ApprovalFlowMapper;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.LeaveApprovalMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.mapper.UserRoleMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多级审批流专项测试。
 *
 * 审批链配置（来自 schema.sql 种子数据）：
 *   第 1 级：直属主管审批    需要 DEPT_MANAGER 角色
 *   第 2 级：部门负责人审批  需要 ADMIN 角色
 *
 * 核心命题：
 *   ① 中间级通过后状态仍然是 PENDING，只有走到最后一级才变 APPROVED
 *   ② 每一级都要有审批流水，否则主表上第一级的信息会被第二级覆盖
 *   ③ 条件更新里必须带 current_step，否则同一级会被审两次
 */
@SpringBootTest
class MultiLevelApprovalTest {

    @Autowired private LeaveService leaveService;
    @Autowired private ApprovalFlowService approvalFlowService;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private LeaveRequestMapper leaveRequestMapper;
    @Autowired private LeaveApprovalMapper leaveApprovalMapper;
    @Autowired private ApprovalFlowMapper approvalFlowMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TestRoleAssigner roleAssigner;
    @Autowired private ApprovalFlowTestSupport approvalFlowTestSupport;

    private Long departmentId;
    private Long adminId;
    private Long managerId;
    private Long managerBId;
    private Long employeeId;
    private Long leaveId;

    @BeforeEach
    void setUp() {
        // 这个类专门测多级，确保是两级配置
        approvalFlowTestSupport.useTwoLevelFlow();

        String tag = "_" + System.nanoTime();
        departmentId = createDepartment("多级审批测试部门" + tag);
        adminId = createUser("mlm_admin" + tag, "ADMIN");
        managerId = createUser("mlm_mgrA" + tag, "DEPT_MANAGER");
        managerBId = createUser("mlm_mgrB" + tag, "DEPT_MANAGER");
        employeeId = createUser("mlm_emp" + tag, "USER");
        leaveId = createPendingLeave(employeeId);
    }

    @AfterEach
    void tearDown() {
        leaveApprovalMapper.delete(new LambdaQueryWrapper<LeaveApproval>()
                .eq(LeaveApproval::getLeaveId, leaveId));
        leaveRequestMapper.deleteById(leaveId);
        for (Long userId : List.of(adminId, managerId, managerBId, employeeId)) {
            userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                    .eq(UserRole::getUserId, userId));
            userMapper.deleteById(userId);
        }
        departmentMapper.deleteById(departmentId);
        approvalFlowTestSupport.useTwoLevelFlow();
    }

    // ---------------------------------------------------------------
    // 状态流转
    // ---------------------------------------------------------------

    @Test
    @DisplayName("审批链确实是两级，第 1 级要部门经理、第 2 级要管理员")
    void approvalChainShouldBeConfiguredAsTwoLevels() {
        List<ApprovalFlow> chain = approvalFlowService.getChain(ApprovalFlowService.BIZ_TYPE_LEAVE);

        assertEquals(2, chain.size(), "种子数据里请假配的是两级审批");
        assertEquals(1, chain.get(0).getStep());
        assertEquals("DEPT_MANAGER", chain.get(0).getApproverRoleCode());
        assertEquals(2, chain.get(1).getStep());
        assertEquals("ADMIN", chain.get(1).getApproverRoleCode());
    }

    @Test
    @DisplayName("第 1 级通过后状态仍是 PENDING，级数推进到 2 —— 这是多级审批最关键的一点")
    void firstLevelApprovalShouldKeepPendingAndAdvanceStep() {
        leaveService.approveLeave(managerId, leaveId, approval());

        LeaveRequest after = leaveRequestMapper.selectById(leaveId);
        assertEquals(LeaveStatus.PENDING, after.getStatus(),
                "第 1 级通过后单据还在流程中，状态必须还是 PENDING。"
                        + "如果这里变成了 APPROVED，说明多级审批退化成了单级");
        assertEquals(2, after.getCurrentStep(), "级数应当推进到第 2 级");
    }

    @Test
    @DisplayName("走完两级之后，状态才变成 APPROVED")
    void shouldBeApprovedOnlyAfterLastLevel() {
        leaveService.approveLeave(managerId, leaveId, approval());   // 第 1 级
        leaveService.approveLeave(adminId, leaveId, approval());     // 第 2 级

        LeaveRequest after = leaveRequestMapper.selectById(leaveId);
        assertEquals(LeaveStatus.APPROVED, after.getStatus());
        assertEquals(3, after.getCurrentStep(), "走完两级后级数指向「第 3 级」，表示流程结束");
    }

    @Test
    @DisplayName("任何一级拒绝，直接结束，后面的级别不用走")
    void rejectionShouldEndTheFlowImmediately() {
        leaveService.approveLeave(managerId, leaveId, rejection());

        LeaveRequest after = leaveRequestMapper.selectById(leaveId);
        assertEquals(LeaveStatus.REJECTED, after.getStatus(),
                "第 1 级拒绝就该直接结束，不需要再等第 2 级");
    }

    @Test
    @DisplayName("第 2 级拒绝同样直接结束")
    void rejectionAtSecondLevelShouldAlsoEnd() {
        leaveService.approveLeave(managerId, leaveId, approval());
        leaveService.approveLeave(adminId, leaveId, rejection());

        assertEquals(LeaveStatus.REJECTED, leaveRequestMapper.selectById(leaveId).getStatus());
    }

    // ---------------------------------------------------------------
    // 每一级该谁审
    // ---------------------------------------------------------------

    @Test
    @DisplayName("部门经理审不了第 2 级 —— 第 2 级要求 ADMIN 角色")
    void managerShouldNotApproveSecondLevel() {
        leaveService.approveLeave(managerId, leaveId, approval());   // 第 1 级通过，现在停在第 2 级

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> leaveService.approveLeave(managerId, leaveId, approval()));

        assertTrue(ex.getMessage().contains("第 2 级"),
                "应当明确告知是第几级、需要什么角色。实际消息：" + ex.getMessage());

        // 确认状态没被改动
        LeaveRequest after = leaveRequestMapper.selectById(leaveId);
        assertEquals(2, after.getCurrentStep(), "被拒绝的审批不应该推进级数");
        assertEquals(LeaveStatus.PENDING, after.getStatus());
    }

    // ---------------------------------------------------------------
    // 审批流水
    // ---------------------------------------------------------------

    @Test
    @DisplayName("审批流水完整记录每一级 —— 主表只保留最后一次，流水不会")
    void approvalHistoryShouldRecordEveryLevel() {
        leaveService.approveLeave(managerId, leaveId, approval());
        leaveService.approveLeave(adminId, leaveId, approval());

        List<LeaveApproval> history = leaveApprovalMapper.selectByLeaveId(leaveId);
        assertEquals(2, history.size(), "两级审批应当留下两条流水");

        assertEquals(1, history.get(0).getStep());
        assertEquals(managerId, history.get(0).getApproverId(), "第 1 级的审批人是部门经理");
        assertEquals(2, history.get(1).getStep());
        assertEquals(adminId, history.get(1).getApproverId(), "第 2 级的审批人是管理员");

        // ★ 这一条最能说明「为什么必须有流水表」
        assertEquals(adminId, leaveRequestMapper.selectById(leaveId).getApproverId(),
                "主表的 approver_id 已经被第 2 级覆盖了 —— "
                        + "第 1 级是谁批的，只能从流水表里查");
    }

    @Test
    @DisplayName("被拒绝的那一级也会留下流水，并记录拒绝意见")
    void rejectionShouldAlsoLeaveHistory() {
        leaveService.approveLeave(managerId, leaveId, rejection());

        List<LeaveApproval> history = leaveApprovalMapper.selectByLeaveId(leaveId);
        assertEquals(1, history.size());
        assertEquals("REJECTED", history.get(0).getDecision());
        assertEquals("不同意", history.get(0).getComment());
    }

    // ---------------------------------------------------------------
    // 并发：同一级只能被审一次
    // ---------------------------------------------------------------

    @Test
    @DisplayName("两个部门经理同时审同一级，只有一个成功 —— 条件更新必须带上 current_step")
    void onlyOneShouldWinWhenApprovingSameLevelConcurrently() throws InterruptedException {

        int threadCount = 10;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        // 被【业务异常】挡下 —— 也就是「请假状态发生改变，请稍后重试」
        AtomicInteger businessConflict = new AtomicInteger();
        // 被【数据库唯一键】挡下 —— 也就是 DuplicateKeyException
        AtomicInteger databaseConflict = new AtomicInteger();

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Long approver = (i % 2 == 0) ? managerId : managerBId;
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    leaveService.approveLeave(approver, leaveId, approval());
                    success.incrementAndGet();
                } catch (InvalidLeaveStatusException e) {
                    businessConflict.incrementAndGet();
                } catch (Exception e) {
                    databaseConflict.incrementAndGet();
                }
            });
            workers.add(worker);
            worker.start();
        }

        ready.await();
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(1, success.get(), "同一级只应该有一个人审批成功");
        assertEquals(2, leaveRequestMapper.selectById(leaveId).getCurrentStep(),
                "级数只应该推进一次");
        assertEquals(1,
                leaveApprovalMapper.selectByLeaveId(leaveId).size(),
                "流水表只应该有一条记录");

        // ★ 这条断言是重点：重复审批必须被【业务异常】挡下，
        //   而不是撞到流水表的唯一索引、抛 DuplicateKeyException 出来。
        //   两层保护都在，但正常情况下应该由第一层（条件更新带 current_step）先拦住 ——
        //   用户不该看到「Duplicate entry '1-1' for key 'uk_approval_leave_step'」这种数据库错误。
        assertEquals(threadCount - 1, businessConflict.get(),
                "其余的应当被业务异常「请假状态发生改变」挡下。"
                        + "如果这里是 0，说明条件更新没带 current_step，"
                        + "保护退化成了数据库唯一键报错");
        assertEquals(0, databaseConflict.get(),
                "不应该有线程撞到数据库唯一键 —— 那意味着用户会看到数据库层的报错");
    }

    // ---------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------

    private LeaveApprovalDTO approval() {
        LeaveApprovalDTO dto = new LeaveApprovalDTO();
        dto.setDecision("APPROVED");
        dto.setApprovalComment("同意");
        return dto;
    }

    private LeaveApprovalDTO rejection() {
        LeaveApprovalDTO dto = new LeaveApprovalDTO();
        dto.setDecision("REJECTED");
        dto.setApprovalComment("不同意");
        return dto;
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

    private Long createUser(String username, String roleCode) {
        User u = new User();
        u.setUsername(username);
        u.setName(username);
        u.setPassword(passwordEncoder.encode("test123456"));
        u.setDepartmentId(departmentId);
        u.setStatus(1);
        u.setRole(roleCode);
        u.setTokenVersion(0);
        userMapper.insert(u);
        roleAssigner.assign(u.getId(), roleCode);
        return u.getId();
    }

    private Long createPendingLeave(Long applicantId) {
        LeaveRequest leave = new LeaveRequest();
        leave.setApplicantId(applicantId);
        leave.setDepartmentId(departmentId);
        leave.setLeaveType("ANNUAL");
        leave.setStartTime(LocalDateTime.now().plusDays(1));
        leave.setEndTime(LocalDateTime.now().plusDays(2));
        leave.setReason("多级审批测试");
        leave.setStatus(LeaveStatus.PENDING);
        leave.setCurrentStep(1);
        leaveRequestMapper.insert(leave);
        return leave.getId();
    }
}
