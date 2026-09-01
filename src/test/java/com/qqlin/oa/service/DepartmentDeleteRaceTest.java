package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.DepartmentCreateDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.exception.DepartmentInUseException;
import com.qqlin.oa.exception.DepartmentNotFoundException;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复现「删除部门」的 check-then-act 竞态。
 *
 * 背景：deleteDepartment 现在是三步分开做的——
 *   1. 查这个部门有没有子部门
 *   2. 查这个部门有没有员工
 *   3. 都没问题，才删除
 * 第 1 步和第 3 步之间有时间窗。如果另一个管理员刚好在这个窗口里
 * 往该部门下面建了一个子部门，那么这个新建的子部门就会变成孤儿节点。
 */
@SpringBootTest
class DepartmentDeleteRaceTest {

    @Autowired
    private DepartmentService departmentService;
    @Autowired
    private DepartmentMapper departmentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long adminId;
    private Long adminDeptId;   // 管理员自己所在的部门，不参与删除，避免干扰

    @BeforeEach
    void setUp() {
        adminDeptId = createDepartment("race_admindept_" + System.nanoTime());
        adminId = createAdmin(adminDeptId);
    }

    @AfterEach
    void tearDown() {
        // 按名字前缀清掉本测试造的所有数据，不依赖数据库里已有的任何一行
        departmentMapper.delete(new LambdaQueryWrapper<Department>()
                .like(Department::getName, "race_"));
        userMapper.delete(new LambdaQueryWrapper<User>()
                .like(User::getUsername, "test_race_"));
    }

