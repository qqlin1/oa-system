package com.qqlin.oa.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.entity.Role;
import com.qqlin.oa.entity.UserRole;
import com.qqlin.oa.mapper.RoleMapper;
import com.qqlin.oa.mapper.UserRoleMapper;
import com.qqlin.oa.service.PermissionService;
import org.springframework.stereotype.Component;

/**
 * 测试辅助：给测试里临时创建的用户分配角色。
 *
 * 为什么需要它：生产代码里「创建用户」走的是 UserService.createUser，
 * 那里会顺手写 sys_user_role。但测试为了造数据快，是直接 userMapper.insert()
 * 的，绕过了 Service —— 结果就是造出来的用户在 RBAC 里没有任何角色，
 * 一调带 @RequiresPermission 的方法就被拒。
 *
 * 放在 test 源码目录下，不会打进生产包。
 */
@Component
public class TestRoleAssigner {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionService permissionService;

    public TestRoleAssigner(RoleMapper roleMapper,
                            UserRoleMapper userRoleMapper,
                            PermissionService permissionService) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.permissionService = permissionService;
    }

    /**
     * 给用户分配角色。重复分配会被唯一索引挡掉，这里先查一次避免抛异常。
     *
     * @param roleCode 角色编码，如 ADMIN / DEPT_MANAGER / USER。
     *                 这些角色来自 sql/schema.sql 的种子数据，测试库必须先执行过建表脚本。
     */
    public void assign(Long userId, String roleCode) {
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, roleCode));
        if (role == null) {
            throw new IllegalStateException(
                    "角色不存在：" + roleCode + "。请确认测试库执行过 sql/schema.sql 的种子数据部分。");
        }

        Long exists = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getRoleId, role.getId()));
        if (exists == 0) {
            userRoleMapper.insert(new UserRole(userId, role.getId()));
        }

        // 和生产代码保持一致：改了角色就要清权限缓存，
        // 否则新权限要等 30 分钟 TTL 到期才生效。
        permissionService.evict(userId);
    }
}
