package com.qqlin.oa.dto;

/**
 * 「请假审批结果」这条消息的内容。
 *
 * msgId 是这一条消息自己的编号（发消息前生成）。
 * 消费者靠它做幂等：同一条消息被投递两次时，
 * 第二次会因为 msg_id 撞唯一索引而插入失败，于是不会产生两条通知。
 */
public class LeaveResultMessage {

    private String msgId;
    private Long leaveId;
    private Long applicantId;
    private String status;
    private String comment;

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public Long getLeaveId() {
        return leaveId;
    }

    public void setLeaveId(Long leaveId) {
        this.leaveId = leaveId;
    }

    public Long getApplicantId() {
        return applicantId;
    }

    public void setApplicantId(Long applicantId) {
        this.applicantId = applicantId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
