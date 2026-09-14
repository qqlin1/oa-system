package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.common.PageResult;
import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.entity.UserRole;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.mapper.UserRoleMapper;
import com.qqlin.oa.support.TestRoleAssigner;
import com.qqlin.oa.vo.LeaveVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 RBAC 的两层权限。
 *
 * 【功能权限】你有没有「审批请假」这个操作。存 sys_role_permission。
 * 【数据权限】你能审批「谁」的请假。存 sys_role.data_scope。
 *
 * 组织结构和人员安排：
 *
 *   部门A ──── 子部门A1
 *    ├ 经理A   （DEPT_MANAGER，data_scope=2 本部门及以下）
 *    ├ 员工A   （USER）
 *    └ 员工A1  （USER，在子部门A1）
 *
 *   部门B
 *    └ 员工B   （USER）
 *
 *   管理员    （ADMIN，data_scope=1 全部）
 *
 * 关键用例是「经理A 能审本部门和子部门、但不能审部门B」——
 * 这一个用例同时证明了功能权限和数据权限都生效。
 */
@SpringBootTest
class RbacPermissionTest {

    @Autowired private LeaveService leaveService;
    @Autowired private PermissionService permissionService;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private LeaveRequestMapper leaveRequestMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TestRoleAssigner roleAssigner;

    private Long deptA;
    private Long deptA1;
    private Long deptB;

    private Long adminId;
    private Long managerAId;
    private Long employeeAId;
    private Long employeeA1Id;
    private Long employeeBId;

    @BeforeEach
    void setUp() {
        String tag = "_" + System.nanoTime();
        deptA = createDepartment("RBAC部门A" + tag, 0L);
        deptA1 = createDepartment("RBAC子部门A1" + tag, deptA);
        deptB = createDepartment("RBAC部门B" + tag, 0L);

        adminId = createUser("rbac_admin" + tag, deptA, "ADMIN");
        managerAId = createUser("rbac_mgr" + tag, deptA, "DEPT_MANAGER");
        employeeAId = createUser("rbac_empA" + tag, deptA, "USER");
        employeeA1Id = createUser("rbac_empA1" + tag, deptA1, "USER");
        employeeBId = createUser("rbac_empB" + tag, deptB, "USER");
    }

