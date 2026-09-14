package com.qqlin.oa.service;

import com.qqlin.oa.entity.Role;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 数据权限。
 *
 * 权限分两层，很多人只知道第一层：
 *
 *   功能权限 —— 你有没有「审批请假」这个操作。用 sys_role_permission 配置。
 *   数据权限 —— 你能审批「谁」的请假。用 sys_role.data_scope 配置。
 *
 * 只有功能权限是不够的：如果公司有 20 个部门、5 个部门经理，
 * 光判断「他是不是部门经理」会导致任何一个经理都能审任何一个部门的单子 —— 这显然不对。
 *
 * data_scope 的四个取值：
 *   1 全部        —— 管理员，什么都能看
 *   2 本部门及以下 —— 部门经理，能看自己部门和所有下属部门
 *   3 本部门      —— 只能看自己部门
 *   4 仅本人      —— 普通员工
 */
@Service
public class DataScopeService {

    private final PermissionService permissionService;
    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;

    public DataScopeService(PermissionService permissionService,
                            UserMapper userMapper,
                            DepartmentMapper departmentMapper) {
        this.permissionService = permissionService;
        this.userMapper = userMapper;
        this.departmentMapper = departmentMapper;
    }

    /**
     * 判断操作人能不能操作「某个部门」的数据。
     *
     * @param targetDepartmentId 数据归属的部门 ID
     * @return true 表示在管辖范围内
     */
    public boolean canAccessDepartment(Long operatorId, Long targetDepartmentId) {
        int scope = permissionService.getDataScope(operatorId);

        if (scope == Role.SCOPE_ALL) {
            return true;
        }

        // 数据没有归属部门时，只有全部数据权限的人能碰。
        // 这里是「拒绝」而不是「放行」—— 拿不准归属的数据，默认按最严格处理。
        if (targetDepartmentId == null) {
            return false;
        }

        Long operatorDeptId = departmentIdOf(operatorId);
        if (operatorDeptId == null) {
            return false;
        }

        if (scope == Role.SCOPE_SELF) {
            // 仅本人：只能碰自己的数据，不能跨部门操作别人的
            return false;
        }
        if (scope == Role.SCOPE_DEPT) {
            return Objects.equals(operatorDeptId, targetDepartmentId);
        }

        // 本部门及以下：把自己和所有下属部门一次性查出来，看目标在不在里面
        return departmentMapper.selectSelfAndDescendantIds(operatorDeptId)
                .contains(targetDepartmentId);
    }

    /**
     * 拿到操作人能看到的所有部门 ID，用于给列表查询加过滤条件。
     *
     * @return null 表示「不受限制」（拥有全部数据权限），调用方不需要加任何过滤条件。
     *         这一点很关键：如果返回空集合表示不受限，调用方会误以为「一个部门都看不了」。
     */
    public List<Long> accessibleDepartmentIds(Long operatorId) {
        int scope = permissionService.getDataScope(operatorId);

        if (scope == Role.SCOPE_ALL) {
            return null;
        }

        Long operatorDeptId = departmentIdOf(operatorId);
        if (operatorDeptId == null) {
            return List.of();
        }

        if (scope == Role.SCOPE_SELF) {
            return List.of();
        }
        if (scope == Role.SCOPE_DEPT) {
            return List.of(operatorDeptId);
        }

        return departmentMapper.selectSelfAndDescendantIds(operatorDeptId);
    }

    private Long departmentIdOf(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null ? null : user.getDepartmentId();
    }
}
