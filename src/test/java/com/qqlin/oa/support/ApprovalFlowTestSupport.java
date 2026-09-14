package com.qqlin.oa.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.qqlin.oa.entity.ApprovalFlow;
import com.qqlin.oa.mapper.ApprovalFlowMapper;
import org.springframework.stereotype.Component;

/**
 * 测试辅助：切换 LEAVE 审批链的级数。
 *
 * 【为什么需要它】
 *
 * 种子数据里请假配的是两级审批（部门经理 → 管理员）。
 * 但很多既有测试关心的不是「审批几级」，而是别的主题：
 *
 *   RbacPermissionTest     —— 测权限模型（谁能审、数据权限）
 *   RoleManageTest         —— 测角色分配
 *   LeaveServiceConcurrencyTest —— 测并发控制
 *
 * 这些测试都是「审一次就断言 APPROVED」。在两级配置下，审一次只会推进到第二级，
 * 状态仍然是 PENDING —— 于是它们全部变红，但红的原因跟它们要测的东西毫无关系。
 *
 * 所以这些测试应该在一个「只有一级」的审批链下运行：主题不被干扰，断言保持原意。
 * 多级审批本身由 MultiLevelApprovalTest 专门覆盖。
 *
 * 这本身也是个值得记住的原则：**测试要为自己需要的环境负责，而不是依赖全局默认值。**
 */
@Component
public class ApprovalFlowTestSupport {

    private static final String BIZ_TYPE_LEAVE = "LEAVE";

    /** 种子里配的第二级。停用它就变成单级审批。 */
    private static final int SECOND_STEP = 2;

    private final ApprovalFlowMapper approvalFlowMapper;

    public ApprovalFlowTestSupport(ApprovalFlowMapper approvalFlowMapper) {
        this.approvalFlowMapper = approvalFlowMapper;
    }

    /** 切成单级审批：停用第二级。用于那些不关心级数的测试。 */
    public void useSingleLevelFlow() {
        setStepStatus(SECOND_STEP, 0);
    }

    /** 恢复成两级审批。用于多级审批专项测试，以及测试收尾时还原现场。 */
    public void useTwoLevelFlow() {
        setStepStatus(SECOND_STEP, 1);
    }

    private void setStepStatus(int step, int status) {
        ApprovalFlow update = new ApprovalFlow();
        update.setStatus(status);
        approvalFlowMapper.update(update,
                new LambdaUpdateWrapper<ApprovalFlow>()
                        .eq(ApprovalFlow::getBizType, BIZ_TYPE_LEAVE)
                        .eq(ApprovalFlow::getStep, step));
    }
}
