package com.qqlin.oa.dto;


import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class LeaveApprovalDTO {
    @NotBlank(message = "审批决定不能为空")
    @Pattern(
            regexp = "^(APPROVED|REJECTED)$",
            message = "审批决定只能是APPROVED或者REJECTED"
    )
    private String decision;
    @NotBlank(message = "审批意见不能为空")
    @Size(max = 500,message = "审批意见最多500个字符")
    private String approvalComment;

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getApprovalComment() {
        return approvalComment;
    }

    public void setApprovalComment(String approvalComment) {
        this.approvalComment = approvalComment;
    }
}
