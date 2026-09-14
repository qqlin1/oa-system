package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 物理文件 —— 磁盘上真实存在的那一份。
 *
 * file_md5 上有唯一索引，这是「秒传」和「物理去重」的基础：
 * 同一个文件无论被多少人上传，磁盘上都只有一份。
 *
 * ref_count 记录它被多少条逻辑文件引用。删文件时不能直接删物理文件 ——
 * 别人可能还在用。只有引用数降到 0 才真正删磁盘文件。
 */
@TableName("sys_file_blob")
public class FileBlob {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件内容的 MD5（32 位十六进制小写） */
    private String fileMd5;

    private Long fileSize;

    private String contentType;

    /** 相对上传根目录的路径，如 ab/abcdef1234... */
    private String storagePath;

    /** 被多少条逻辑文件引用 */
    private Integer refCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileMd5() {
        return fileMd5;
    }

    public void setFileMd5(String fileMd5) {
        this.fileMd5 = fileMd5;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Integer getRefCount() {
        return refCount;
    }

    public void setRefCount(Integer refCount) {
        this.refCount = refCount;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
