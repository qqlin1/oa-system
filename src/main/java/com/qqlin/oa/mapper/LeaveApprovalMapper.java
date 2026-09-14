package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.LeaveApproval;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LeaveApprovalMapper extends BaseMapper<LeaveApproval> {

    /** 查一张请假单的完整审批流水，按级别升序 —— 就是它的「审批履历」。 */
    @Select("SELECT * FROM sys_leave_approval WHERE leave_id = #{leaveId} ORDER BY step ASC")
    List<LeaveApproval> selectByLeaveId(@Param("leaveId") Long leaveId);
}
