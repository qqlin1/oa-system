package com.qqlin.oa.service;

import com.qqlin.oa.dto.DepartmentParentUpdateDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.exception.InvalidDepartmentHierarchyException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.vo.DepartmentTreeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门迁移的并发安全测试。
 *
 * 场景：两个平级部门，同一时刻管理员 A 把甲移到乙下面、管理员 B 把乙移到甲下面。
 * 这两个操作单独看都合法，但同时发生就会形成环：
 * 甲的父是乙、乙的父是甲，两者谁都进不了部门树的根节点列表，
 * 结果就是这两个部门（以及它们下面的所有子部门）从部门树里凭空消失。
 *
 * 注意：跑这个测试之前，updateParent 的修复还没做，
 * 所以按理说它应该是「红」的 —— 那正是 bug 存在的证据。
 */
@SpringBootTest
class DepartmentMoveConcurrencyTest {

    @Autowired private DepartmentService departmentService;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private Long adminId;
    private Long deptAId;
    private Long deptBId;

    /** 自己造数据：一个管理员 + 两个平级部门，不依赖库里已有的任何一行 */
    @BeforeEach
    void setUp() {
        adminId = createAdmin();
        deptAId = createDepartment("迁移测试A_" + System.nanoTime());
        deptBId = createDepartment("迁移测试B_" + System.nanoTime());
    }

    /** 手动清理。并发测试里 @Transactional 回滚是失效的（子线程不在主线程事务里） */
    @AfterEach
    void tearDown() {
        departmentMapper.deleteById(deptAId);
        departmentMapper.deleteById(deptBId);
        userMapper.deleteById(adminId);
    }

    @Test
    @DisplayName("两个平级部门互相移到对方下面，不能形成环")
    void shouldNotFormCycleWhenTwoDepartmentsMoveIntoEachOther() throws InterruptedException {

        ExecutorService pool = Executors.newFixedThreadPool(2);

        // 三个发令器：确认就位 / 一起开跑 / 确认跑完
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(2);

        AtomicInteger successCount    = new AtomicInteger(0);
        AtomicInteger conflictCount   = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        // 甲：把 A 移到 B 下面
        pool.submit(() -> move(adminId, deptAId, deptBId,
                readyLatch, startLatch, doneLatch, successCount, conflictCount, otherErrorCount));

        // 乙：把 B 移到 A 下面（完全相反的方向）
        pool.submit(() -> move(adminId, deptBId, deptAId,
                readyLatch, startLatch, doneLatch, successCount, conflictCount, otherErrorCount));

        readyLatch.await();          // 等两个线程都就位
        startLatch.countDown();      // 开枪，真正同时发起
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        Department afterA = departmentMapper.selectById(deptAId);
        Department afterB = departmentMapper.selectById(deptBId);

        System.out.println();
        System.out.println("===== 部门迁移并发测试结果 =====");
        System.out.println("迁移成功：" + successCount.get()
                + "，被拒绝：" + conflictCount.get()
                + "，其他异常：" + otherErrorCount.get());
        System.out.println("部门 A 的 parent_id = " + afterA.getParentId() + "（B 的 id 是 " + deptBId + "）");
        System.out.println("部门 B 的 parent_id = " + afterB.getParentId() + "（A 的 id 是 " + deptAId + "）");
        System.out.println("================================");
        System.out.println();

        // —— 断言 1：结果数量，应当恰好一个成功、一个被拦住 ——
        assertTrue(allDone, "30 秒内应当都跑完");
        assertEquals(0, otherErrorCount.get(), "不应当有意料之外的异常");
        assertEquals(1, successCount.get(), "两个方向互移，应当恰好一个成功");
        assertEquals(1, conflictCount.get(), "另一个应当被环检查拦住");

        // —— 断言 2：数据库状态，两个部门不能互为父子 ——
        boolean isCycle = Objects.equals(deptBId, afterA.getParentId())
                       && Objects.equals(deptAId, afterB.getParentId());
        assertFalse(isCycle, "两个部门不能互为父子（成环会让它们从部门树里消失）");

        // —— 断言 3：业务后果，用户必须还能在部门树里看到它们 ——
        List<DepartmentTreeVO> tree = departmentService.getDepartmentTree(adminId);
        assertTrue(containsId(tree, deptAId), "部门 A 必须在部门树里，不能消失");
        assertTrue(containsId(tree, deptBId), "部门 B 必须在部门树里，不能消失");
    }

    private void move(Long adminId, Long departmentId, Long newParentId,
                      CountDownLatch ready, CountDownLatch start, CountDownLatch done,
                      AtomicInteger success, AtomicInteger conflict, AtomicInteger other) {
        try {
            ready.countDown();
            start.await();

            DepartmentParentUpdateDTO dto = new DepartmentParentUpdateDTO();
            dto.setParentId(newParentId);
            departmentService.updateParent(adminId, departmentId, dto);

            success.incrementAndGet();

        } catch (InvalidDepartmentHierarchyException e) {
            conflict.incrementAndGet();   // 期待的结果：被环检查拦住

        } catch (Exception e) {
            other.incrementAndGet();
            e.printStackTrace();

        } finally {
            done.countDown();
        }
    }

    /** 遍历部门树找某个 id。带 visited 是为了防止成环数据把递归跑死 */
    private boolean containsId(List<DepartmentTreeVO> nodes, Long id) {
        return containsId(nodes, id, new HashSet<>());
    }

    private boolean containsId(List<DepartmentTreeVO> nodes, Long id, Set<Long> visited) {
        for (DepartmentTreeVO node : nodes) {
            if (!visited.add(node.getId())) {
                continue;
            }
            if (id.equals(node.getId())) {
                return true;
            }
            if (containsId(node.getChildren(), id, visited)) {
                return true;
            }
        }
        return false;
    }

    private Long createAdmin() {
        User user = new User();
        user.setUsername("test_move_admin_" + System.nanoTime());
        user.setName("迁移测试管理员");
        user.setPassword(passwordEncoder.encode("test123456"));
        user.setDepartmentId(0L);
        user.setStatus(1);
        user.setRole("ADMIN");
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
