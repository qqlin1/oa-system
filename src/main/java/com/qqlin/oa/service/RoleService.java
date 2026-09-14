package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.annotation.RequiresPermission;
import com.qqlin.oa.entity.Role;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.entity.UserRole;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.UserNotFoundException;
import com.qqlin.oa.mapper.RoleMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.mapper.UserRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 角色管理：查角色、给用户分配/移除角色。
 *
 * 这是 RBAC 的「管理面」。没有它，RBAC 就只是半成品 ——
 * 权限模型做得再好，如果分配角色还得手动改数据库，那它就不是一个能用的功能。
 *
 * 所有写操作都必须带 @RequiresPermission("role:manage")：
 * 能改别人权限的人，本身必须是最高权限者，否则就成了「越权提权」的漏洞。
 */
@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;

    public RoleService(RoleMapper roleMapper,
                       UserRoleMapper userRoleMapper,
                       UserMapper userMapper,
                       PermissionService permissionService) {
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.userMapper = userMapper;
        this.permissionService = permissionService;
    }

    /** 列出所有启用中的角色，供分配时选择。 */
    @RequiresPermission("role:list")
    public List<Role> listRoles(Long currentUserId) {
        return roleMapper.selectList(
                new LambdaQueryWrapper<Role>()
                        .eq(Role::getStatus, 1)
                        .orderByAsc(Role::getId));
    }

    /** 查某个用户当前拥有的角色。 */
    @RequiresPermission("role:list")
    public List<Role> listUserRoles(Long currentUserId, Long userId) {
        requireUserExists(userId);
        return roleMapper.selectByUserId(userId);
    }

    /**
     * 给用户分配角色。
     *
     * 重复分配同一个角色是安全的：sys_user_role 上 (user_id, role_id) 有唯一索引，
     * 这里先查一次避免撞键报错，效果等价于幂等。
     *
     * 分配完必须清权限缓存，否则新角色要等 TTL 到期才生效 ——
     * 表现就是「我明明给他加了权限，怎么还是不行」。
     */
    @Transactional
    @RequiresPermission("role:manage")
    public void assignRole(Long currentUserId, Long userId, String roleCode) {
        requireUserExists(userId);

        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, roleCode));
        if (role == null) {
            throw new IllegalArgumentException("角色不存在：" + roleCode);
        }

        Long exists = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getRoleId, role.getId()));
        if (exists == 0) {
            userRoleMapper.insert(new UserRole(userId, role.getId()));
        }

        permissionService.evict(userId);
    }

    /**
     * 移除用户的某个角色。
     *
     * 这里有一条重要的保护规则：【不能移除自己的管理员角色】。
     *
     * 为什么需要它：如果管理员能把自己的 ADMIN 角色删掉，系统就可能再没有任何管理员，
     * 谁都改不了权限，只能去数据库手动改数据才能救回来 —— 这叫「把自己锁在门外」。
     *
     * 这条规则足够彻底：任何人都不可能删掉自己的管理员身份，
     * 所以系统里永远至少剩一个管理员 —— 就是正在操作的那个人。
     *
     * （另一种写法是「不允许移除系统中最后一个管理员」。但那个判断依赖全局计数，
     *   在并发下有两个管理员同时删对方时会一起通过检查、双双删成功，
     *   最后谁都不是管理员。而「不能删自己」不依赖计数，天然没有这个竞态。）
     */
    @Transactional
    @RequiresPermission("role:manage")
    public void revokeRole(Long currentUserId, Long userId, String roleCode) {
        requireUserExists(userId);

        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, roleCode));
        if (role == null) {
            throw new IllegalArgumentException("角色不存在：" + roleCode);
        }

        // data_scope = 1（全部数据）代表这是管理员级别的角色
        if (Objects.equals(currentUserId, userId) && Role.SCOPE_ALL == role.getDataScope()) {
            throw new ForbiddenException("不能移除自己的管理员角色，否则系统可能失去所有管理员");
        }

        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getRoleId, role.getId()));

        permissionService.evict(userId);
    }

    private void requireUserExists(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new UserNotFoundException("用户不存在");
        }
    }
}
