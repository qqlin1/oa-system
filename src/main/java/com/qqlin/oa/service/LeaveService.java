package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qqlin.oa.common.PageResult;
import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.dto.LeaveCreateDTO;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.InvalidLeaveRequestException;
import com.qqlin.oa.exception.InvalidLeaveStatusException;
import com.qqlin.oa.exception.LeaveNotFoundException;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.vo.LeaveVO;
import com.qqlin.oa.vo.UserVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class LeaveService {
    private final LeaveRequestMapper leaveRequestMapper;
    private final UserService userService;
    public LeaveService(LeaveRequestMapper leaveRequestMapper,UserService userService) {
        this.leaveRequestMapper = leaveRequestMapper;
        this.userService = userService;
    }
    public Long createLeave(Long currentUserId,LeaveCreateDTO dto){
        UserVO currentUser =
                userService.getById(currentUserId);
        Long departmentId=currentUser.getDepartmentId();
        if(departmentId==null||departmentId<=0) {
        throw new InvalidLeaveRequestException("当前用户尚未分配部门，不能提交请假申请");
        }
        LocalDateTime now=LocalDateTime.now();
        if (dto.getStartTime().isBefore(now)){
            throw new InvalidLeaveRequestException("请假开始时间不能早于当前时间");
        }
        if(!dto.getEndTime().isAfter(dto.getStartTime())){
            throw new InvalidLeaveRequestException("请假结束时间必须晚于请假开始时间");
        }

        LeaveRequest leaveRequest=new LeaveRequest();
        leaveRequest.setApplicantId(currentUserId);
        leaveRequest.setDepartmentId(departmentId);
        leaveRequest.setLeaveType(dto.getLeaveType());
        leaveRequest.setStartTime(dto.getStartTime());
        leaveRequest.setEndTime(dto.getEndTime());
        leaveRequest.setReason(dto.getReason().trim());
        leaveRequest.setStatus(LeaveStatus.PENDING);
        leaveRequestMapper.insert(leaveRequest);
        return leaveRequest.getId();
    }
    public PageResult<LeaveVO> getLeaveList(Long currentUserId, Long current, Long size)
    {
        Page<LeaveRequest> page=new Page<>(current,size);
        LambdaQueryWrapper<LeaveRequest> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(LeaveRequest::getApplicantId,currentUserId);
        wrapper.orderByDesc(LeaveRequest::getCreateTime);
        wrapper.orderByDesc(LeaveRequest::getId);
        Page<LeaveRequest> leavePage=leaveRequestMapper.selectPage(page,wrapper);
        List<LeaveVO> leaveVOList=new ArrayList<>();
        for (LeaveRequest leaveRequest:leavePage.getRecords()){
            leaveVOList.add(toLeaveVO(leaveRequest));
        }
        return new PageResult<>(
                leaveVOList,leavePage.getTotal(),leavePage.getCurrent(),leavePage.getSize()
        );
    }
    private LeaveVO toLeaveVO(
            LeaveRequest leaveRequest) {

        LeaveVO leaveVO = new LeaveVO();

        leaveVO.setId(leaveRequest.getId());
        leaveVO.setApplicantId(
                leaveRequest.getApplicantId()
        );
        leaveVO.setDepartmentId(
                leaveRequest.getDepartmentId()
        );
        leaveVO.setLeaveType(
                leaveRequest.getLeaveType()
        );
        leaveVO.setStartTime(
                leaveRequest.getStartTime()
        );
        leaveVO.setEndTime(
                leaveRequest.getEndTime()
        );
        leaveVO.setReason(
                leaveRequest.getReason()
        );
        leaveVO.setStatus(
                leaveRequest.getStatus()
        );
        leaveVO.setApproverId(
                leaveRequest.getApproverId()
        );
        leaveVO.setApprovalComment(
                leaveRequest.getApprovalComment()
        );
        leaveVO.setApprovalTime(
                leaveRequest.getApprovalTime()
        );
        leaveVO.setCreateTime(
                leaveRequest.getCreateTime()
        );

        return leaveVO;
    }
    public void approveLeave(Long currentUserId, Long leaveId, LeaveApprovalDTO dto){
        userService.requireAdmin(currentUserId);
        LeaveRequest currentLeave=leaveRequestMapper.selectById(leaveId);
        if(currentLeave==null){
            throw new LeaveNotFoundException("请假申请不存在");
        }
        if(Objects.equals(currentUserId,currentLeave.getApplicantId())){
            throw new ForbiddenException("不能审批自己提交的请假申请");
        }
        if(currentLeave.getStatus()!=LeaveStatus.PENDING){
            throw new InvalidLeaveStatusException("当前请假审批已经处理，不能重复审批");
        }
        LeaveStatus decision=LeaveStatus.valueOf(dto.getDecision());
        LeaveRequest updateLeave=new LeaveRequest();
        updateLeave.setStatus(decision);
        updateLeave.setApproverId(currentUserId);
        updateLeave.setApprovalComment(dto.getApprovalComment().trim());
        updateLeave.setApprovalTime(LocalDateTime.now());
        LambdaUpdateWrapper<LeaveRequest> wrapper=new LambdaUpdateWrapper<>();
        wrapper.eq(LeaveRequest::getId,leaveId);
        wrapper.eq(LeaveRequest::getStatus,LeaveStatus.PENDING);
        int affectRows=leaveRequestMapper.update(updateLeave,wrapper);
        if(affectRows==0){
            throw new InvalidLeaveStatusException("请假状态发生改变，请稍后重试");
        }
    }
    public void cancelLeave(Long currentUserId,
                            long leaveId){
        
    }
}