    @AfterEach
    void tearDown() {
        for (Long userId : List.of(adminId, managerAId, employeeAId, employeeA1Id, employeeBId)) {
            leaveRequestMapper.delete(new LambdaQueryWrapper<LeaveRequest>()
                    .eq(LeaveRequest::getApplicantId, userId));
            userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                    .eq(UserRole::getUserId, userId));
            userMapper.deleteById(userId);
        }
        departmentMapper.deleteById(deptA1);
        departmentMapper.deleteById(deptA);
        departmentMapper.deleteById(deptB);
    }

    // ---------------------------------------------------------------
    // 功能权限：有没有「审批请假」这个操作
    // ---------------------------------------------------------------

    @Test
    @DisplayName("普通员工没有 leave:approve 权限，调用审批接口应当被拒绝")
    void employeeShouldNotBeAllowedToApprove() {
        Long leaveId = createPendingLeave(employeeAId, deptA);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> leaveService.approveLeave(employeeAId, leaveId, approval()));

        // 断言必须是「被哪一层拒绝」，不能只断言「抛了 ForbiddenException」。
        // 因为员工A 的角色是 USER（数据权限=仅本人），数据权限那一层也会把他拦下来。
        // 如果只断言异常类型，那么把 @RequiresPermission 删掉，测试照样是绿的 ——
        // 测试通过的原因是错的，这种「假绿」比测试失败更危险。
        assertTrue(ex.getMessage().contains("没有操作权限"),
                "应当被【功能权限】拦下（没有 leave:approve）。实际消息：" + ex.getMessage());

        // 确认请假单没被改动 —— 拒绝必须是「什么都没发生」，不能只抛异常但数据已经改了
        assertEquals(LeaveStatus.PENDING, leaveRequestMapper.selectById(leaveId).getStatus(),
                "被拒绝的审批不应该修改任何数据");
    }

    @Test
    @DisplayName("功能权限来自数据库配置：员工没有、经理和管理员有")
    void permissionSetShouldComeFromRoleConfiguration() {
        assertFalse(permissionService.hasPermission(employeeAId, "leave:approve"),
                "USER 角色不该有审批权限");
        assertTrue(permissionService.hasPermission(managerAId, "leave:approve"),
                "DEPT_MANAGER 角色应当有审批权限");
        assertTrue(permissionService.hasPermission(adminId, "leave:approve"),
                "ADMIN 角色应当有审批权限");

        // 这条最能说明「权限是数据」：员工和管理员的差别，只是 sys_role_permission 里有没有那一行
        assertTrue(permissionService.getPermissions(adminId).size()
                        > permissionService.getPermissions(employeeAId).size(),
                "管理员拥有的权限点数量应当多于普通员工");
    }

    @Test
    @DisplayName("部门经理有 leave:approve 权限，可以审批本部门的请假")
    void managerShouldApproveOwnDepartment() {
        Long leaveId = createPendingLeave(employeeAId, deptA);

        leaveService.approveLeave(managerAId, leaveId, approval());

        assertEquals(LeaveStatus.APPROVED, leaveRequestMapper.selectById(leaveId).getStatus());
    }

    // ---------------------------------------------------------------
    // 数据权限：能审批「谁」的请假
    // ---------------------------------------------------------------

    @Test
    @DisplayName("部门经理可以审批下属部门的请假（data_scope = 本部门及以下）")
    void managerShouldApproveSubDepartment() {
        Long leaveId = createPendingLeave(employeeA1Id, deptA1);

        leaveService.approveLeave(managerAId, leaveId, approval());

        assertEquals(LeaveStatus.APPROVED, leaveRequestMapper.selectById(leaveId).getStatus(),
                "子部门 A1 在 A 的管辖范围内，应当可以审批");
    }

    @Test
    @DisplayName("部门经理不能审批其他部门的请假（这是数据权限的核心）")
    void managerShouldNotApproveOtherDepartment() {
        Long leaveId = createPendingLeave(employeeBId, deptB);

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> leaveService.approveLeave(managerAId, leaveId, approval()),
                "经理A 只管部门A，部门B 的单子不在管辖范围内");

        // 同上：必须断言是【数据权限】拦下的。
        // 经理A 是有 leave:approve 功能权限的，他之所以被拒，唯一的原因就是数据权限。
        assertTrue(ex.getMessage().contains("无权审批该部门"),
                "应当被【数据权限】拦下。实际消息：" + ex.getMessage());

        assertEquals(LeaveStatus.PENDING, leaveRequestMapper.selectById(leaveId).getStatus());
    }

    @Test
    @DisplayName("管理员拥有全部数据权限，可以审批任意部门的请假")
    void adminShouldApproveAnyDepartment() {
        Long leaveId = createPendingLeave(employeeBId, deptB);

        leaveService.approveLeave(adminId, leaveId, approval());

        assertEquals(LeaveStatus.APPROVED, leaveRequestMapper.selectById(leaveId).getStatus());
    }

    @Test
    @DisplayName("待审批列表只返回管辖范围内的单据（数据权限要作用在查询上）")
    void pendingListShouldBeFilteredByDataScope() {
        Long leaveInA = createPendingLeave(employeeAId, deptA);
        Long leaveInA1 = createPendingLeave(employeeA1Id, deptA1);
        Long leaveInB = createPendingLeave(employeeBId, deptB);

        PageResult<LeaveVO> page = leaveService.getPendingLeaveList(managerAId, 1L, 100L);
        Set<Long> visibleLeaveIds = page.getRecords().stream()
                .map(LeaveVO::getId)
                .collect(Collectors.toSet());

        assertTrue(visibleLeaveIds.contains(leaveInA), "本部门的单子应当可见");
        assertTrue(visibleLeaveIds.contains(leaveInA1), "下属部门的单子应当可见");
        assertFalse(visibleLeaveIds.contains(leaveInB),
                "其他部门的单子不应当出现在待办列表里 —— 否则信息已经泄露了");
    }

    // ---------------------------------------------------------------
    // 权限是数据驱动的：改数据就能改权限，不用改代码
    // ---------------------------------------------------------------

    @Test
    @DisplayName("给普通员工加上部门经理角色后，他立刻就能审批了（权限是数据，不是代码）")
    void grantingRoleTakesEffectImmediately() {
        // 用子部门 A1 员工的单子，不能用员工A 自己的 ——
        // 那样会被「不能审批自己提交的请假」这条规则挡掉，测不出权限的变化
        Long leaveId = createPendingLeave(employeeA1Id, deptA1);

        // 加角色之前：拒绝（没有 leave:approve 权限）
        assertThrows(ForbiddenException.class,
                () -> leaveService.approveLeave(employeeAId, leaveId, approval()));

        // 给这个员工也分配部门经理角色 —— 只改数据，一行代码都没动
        roleAssigner.assign(employeeAId, "DEPT_MANAGER");

        // 加角色之后：立刻生效（assignRole 会清权限缓存）
        leaveService.approveLeave(employeeAId, leaveId, approval());

        assertEquals(LeaveStatus.APPROVED, leaveRequestMapper.selectById(leaveId).getStatus(),
                "权限来源于数据库里的角色配置，改配置应当立即生效");
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

    private Long createDepartment(String name, Long parentId) {
        Department d = new Department();
        d.setName(name);
        d.setParentId(parentId);
        d.setStatus(1);
        d.setSort(0);
        departmentMapper.insert(d);
        return d.getId();
    }

    private Long createUser(String username, Long departmentId, String roleCode) {
        User u = new User();
        u.setUsername(username);
        u.setName(username);
        u.setPassword(passwordEncoder.encode("test123456"));
        u.setDepartmentId(departmentId);
        u.setStatus(1);
        u.setRole(roleCode);
        u.setTokenVersion(0);
        userMapper.insert(u);

        // 授权走 sys_user_role，不是 sys_user.role 那个字段
        roleAssigner.assign(u.getId(), roleCode);
        return u.getId();
    }

    private Long createPendingLeave(Long applicantId, Long departmentId) {
        LeaveRequest leave = new LeaveRequest();
        leave.setApplicantId(applicantId);
        leave.setDepartmentId(departmentId);
        leave.setLeaveType("ANNUAL");
        leave.setStartTime(LocalDateTime.now().plusDays(1));
        leave.setEndTime(LocalDateTime.now().plusDays(2));
        leave.setReason("RBAC 权限测试");
        leave.setStatus(LeaveStatus.PENDING);
        leaveRequestMapper.insert(leave);
        return leave.getId();
    }
}
