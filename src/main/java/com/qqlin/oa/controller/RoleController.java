package com.qqlin.oa.controller;

import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.RoleAssignDTO;
import com.qqlin.oa.entity.Role;
import com.qqlin.oa.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理接口。RBAC 的「管理面」。
 *
 * 路径设计：
 *   GET    /roles                          列出所有角色（供前端下拉框用）
 *   GET    /users/{userId}/roles           查某个用户有哪些角色
 *   POST   /users/{userId}/roles           给用户加一个角色
 *   DELETE /users/{userId}/roles/{roleCode} 移除用户的某个角色
 *
 * 为什么「给用户加角色」挂在 /users 下面而不是 /roles 下面：
 * 因为被操作的主体是「用户」，角色只是加给他的一个属性。
 * 换成 /roles/{code}/users 会把主次颠倒，读起来别扭。
 */
@RestController
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/roles")
    public Result<List<Role>> listRoles(@RequestAttribute("currentUserId") Long currentUserId) {
        return Result.success(roleService.listRoles(currentUserId));
    }

    @GetMapping("/users/{userId}/roles")
    public Result<List<Role>> listUserRoles(@RequestAttribute("currentUserId") Long currentUserId,
                                            @PathVariable("userId") Long userId) {
        return Result.success(roleService.listUserRoles(currentUserId, userId));
    }

    @PostMapping("/users/{userId}/roles")
    public Result<Void> assignRole(@RequestAttribute("currentUserId") Long currentUserId,
                                   @PathVariable("userId") Long userId,
                                   @Valid @RequestBody RoleAssignDTO dto) {
        roleService.assignRole(currentUserId, userId, dto.getRoleCode());
        return Result.success();
    }

    @DeleteMapping("/users/{userId}/roles/{roleCode}")
    public Result<Void> revokeRole(@RequestAttribute("currentUserId") Long currentUserId,
                                   @PathVariable("userId") Long userId,
                                   @PathVariable("roleCode") String roleCode) {
        roleService.revokeRole(currentUserId, userId, roleCode);
        return Result.success();
    }
}
