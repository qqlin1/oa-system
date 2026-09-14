package com.qqlin.oa.service;

import com.qqlin.oa.entity.ApprovalFlow;
import com.qqlin.oa.entity.Role;
import com.qqlin.oa.mapper.ApprovalFlowMapper;
import com.qqlin.oa.mapper.RoleMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 审批链的读取与判定。
 *
 * 把「这个业务要几级审批、第 N 级谁审、某人能不能审这一级」这三件事收在这里，
 * 业务 Service（比如 LeaveService）只管调用，不用关心审批链存在哪、怎么查。
 *
 * 注意：审批链本身也是「配置」。改级数、改每级的审批角色，都是改数据，不是改代码。
 */
@Service
public class ApprovalFlowService {

    /** 请假业务的类型标识，对应 sys_approval_flow.biz_type */
    public static final String BIZ_TYPE_LEAVE = "LEAVE";

    private final ApprovalFlowMapper approvalFlowMapper;
    private final RoleMapper roleMapper;
    private final PermissionService permissionService;

    public ApprovalFlowService(ApprovalFlowMapper approvalFlowMapper,
                               RoleMapper roleMapper,
                               PermissionService permissionService) {
        this.approvalFlowMapper = approvalFlowMapper;
        this.roleMapper = roleMapper;
        this.permissionService = permissionService;
    }

    /** 查某个业务的完整审批链，按级别升序。 */
    public List<ApprovalFlow> getChain(String bizType) {
        return approvalFlowMapper.selectChain(bizType);
    }

    /**
     * 查某个业务一共有几级审批。
     *
     * @return 级数；null 表示这个业务没有配置审批链
     */
    public Integer getMaxStep(String bizType) {
        return approvalFlowMapper.selectMaxStep(bizType);
    }

    /** 查第 step 级的节点配置，没配就返回 null。 */
    public ApprovalFlow getNode(String bizType, int step) {
        return getChain(bizType).stream()
                .filter(node -> Objects.equals(node.getStep(), step))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断某个人能不能审批这个节点。
     *
     * 规则：拥有该节点要求的角色即可。
     * 例外：管理员可以审批任何节点 —— 这是「超管代审」，
     * 真实 OA 里主管请假、主管离职这类情况都需要它兜底，否则单子会卡死没人能审。
     */
    public boolean canApprove(Long userId, ApprovalFlow node) {
        if (node == null || userId == null) {
            return false;
        }

        if (permissionService.isAdmin(userId)) {
            return true;
        }

        List<Role> roles = roleMapper.selectByUserId(userId);
        return roles.stream()
                .anyMatch(role -> Objects.equals(role.getCode(), node.getApproverRoleCode()));
    }
}
