package com.qqlin.oa.vo;

/**
 * 上传结果。
 *
 * instant 这个字段是给前端做提示用的：
 * 秒传时告诉用户「已秒传」，不然文件「瞬间传完」会让人以为出错了。
 */
public class FileUploadVO {

    private Long fileId;
    private String fileName;
    private Long fileSize;
    private String fileMd5;

    /** true 表示命中了秒传，磁盘上并没有真的再存一份 */
    private boolean instant;

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileMd5() {
        return fileMd5;
    }

    public void setFileMd5(String fileMd5) {
        this.fileMd5 = fileMd5;
    }

    public boolean isInstant() {
        return instant;
    }

    public void setInstant(boolean instant) {
        this.instant = instant;
    }
}
