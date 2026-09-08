package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.LeaveCreateDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.exception.InvalidLeaveRequestException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.mapper.UserMapper;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 请假重复提交（幂等性）测试。
 *
 * 场景：员工填完请假单点提交，网络卡了一下没反应，他又点了两下。
 * 三次请求的参数完全一样，但数据库里会多出三条一模一样的请假单 ——
 * 因为「新增」这个操作天生就不是幂等的。
 *
 * 这个测试不需要并发，就是老老实实串行调 3 次。
 * 在修复之前，它应当是「红」的：数出来是 3 条而不是 1 条。
 */
@SpringBootTest
class LeaveIdempotentTest {

    @Autowired private LeaveService leaveService;
    @Autowired private LeaveRequestMapper leaveRequestMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private Long userId;
    private Long departmentId;
    private Long noDeptUserId;

    @BeforeEach
    void setUp() {
        // createLeave 要求用户必须有部门，所以这里得先建一个部门
        departmentId = createDepartment("幂等测试部门_" + System.nanoTime());
        userId = createUser("test_idem_" + System.nanoTime(), departmentId);
    }

    @AfterEach
    void tearDown() {
        // 先删请假单（它引用了用户），再删用户，最后删部门
        leaveRequestMapper.delete(new LambdaQueryWrapper<LeaveRequest>()
                .eq(LeaveRequest::getApplicantId, userId));
        userMapper.deleteById(userId);
        departmentMapper.deleteById(departmentId);
        if (noDeptUserId != null) {
            userMapper.deleteById(noDeptUserId);
        }
    }

    @Test
    @DisplayName("同一份请假单重复提交 3 次，数据库里只应当有 1 条")
    void shouldCreateOnlyOneLeaveWhenSubmitThreeTimes() {

        // 模拟前端取号：三次重试都用同一个号
        String requestId = UUID.randomUUID().toString();

        // 三次提交用的是完全相同的一份参数
        LeaveCreateDTO dto = new LeaveCreateDTO();
        dto.setRequestId(requestId);
        dto.setLeaveType("ANNUAL");
        dto.setStartTime(LocalDateTime.now().plusDays(1));
        dto.setEndTime(LocalDateTime.now().plusDays(2));
        dto.setReason("幂等测试：重复提交");

        // 模拟手抖：连着提交 3 次
        List<Long> leaveIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            leaveIds.add(leaveService.createLeave(userId, dto));
        }

        long countInDb = leaveRequestMapper.selectCount(
                new LambdaQueryWrapper<LeaveRequest>()
                        .eq(LeaveRequest::getApplicantId, userId));

        System.out.println();
        System.out.println("===== 请假重复提交测试结果 =====");
        System.out.println("提交次数：3");
        System.out.println("服务端返回的单号：" + leaveIds);
        System.out.println("数据库里的请假单数量：" + countInDb);
        System.out.println("================================");
        System.out.println();

        assertEquals(1, countInDb,
                "同一份请假单提交 3 次，数据库里应当只有 1 条");
    }

    private Long createUser(String username, Long departmentId) {
        User user = new User();
        user.setUsername(username);
        user.setName(username);
        user.setPassword(passwordEncoder.encode("test123456"));
        user.setDepartmentId(departmentId);
        user.setStatus(1);
        user.setRole("USER");
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * 验证一个坑：如果「插幂等记录」成功、但后续「创建请假单」失败，
     * 那条幂等记录会残留下来，把这个 requestId 永久占用 ——
     * 用户换个条件重新提交，也会被当成「重复请求」，返回 null，永远提交不了。
     *
     * 正确行为：创建失败时，幂等记录应当跟着一起撤销（事务回滚）。
     */
    @Test
    @DisplayName("请假单创建失败时，幂等记录不能残留占用这个 requestId")
    void shouldNotOccupyRequestIdWhenCreateFailed() {

        // 造一个还没分配部门的用户，createLeave 会直接拒绝他
        noDeptUserId = createUserWithoutDepartment();

        String requestId = UUID.randomUUID().toString();

        LeaveCreateDTO dto = new LeaveCreateDTO();
        dto.setRequestId(requestId);
        dto.setLeaveType("ANNUAL");
        dto.setStartTime(LocalDateTime.now().plusDays(1));
        dto.setEndTime(LocalDateTime.now().plusDays(2));
        dto.setReason("幂等测试：这次会失败");

        // 第一次：因为没有部门，业务校验应当拒绝
        assertThrows(InvalidLeaveRequestException.class, () -> {
            leaveService.createLeave(noDeptUserId, dto);
        });

        // 第二次：换成有部门的用户、用同一个 requestId 重新提交
        // 修复前：幂等记录残留 → 撞唯一键 → 返回 null（永远提交不了）
        // 修复后：事务回滚，没有残留 → 正常创建成功
        Long leaveId = leaveService.createLeave(userId, dto);

        assertNotNull(leaveId,
                "创建失败后这个 requestId 不应当被永久占用，换个用户重新提交应当能成功");
    }

    private Long createUserWithoutDepartment() {
        User user = new User();
        user.setUsername("test_nodept_" + System.nanoTime());
        user.setName("无部门用户");
        user.setPassword(passwordEncoder.encode("test123456"));
        user.setDepartmentId(0L);      // 0 表示没分配部门
        user.setStatus(1);
        user.setRole("USER");
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user.getId();
    }

    private Long createDepartment(String name) {
        Department department = new Department();
        department.setName(name);
        department.setParentId(0L);
        department.setStatus(1);
        department.setSort(0);
        departmentMapper.insert(department);
        return department.getId();
    }
}
