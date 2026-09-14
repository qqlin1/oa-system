package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.ApprovalFlow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApprovalFlowMapper extends BaseMapper<ApprovalFlow> {

    /**
     * 查某个业务的完整审批链，按级别升序。
     *
     * 只查 status = 1 的节点：停用某一级就相当于把它从链上摘掉，
     * 不用去改已经提交的单据。
     */
    @Select("SELECT * FROM sys_approval_flow "
            + "WHERE biz_type = #{bizType} AND status = 1 "
            + "ORDER BY step ASC")
    List<ApprovalFlow> selectChain(@Param("bizType") String bizType);

    /**
     * 查某个业务一共有几级审批。
     *
     * 返回 null 表示这个业务根本没配审批链 —— 调用方要当成错误处理，
     * 不能当成「0 级 = 直接通过」。
     */
    @Select("SELECT MAX(step) FROM sys_approval_flow "
            + "WHERE biz_type = #{bizType} AND status = 1")
    Integer selectMaxStep(@Param("bizType") String bizType);
}
