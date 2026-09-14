package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 逻辑文件 —— 一次上传动作对应一条记录。
 *
 * 它指向一个 {@link FileBlob}（物理文件），并记录「谁传的、叫什么名字、挂在哪张单子上」。
 *
 * 为什么要和物理文件分开：
 *   同一份文件被两个人上传时，磁盘上只有一份（物理去重），
 *   但两个人各自要看到自己的文件名和上传时间。只有一张表就做不到 ——
 *   第二个人的记录会把第一个人的覆盖掉。
 *
 * 类名不叫 File，是为了不和 java.io.File 撞名。
 */
@TableName("sys_file")
public class FileRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long blobId;

    /** 用户上传时的原始文件名 */
    private String fileName;

    private Long uploaderId;

    /** 关联的业务类型，如 LEAVE。不挂业务时为空 */
    private String bizType;

    /** 关联的业务 ID */
    private Long bizId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBlobId() {
        return blobId;
    }

    public void setBlobId(Long blobId) {
        this.blobId = blobId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(Long uploaderId) {
        this.uploaderId = uploaderId;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public Long getBizId() {
        return bizId;
    }

    public void setBizId(Long bizId) {
        this.bizId = bizId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