    @Test
    @DisplayName("删除部门的同时在它下面建子部门，会产生孤儿节点（竞态存在）")
    void shouldProduceOrphanWhenDeleteAndCreateRace() throws InterruptedException {

        int rounds = 20;          // 并发 bug 时灵时不灵，多跑几轮统计复现率
        int orphanRounds = 0;     // 有多少轮跑出了孤儿节点
        int totalOrphans = 0;     // 一共跑出多少个孤儿节点

        for (int round = 1; round <= rounds; round++) {

            Long targetId = createDepartment("race_target_" + round + "_" + System.nanoTime());

            int deleteThreads = 10;
            int createThreads = 10;
            int total = deleteThreads + createThreads;

            ExecutorService pool = Executors.newFixedThreadPool(total);
            CountDownLatch ready = new CountDownLatch(total);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(total);

            AtomicInteger deleteSuccess = new AtomicInteger(0);   // 成功删掉了部门
            AtomicInteger deleteBlocked = new AtomicInteger(0);   // 被「有子部门/有员工」挡回
            AtomicInteger createSuccess = new AtomicInteger(0);   // 成功建了子部门
            AtomicInteger createRejected = new AtomicInteger(0);  // 父部门已被删，建不了
            AtomicInteger otherError = new AtomicInteger(0);

            // 前 10 个线程：管理员点「删除部门」
            for (int i = 0; i < deleteThreads; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();

                        departmentService.deleteDepartment(adminId, targetId);
                        deleteSuccess.incrementAndGet();

                    } catch (DepartmentInUseException | DepartmentNotFoundException e) {
                        // 竞争中被正确挡回，属于正常结果
                        deleteBlocked.incrementAndGet();
                    } catch (Exception e) {
                        otherError.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            // 后 10 个线程：另一个管理员往这个部门下面建子部门
            for (int i = 0; i < createThreads; i++) {
                final int index = i;
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();

                        DepartmentCreateDTO dto = new DepartmentCreateDTO();
                        dto.setName("race_child_" + index + "_" + System.nanoTime());
                        dto.setParentId(targetId);
                        dto.setSort(0);

                        departmentService.createDepartment(adminId, dto);
                        createSuccess.incrementAndGet();

                    } catch (DepartmentNotFoundException e) {
                        // 父部门已经被人删了，建不了，属于正常结果
                        createRejected.incrementAndGet();
                    } catch (Exception e) {
                        otherError.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();       // 等 20 个线程全部就位
            start.countDown();   // 开枪
            done.await(30, TimeUnit.SECONDS);
            pool.shutdown();

            // 这一轮跑出了几个孤儿节点？
            List<Department> orphans = findOrphans();
            if (!orphans.isEmpty()) {
                orphanRounds++;
                totalOrphans += orphans.size();
                System.out.println("  第 " + round + " 轮出现孤儿节点："
                        + orphans.size() + " 个，父部门 ID = " + orphans.get(0).getParentId());
            }

            // 清掉本轮的目标部门和子部门，下一轮重新开始
            cleanRound();
        }

        System.out.println();
        System.out.println("===== 部门删除竞态测试结果 =====");
        System.out.println("跑了 " + rounds + " 轮，其中 " + orphanRounds + " 轮出现了孤儿节点");
        System.out.println("孤儿节点总数：" + totalOrphans + " 个");
        System.out.println("================================");

        // 修复前实测：20 轮全部出现孤儿节点，共 189 个（复现率 100%）。
        // 修成「一条带 NOT EXISTS 的 DELETE」之后，应当一轮都不出现。
        assertEquals(0, orphanRounds,
                "修复后不应当再产生孤儿节点。修复前实测：20/20 轮都出现，共 189 个");
    }

    @Test
    @DisplayName("库里已经有孤儿节点时，查部门树不应当崩溃")
    void shouldNotCrashWhenOrphanAlreadyExists() {

        // 绕过 Service 的校验，直接往库里插一条 parent_id 指向不存在部门的记录，
        // 模拟「写操作修好之前就已经存在的历史脏数据」
        Long orphanId = insertDepartmentDirectly("race_orphan_" + System.nanoTime(), 999999L);

        // 修复前这里会抛 NullPointerException（组装树时父节点是 null）
        List<DepartmentTreeVO> tree = departmentService.getDepartmentTree(adminId);

        assertNotNull(tree, "查部门树不应当因为一条脏数据就崩掉");
        assertTrue(tree.stream().anyMatch(node -> orphanId.equals(node.getId())),
                "孤儿节点应当被当作根节点正常返回，而不是让整个接口 500");
    }

    /**
     * 找出孤儿节点：parent_id 不是 0，但它指向的父部门在库里已经不存在了。
     *
     * 等价于计划里那条 SQL：
     *   SELECT d.* FROM sys_department d
     *   LEFT JOIN sys_department p ON d.parent_id = p.id
     *   WHERE d.parent_id <> 0 AND p.id IS NULL;
     */
    private List<Department> findOrphans() {
        List<Department> all = departmentMapper.selectList(null);

        Set<Long> existingIds = new HashSet<>();
        for (Department d : all) {
            existingIds.add(d.getId());
        }

        List<Department> orphans = new ArrayList<>();
        for (Department d : all) {
            Long parentId = d.getParentId();
            if (parentId != null && parentId != 0L && !existingIds.contains(parentId)) {
                orphans.add(d);
            }
        }
        return orphans;
    }

    /** 清掉本轮的目标部门和它下面的子部门（含孤儿节点），下一轮重新造数据 */
    private void cleanRound() {
        departmentMapper.delete(new LambdaQueryWrapper<Department>()
                .like(Department::getName, "race_child_"));
        departmentMapper.delete(new LambdaQueryWrapper<Department>()
                .like(Department::getName, "race_target_"));
    }

    // ---------- 造数据的工具方法 ----------

    private Long createDepartment(String name) {
        Department department = new Department();
        department.setName(name);
        department.setParentId(0L);
        department.setStatus(1);
        department.setSort(0);
        departmentMapper.insert(department);
        return department.getId();
    }

    /**
     * 绕过 Service 的校验直接插一条部门记录，用来制造脏数据。
     * 走 createDepartment 的话，parentId 指向不存在的部门会被校验拦下来。
     */
    private Long insertDepartmentDirectly(String name, Long parentId) {
        Department department = new Department();
        department.setName(name);
        department.setParentId(parentId);
        department.setStatus(1);
        department.setSort(0);
        departmentMapper.insert(department);
        return department.getId();
    }

    private Long createAdmin(Long departmentId) {
        User user = new User();
        user.setUsername("test_race_admin_" + System.nanoTime());
        user.setName("竞态测试管理员");
        user.setPassword(passwordEncoder.encode("test123456"));
        user.setDepartmentId(departmentId);
        user.setStatus(1);
        user.setRole("ADMIN");
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user.getId();
    }
}
