package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.Permission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * 查一个人拥有的所有权限编码。
     *
     * 这条 SQL 走三张表：用户角色 → 角色权限 → 权限。
     * 用 DISTINCT 是因为一个人有多个角色时，两个角色可能都含同一个权限。
     *
     * 注意这里过滤了 r.status = 1：角色停用后权限立即失效，不用清关联表。
     */
    @Select("SELECT DISTINCT p.code FROM sys_permission p "
            + "JOIN sys_role_permission rp ON rp.permission_id = p.id "
            + "JOIN sys_user_role ur ON ur.role_id = rp.role_id "
            + "JOIN sys_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId} AND r.status = 1")
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
