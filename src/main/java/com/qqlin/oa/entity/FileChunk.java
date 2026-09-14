package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分片上传的一条记录：某个上传会话的第 N 个分片已经传上来了。
 *
 * 【它解决的是「断点续传」】
 * 前端在开始上传前先调一次 init 接口，服务端把这张表里已存在的分片序号返回给它，
 * 前端就只传缺失的那些。网络断了重来也不用从头开始。
 *
 * (upload_id, chunk_index) 上有唯一索引，这是并发安全的关键：
 * 同一个分片被重复上传（重试、网络重发）时，第二次会撞唯一键，
 * 而不是在磁盘上写出两份分片文件。
 */
@TableName("sys_file_chunk")
public class FileChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一次分片上传会话的 ID，由 init 接口生成 */
    private String uploadId;

    /** 分片序号，从 0 开始 */
    private Integer chunkIndex;

    /** 这个分片的大小（字节） */
    private Integer chunkSize;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
