package com.qqlin.oa.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 给用户分配角色的请求体。
 *
 * 只传角色编码，不传角色 ID —— 编码是稳定的业务标识（ADMIN / DEPT_MANAGER / USER），
 * ID 是数据库自增的，换环境就变了。对外接口用编码更稳。
 */
public class RoleAssignDTO {

    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }
}
