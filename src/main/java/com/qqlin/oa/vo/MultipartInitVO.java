package com.qqlin.oa.vo;

import java.util.List;

/**
 * 分片上传初始化结果。
 *
 * 【两种可能】
 *   秒传命中：instant = true，fileId 有值，前端直接显示「上传完成」，一片都不用传。
 *   需要上传：instant = false，uploadId 有值，uploadedChunks 是【已经传过的分片序号】。
 *
 * 【断点续传就靠 uploadedChunks】
 * 前端拿这个列表和「总片数」一比，就知道还差哪几片。
 * 用户上次传到 30% 断了，这次只需要传剩下的 70%，不用从头开始。
 */
public class MultipartInitVO {

    /** 分片上传会话 ID。秒传时为空 */
    private String uploadId;

    /** true 表示秒传命中，不需要再传任何分片 */
    private boolean instant;

    /** 秒传命中时，指向已存在的文件记录 */
    private Long fileId;

    /** 已经上传成功的分片序号，升序 */
    private List<Integer> uploadedChunks;

    public String getUploadId() {
        return uploadId;
    }

    public void setUploadId(String uploadId) {
        this.uploadId = uploadId;
    }

    public boolean isInstant() {
        return instant;
    }

    public void setInstant(boolean instant) {
        this.instant = instant;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public List<Integer> getUploadedChunks() {
        return uploadedChunks;
    }

    public void setUploadedChunks(List<Integer> uploadedChunks) {
        this.uploadedChunks = uploadedChunks;
    }
}
