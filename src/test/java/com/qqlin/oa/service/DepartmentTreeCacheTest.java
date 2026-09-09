package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.DepartmentCreateDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.vo.DepartmentTreeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证部门树的 Cache Aside 缓存确实在工作。
 *
 * 这里要解决一个关键问题：怎么证明「这次读的是缓存，不是数据库」？
 *
 * 办法是：绕过 Service，直接用 Mapper 改数据库。
 * 因为删缓存的动作写在 Service 里，绕过 Service 改库就不会触发删缓存，
 * 于是缓存里留着的还是旧数据。这时候再查：
 *
 *   拿到旧数据 → 说明读的是缓存，缓存生效了
 *   拿到新数据 → 说明根本没走缓存，白写了
 *
 * 这样设计出来的断言是「可证伪」的——如果缓存没生效，测试一定会红。
 */
@SpringBootTest
class DepartmentTreeCacheTest {

    /** 必须和 DepartmentTreeCacheService 里的常量保持一致 */
    private static final String TREE_KEY = "oa:dept:tree";

    @Autowired
    private DepartmentService departmentService;
    @Autowired
    private DepartmentMapper departmentMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private Long adminId;
    private Long adminDeptId;

    @BeforeEach
    void setUp() {
        // 每个测试开始前先清掉缓存，避免测试之间互相干扰
        redisTemplate.delete(TREE_KEY);
        adminDeptId = createDepartment("cache_admindept_" + System.nanoTime());
        adminId = createAdmin(adminDeptId);
    }

    @AfterEach
    void tearDown() {
        // 缓存也要清。不清的话，其他测试（比如查部门树的那些）
        // 会读到这里留下来的旧缓存，莫名其妙就失败了。
        redisTemplate.delete(TREE_KEY);
        departmentMapper.delete(new LambdaQueryWrapper<Department>()
                .like(Department::getName, "cache_"));
        userMapper.delete(new LambdaQueryWrapper<User>()
                .like(User::getUsername, "test_cache_"));
    }

    @Test
    @DisplayName("第一次查写缓存，第二次查走缓存（绕过 Service 改库后读到的仍是旧数据）")
    void shouldReadFromCacheOnSecondQuery() {

        String originName = "cache_origin_" + System.nanoTime();
        Long deptId = createDepartment(originName);

        // 第一次查：缓存是空的 → 查数据库 → 写回缓存
        List<DepartmentTreeVO> first = departmentService.getDepartmentTree(adminId);
        assertTrue(containsId(first, deptId), "第一次查应当能从数据库里查到这个部门");

        // 关键断言一：查完之后，Redis 里应当已经有缓存了
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(TREE_KEY)),
                "第一次查之后，Redis 里应当已经写好了部门树缓存");

        // 绕过 Service，直接用 Mapper 改数据库。
        // 不走 Service 就不会触发「删缓存」，所以缓存里留着的还是旧数据。
        Department changed = new Department();
        changed.setId(deptId);
        changed.setName("cache_changed_" + System.nanoTime());
        departmentMapper.updateById(changed);

        // 第二次查
        List<DepartmentTreeVO> second = departmentService.getDepartmentTree(adminId);
        String nameOnSecondQuery = flatten(second).stream()
                .filter(node -> deptId.equals(node.getId()))
                .findFirst()
                .map(DepartmentTreeVO::getName)
                .orElse(null);

        // 关键断言二：读到的还是旧名字，证明这一次读的是缓存而不是数据库
        assertEquals(originName, nameOnSecondQuery,
                "缓存没失效，第二次查读到的应当还是旧名字。"
                        + "如果这里拿到的是新名字，说明缓存根本没生效");
    }

    @Test
    @DisplayName("通过 Service 创建部门后，缓存被删除，再查能看到新部门")
    void shouldEvictCacheAfterCreateDepartment() {

        // 先查一次，把缓存填上
        departmentService.getDepartmentTree(adminId);
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(TREE_KEY)),
                "前置条件：缓存里应当已经有数据");

        // 通过 Service 创建部门——创建成功后应当删掉缓存
        DepartmentCreateDTO dto = new DepartmentCreateDTO();
        dto.setName("cache_newdept_" + System.nanoTime());
        dto.setParentId(0L);
        dto.setSort(0);
        Long newDeptId = departmentService.createDepartment(adminId, dto).getId();

        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(TREE_KEY)),
                "创建部门之后，旧的部门树缓存应当被删除");

        // 再查一次，新部门应当出现在结果里
        List<DepartmentTreeVO> tree = departmentService.getDepartmentTree(adminId);
        assertTrue(containsId(tree, newDeptId),
                "缓存删掉之后重新查，应当能看到刚创建的部门");
    }

    @Test
    @DisplayName("通过 Service 删除部门后，缓存被删除，再查这个部门已经不在")
    void shouldEvictCacheAfterDeleteDepartment() {

        Long deptId = createDepartment("cache_todelete_" + System.nanoTime());

        // 先查一次，把缓存填上（此时缓存里有这个部门）
        List<DepartmentTreeVO> before = departmentService.getDepartmentTree(adminId);
        assertTrue(containsId(before, deptId), "前置条件：删之前树里应当有这个部门");
        // 这个前置断言不能省。少了它，万一缓存压根没生效，
        // 下面那句「缓存应当被删」也会通过（因为从来就没写过），测试就成了摆设。
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(TREE_KEY)),
                "前置条件：缓存里应当已经有数据");

        // 通过 Service 删除
        departmentService.deleteDepartment(adminId, deptId);

        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(TREE_KEY)),
                "删除部门之后，旧的部门树缓存应当被删除");

        // 再查一次，这个部门不应当还在
        List<DepartmentTreeVO> after = departmentService.getDepartmentTree(adminId);
        assertFalse(containsId(after, deptId),
                "缓存删掉之后重新查，被删除的部门不应当还在树里");
    }

    // ---------- 工具方法 ----------

    /** 树是嵌套的，摊平之后才好查找 */
    private List<DepartmentTreeVO> flatten(List<DepartmentTreeVO> nodes) {
        List<DepartmentTreeVO> result = new ArrayList<>();
        for (DepartmentTreeVO node : nodes) {
            result.add(node);
            result.addAll(flatten(node.getChildren()));
        }
        return result;
    }

    private boolean containsId(List<DepartmentTreeVO> tree, Long id) {
        return flatten(tree).stream().anyMatch(node -> id.equals(node.getId()));
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

    private Long createAdmin(Long departmentId) {
        User user = new User();
        user.setUsername("test_cache_admin_" + System.nanoTime());
        user.setName("缓存测试管理员");
        user.setPassword(passwordEncoder.encode("test123456"));
        user.setDepartmentId(departmentId);
        user.setStatus(1);
        user.setRole("ADMIN");
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user.getId();
    }
}
