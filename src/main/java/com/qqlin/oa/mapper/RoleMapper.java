package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.Role;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 查一个人拥有的所有角色。
     *
     * 只查 status = 1 的：角色被停用后，持有它的人就自动失去对应权限，
     * 不用去清理 sys_user_role 里的关联数据。
     */
    @Select("SELECT r.* FROM sys_role r "
            + "JOIN sys_user_role ur ON ur.role_id = r.id "
            + "WHERE ur.user_id = #{userId} AND r.status = 1")
    List<Role> selectByUserId(@Param("userId") Long userId);
}
