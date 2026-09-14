package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qqlin.oa.annotation.AuditLog;
import com.qqlin.oa.annotation.RequiresPermission;
import com.qqlin.oa.common.PageResult;
import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.dto.LeaveCreateDTO;
import com.qqlin.oa.dto.LeaveResultMessage;
import com.qqlin.oa.entity.ApprovalFlow;
import com.qqlin.oa.entity.Idempotent;
import com.qqlin.oa.entity.LeaveApproval;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.InvalidLeaveRequestException;
import com.qqlin.oa.exception.InvalidLeaveStatusException;
import com.qqlin.oa.exception.LeaveNotFoundException;
import com.qqlin.oa.mapper.IdempotentMapper;
import com.qqlin.oa.mapper.LeaveApprovalMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.vo.LeaveVO;
import com.qqlin.oa.vo.UserVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class LeaveService {
    private final LeaveRequestMapper leaveRequestMapper;
    private final IdempotentMapper idempotentMapper;
    private final UserService userService;
    private final LeaveResultProducer leaveResultProducer;
    private final DataScopeService dataScopeService;
    private final ApprovalFlowService approvalFlowService;
    private final LeaveApprovalMapper leaveApprovalMapper;

    public LeaveService(LeaveRequestMapper leaveRequestMapper,
                        IdempotentMapper idempotentMapper,
                        UserService userService,
                        LeaveResultProducer leaveResultProducer,
                        DataScopeService dataScopeService,
                        ApprovalFlowService approvalFlowService,
                        LeaveApprovalMapper leaveApprovalMapper) {
        this.leaveRequestMapper = leaveRequestMapper;
        this.idempotentMapper = idempotentMapper;
        this.userService = userService;
        this.leaveResultProducer = leaveResultProducer;
        this.dataScopeService = dataScopeService;
        this.approvalFlowService = approvalFlowService;
        this.leaveApprovalMapper = leaveApprovalMapper;
    }
    @Transactional
    public Long createLeave(Long currentUserId, LeaveCreateDTO dto) {
        String requestId = dto.getRequestId();

        // 没带幂等号的老请求，走原逻辑，不受影响
        if (requestId == null || requestId.isBlank()) {
            return doCreateLeave(currentUserId, dto);
        }

        try {
            // ① 直接插幂等记录，靠唯一索引判断是不是第一次
            Idempotent record = new Idempotent();
            record.setRequestId(requestId);
            record.setBizType("LEAVE");
            idempotentMapper.insert(record);

        } catch (DuplicateKeyException e) {
            // ② 撞唯一键 = 这个请求处理过了，直接返回上次的单号
            Idempotent exist = idempotentMapper.selectOne(
                    new LambdaQueryWrapper<Idempotent>()
                            .eq(Idempotent::getRequestId, requestId));

            return exist.getBizId();
        }

        // ③ 第一次：正常创建请假单
        Long leaveId = doCreateLeave(currentUserId, dto);

        // ④ 把单号回填到幂等记录，下次重复请求才能拿到它
        Idempotent update = new Idempotent();
        update.setBizId(leaveId);
        idempotentMapper.update(update,
                new LambdaQueryWrapper<Idempotent>()
                        .eq(Idempotent::getRequestId, requestId));

        return leaveId;
    }

    public Long doCreateLeave(Long currentUserId,LeaveCreateDTO dto){
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
    /**
     * 审批请假 —— 多级审批的状态机。
     *
     * 状态怎么流转（以配置的两级为例）：
     *
     *   提交后        status=PENDING,  current_step=1
     *   第 1 级通过   status=PENDING,  current_step=2   ← 注意：状态还是 PENDING
     *   第 2 级通过   status=APPROVED, current_step=3
     *
     *   任何一级拒绝  status=REJECTED（直接结束，后面的级别不用走了）
     *
     * 并发怎么控制：条件更新里【必须带上 current_step】。
     * 两个人同时审同一级时，SQL 是
     *   UPDATE sys_leave SET current_step=2
     *   WHERE id=? AND status='PENDING' AND current_step=1
     * 只有一个人能改成功（影响行数 1），另一个影响行数为 0，直接抛异常。
     *
     * 光靠「status 必须是 PENDING」是拦不住的 —— 中间级通过后状态仍然是 PENDING。
     * 这就是多级审批和单级审批在并发控制上最大的区别。
     */
    @AuditLog("审批请假")
    @RequiresPermission("leave:approve")
    public void approveLeave(Long currentUserId, Long leaveId, LeaveApprovalDTO dto){
        LeaveRequest currentLeave=leaveRequestMapper.selectById(leaveId);
        if(currentLeave==null){
            throw new LeaveNotFoundException("请假申请不存在");
        }
        if(Objects.equals(currentUserId,currentLeave.getApplicantId())){
            throw new ForbiddenException("不能审批自己提交的请假申请");
        }
        // 数据权限：功能上「能审批」还不够，还要看这张单子归不归你管。
        // 部门经理只能审本部门及下属部门的单子，只有管理员才能审全公司。
        // 少了这一步，任何一个部门经理都能审任何部门的单子。
        if(!dataScopeService.canAccessDepartment(currentUserId, currentLeave.getDepartmentId())){
            throw new ForbiddenException("无权审批该部门的请假申请");
        }
        if(currentLeave.getStatus()!=LeaveStatus.PENDING){
            throw new InvalidLeaveStatusException("当前请假审批已经处理，不能重复审批");
        }

        // ---------- 多级审批：先算出「现在停在第几级」和「一共几级」 ----------
        int currentStep = currentLeave.getCurrentStep() == null ? 1 : currentLeave.getCurrentStep();
        Integer maxStep = approvalFlowService.getMaxStep(ApprovalFlowService.BIZ_TYPE_LEAVE);
        if (maxStep == null) {
            // 没配审批链不等于「直接通过」，那是配置缺失，必须报错
            throw new InvalidLeaveStatusException("请假业务没有配置审批链，请联系管理员");
        }

        ApprovalFlow node = approvalFlowService.getNode(ApprovalFlowService.BIZ_TYPE_LEAVE, currentStep);
        if (node == null) {
            throw new InvalidLeaveStatusException("审批链第 " + currentStep + " 级没有配置，请联系管理员");
        }
        // 这一级该谁审：必须拥有该节点要求的角色（管理员可代审任何节点）
        if (!approvalFlowService.canApprove(currentUserId, node)) {
            throw new ForbiddenException("第 " + currentStep + " 级是「" + node.getName()
                    + "」，需要 " + node.getApproverRoleCode() + " 角色");
        }

        LeaveStatus decision=LeaveStatus.valueOf(dto.getDecision());
        String comment = dto.getApprovalComment().trim();
        boolean isLastStep = currentStep >= maxStep;

        LeaveRequest updateLeave=new LeaveRequest();
        // 无论通过还是拒绝，级数都先推进一格
        updateLeave.setCurrentStep(currentStep + 1);
        updateLeave.setApproverId(currentUserId);
        updateLeave.setApprovalComment(comment);
        updateLeave.setApprovalTime(LocalDateTime.now());

        // 只有走到终点才改 status：
        //   拒绝           → REJECTED（不管在第几级）
        //   最后一级通过   → APPROVED
        //   中间级通过     → 不设置，保持 PENDING（MyBatis-Plus 只更新非 null 字段）
        if (decision == LeaveStatus.REJECTED) {
            updateLeave.setStatus(LeaveStatus.REJECTED);
        } else if (isLastStep) {
            updateLeave.setStatus(LeaveStatus.APPROVED);
        }

        LambdaUpdateWrapper<LeaveRequest> wrapper=new LambdaUpdateWrapper<>();
        wrapper.eq(LeaveRequest::getId,leaveId);
        wrapper.eq(LeaveRequest::getStatus,LeaveStatus.PENDING);
        // ★ 关键：把级数也放进条件里，防止同一级被审两次
        wrapper.eq(LeaveRequest::getCurrentStep, currentStep);
        int affectRows=leaveRequestMapper.update(updateLeave,wrapper);
        if(affectRows==0){
            throw new InvalidLeaveStatusException("请假状态发生改变，请稍后重试");
        }

        // 写审批流水：每一级都要留痕。
        // 不写流水的话，第 2 级审批会把主表上的第 1 级审批人覆盖掉，历史就查不到了。
        LeaveApproval approval = new LeaveApproval();
        approval.setLeaveId(leaveId);
        approval.setStep(currentStep);
        approval.setApproverId(currentUserId);
        approval.setDecision(decision.name());
        approval.setComment(comment);
        leaveApprovalMapper.insert(approval);

        // 顺序很重要：先把审批结果落库，再发消息通知。
        // 反过来（先发消息）的话，万一落库失败，
        // 用户会收到一条「审批通过」的通知，但库里其实还是待审批 —— 这就是事故。
        //
        // 只有走到终态才通知：中间级通过就通知的话，
        // 申请人会看到「已通过」，但单据其实还在流程里，是误导。
        if (decision == LeaveStatus.REJECTED || isLastStep) {
            sendApprovalResultMessage(currentLeave, decision, comment);
        }
    }

    /**
     * 发审批结果消息，让消费者去生成站内通知。
     *
     * 这里故意不处理发送失败：审批本身已经成功了，
     * 通知发不出去只是体验差一点，不能因为它就把整个审批回滚掉。
     * 真实项目里会有补偿任务重发，这里先记日志就够了。
     */
    private void sendApprovalResultMessage(LeaveRequest leave,
                                           LeaveStatus decision,
                                           String comment) {
        LeaveResultMessage message = new LeaveResultMessage();
        message.setMsgId(UUID.randomUUID().toString());
        message.setLeaveId(leave.getId());
        message.setApplicantId(leave.getApplicantId());
        message.setStatus(decision.name());
        message.setComment(comment);
        leaveResultProducer.send(message);
    }
    @AuditLog("撤销请假")
    public void cancelLeave(Long currentUserId,
                            long leaveId){
        LeaveRequest currentLeave=leaveRequestMapper.selectById(leaveId);
        if(currentLeave==null){
            throw new LeaveNotFoundException("请假申请不存在");
        }
        if(!Objects.equals(currentUserId,currentLeave.getApplicantId())){
            throw new ForbiddenException("只能撤销自己提交的请假申请");
        }
        if(currentLeave.getStatus()!=LeaveStatus.PENDING){
            throw new InvalidLeaveStatusException("只有待审批的请假申请才能撤销");
        }
        LeaveRequest updateLeave=new LeaveRequest();
        updateLeave.setStatus(LeaveStatus.CANCELED);
        LambdaUpdateWrapper<LeaveRequest> wrapper=new LambdaUpdateWrapper<>();
        wrapper.eq(LeaveRequest::getId,leaveId);
        wrapper.eq(LeaveRequest::getApplicantId,currentUserId);
        wrapper.eq(LeaveRequest::getStatus,LeaveStatus.PENDING);
        int affectedRows =leaveRequestMapper.update(updateLeave,wrapper);
        if(affectedRows==0){
            throw new InvalidLeaveStatusException("请假申请状态已经发生变化，请刷新后重试");
        }
    }
    @RequiresPermission("leave:view-pending")
    public PageResult<LeaveVO> getPendingLeaveList(Long currentUserId,
                                                        long current,
                                                        long size){
        Page<LeaveRequest> leavePage=new Page<>(current,size);
        LambdaQueryWrapper<LeaveRequest> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(LeaveRequest::getStatus,LeaveStatus.PENDING);

        // 数据权限同样要作用在「列表查询」上，不能只拦单个操作。
        // 否则部门经理虽然审批不了别人的单子，却能在待办列表里看到全公司的请假，
        // 信息已经泄露了。
        // accessibleDepartmentIds 返回 null 表示拥有全部数据权限，不需要加过滤。
        List<Long> accessibleDeptIds = dataScopeService.accessibleDepartmentIds(currentUserId);
        if (accessibleDeptIds != null) {
            if (accessibleDeptIds.isEmpty()) {
                // 一个部门都管不到（比如「仅本人」权限），直接返回空列表，
                // 不要走 in () —— 空集合的 in 条件在 SQL 里是非法或恒假的，容易踩坑。
                return new PageResult<>(new ArrayList<>(), 0, current, size);
            }
            wrapper.in(LeaveRequest::getDepartmentId, accessibleDeptIds);
        }
        wrapper.orderByDesc(LeaveRequest::getCreateTime);
        wrapper.orderByDesc(LeaveRequest::getId);
        leaveRequestMapper.selectPage(leavePage,wrapper);
        List<LeaveVO> leaveVOList=new ArrayList<>();
        for (LeaveRequest leaveRequest:leavePage.getRecords()){

            leaveVOList.add(toLeaveVO(leaveRequest));
        }
        return new PageResult<>(leaveVOList,leavePage.getTotal(),leavePage.getCurrent(),leavePage.getSize());
    }
}
