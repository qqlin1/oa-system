package com.qqlin.oa.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 分片上传的参数。init 和 complete 两个接口共用。
 *
 * fileMd5 由前端在浏览器里算好传过来 —— 这是秒传的前提：
 * 服务端必须在上传【之前】就知道文件内容的指纹，才能判断「这个文件我是不是已经有了」。
 */
public class MultipartUploadDTO {

    /** 分片会话 ID。init 时不用传（服务端会算），complete 时必填 */
    private String uploadId;

    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotBlank(message = "文件 MD5 不能为空")
    private String fileMd5;

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于 0")
    private Long fileSize;

    @NotNull(message = "分片数不能为空")
    @Min(value = 1, message = "分片数必须大于 0")
    private Integer totalChunks;

    /** 关联业务类型，可选，如 LEAVE */
    private String bizType;

    /** 关联业务 ID，可选 */
    private Long bizId;

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
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

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
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
}
