package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.entity.Role;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.entity.UserRole;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.UserNotFoundException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.mapper.RoleMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.mapper.UserRoleMapper;
import com.qqlin.oa.support.TestRoleAssigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 RBAC 的「管理面」：给用户分配 / 移除角色。
 *
 * 这个测试证明的核心命题是：
 *   【权限是数据，不是代码】—— 改一行 sys_user_role 数据，权限立刻变化，代码一行没动。
 *
 * 人员安排：
 *   admin      —— ADMIN 角色
 *   employeeA  —— 一开始只有 USER 角色（不能审批）
 *   employeeB  —— USER 角色，提交了一张请假单给 A 审
 */
@SpringBootTest
class RoleManageTest {

    @Autowired private RoleService roleService;
    @Autowired private LeaveService leaveService;
    @Autowired private PermissionService permissionService;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private LeaveRequestMapper leaveRequestMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TestRoleAssigner roleAssigner;

    private Long departmentId;
    private Long adminId;
    private Long employeeAId;
    private Long employeeBId;
    private Long leaveId;

    @BeforeEach
    void setUp() {
        String tag = "_" + System.nanoTime();
        departmentId = createDepartment("角色管理测试部门" + tag);

        adminId = createUser("role_admin" + tag, "ADMIN");
        employeeAId = createUser("role_empA" + tag, "USER");
        employeeBId = createUser("role_empB" + tag, "USER");

        // 员工B 提交一张请假单，给后面测试「A 能不能审批」用
        leaveId = createPendingLeave(employeeBId);
    }

    @AfterEach
    void tearDown() {
        leaveRequestMapper.deleteById(leaveId);
        for (Long userId : List.of(adminId, employeeAId, employeeBId)) {
            userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                    .eq(UserRole::getUserId, userId));
            userMapper.deleteById(userId);
        }
        departmentMapper.deleteById(departmentId);
    }

    // ---------------------------------------------------------------
    // 核心命题：权限是数据，改数据就改权限
    // ---------------------------------------------------------------

    @Test
    @DisplayName("给普通员工分配部门经理角色后，他立刻就能审批了 —— 一行代码都没改")
    void assignRoleShouldGrantPermissionImmediately() {
        // 分配之前：A 是 USER，没有 leave:approve
        assertFalse(permissionService.hasPermission(employeeAId, "leave:approve"));
        assertThrows(ForbiddenException.class,
                () -> leaveService.approveLeave(employeeAId, leaveId, approval()));

        // 只改了一行数据
        roleService.assignRole(adminId, employeeAId, "DEPT_MANAGER");

        // 分配之后：立刻生效
        assertTrue(permissionService.hasPermission(employeeAId, "leave:approve"),
                "分配角色后权限缓存应当被清掉，新权限立刻生效");
        leaveService.approveLeave(employeeAId, leaveId, approval());
        assertEquals(LeaveStatus.APPROVED, leaveRequestMapper.selectById(leaveId).getStatus());
    }

    @Test
    @DisplayName("移除角色后，权限立刻消失")
    void revokeRoleShouldRemovePermissionImmediately() {
        roleService.assignRole(adminId, employeeAId, "DEPT_MANAGER");
        assertTrue(permissionService.hasPermission(employeeAId, "leave:approve"));

        roleService.revokeRole(adminId, employeeAId, "DEPT_MANAGER");

        assertFalse(permissionService.hasPermission(employeeAId, "leave:approve"),
                "移除角色后应当立刻失去权限");
        assertThrows(ForbiddenException.class,
                () -> leaveService.approveLeave(employeeAId, leaveId, approval()));
    }

    @Test
    @DisplayName("重复分配同一个角色是幂等的，不会报错也不会产生重复记录")
    void assignSameRoleTwiceShouldBeIdempotent() {
        roleService.assignRole(adminId, employeeAId, "DEPT_MANAGER");
        roleService.assignRole(adminId, employeeAId, "DEPT_MANAGER");

        Role managerRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, "DEPT_MANAGER"));
        Long count = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, employeeAId)
                        .eq(UserRole::getRoleId, managerRole.getId()));

        assertEquals(1L, count, "sys_user_role 上 (user_id, role_id) 有唯一索引，重复分配只应有一条");
    }

    @Test
    @DisplayName("一个人可以有多个角色，权限取并集")
    void userCanHoldMultipleRoles() {
        roleService.assignRole(adminId, employeeAId, "DEPT_MANAGER");

        List<Role> roles = roleService.listUserRoles(adminId, employeeAId);
        assertEquals(2, roles.size(), "USER + DEPT_MANAGER 应当有两个角色");
        assertTrue(roles.stream().anyMatch(r -> "USER".equals(r.getCode())));
        assertTrue(roles.stream().anyMatch(r -> "DEPT_MANAGER".equals(r.getCode())));

        // 数据权限取最宽的那个（数字最小）：DEPT_MANAGER 是 2，比 USER 的 4 宽
        assertEquals(Role.SCOPE_DEPT_AND_BELOW, permissionService.getDataScope(employeeAId));
    }

    // ---------------------------------------------------------------
    // 权限保护：谁能管理角色
    // ---------------------------------------------------------------

    @Test
    @DisplayName("普通员工没有 role:manage 权限，不能给别人分配角色")
    void employeeShouldNotBeAbleToAssignRole() {
        assertThrows(ForbiddenException.class,
                () -> roleService.assignRole(employeeAId, employeeBId, "ADMIN"),
                "普通员工如果能给自己或别人加管理员角色，就是提权漏洞");

        assertFalse(permissionService.hasPermission(employeeBId, "role:manage"));
    }

    @Test
    @DisplayName("不能移除自己的管理员角色，否则系统可能失去所有管理员")
    void shouldNotRevokeOwnAdminRole() {
        assertThrows(ForbiddenException.class,
                () -> roleService.revokeRole(adminId, adminId, "ADMIN"));

        assertTrue(permissionService.hasPermission(adminId, "role:manage"),
                "被拒绝后，管理员权限应当原样保留");
    }

    @Test
    @DisplayName("管理员可以移除别人的管理员角色（只要不是自己）")
    void adminCanRevokeOthersAdminRole() {
        roleService.assignRole(adminId, employeeBId, "ADMIN");
        assertTrue(permissionService.hasPermission(employeeBId, "role:manage"));

        roleService.revokeRole(adminId, employeeBId, "ADMIN");

        assertFalse(permissionService.hasPermission(employeeBId, "role:manage"),
                "移除后应当立刻失去管理员权限");
    }

    // ---------------------------------------------------------------
    // 参数校验
    // ---------------------------------------------------------------

    @Test
    @DisplayName("分配不存在的角色应当报错")
    void assigningUnknownRoleShouldFail() {
        assertThrows(IllegalArgumentException.class,
                () -> roleService.assignRole(adminId, employeeAId, "NOT_EXIST_ROLE"));
    }

    @Test
    @DisplayName("给不存在的用户分配角色应当报错")
    void assigningRoleToUnknownUserShouldFail() {
        assertThrows(UserNotFoundException.class,
                () -> roleService.assignRole(adminId, 999999999L, "USER"));
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
        leave.setReason("角色管理测试");
        leave.setStatus(LeaveStatus.PENDING);
        leaveRequestMapper.insert(leave);
        return leave.getId();
    }
}
