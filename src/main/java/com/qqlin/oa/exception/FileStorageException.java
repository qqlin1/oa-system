package com.qqlin.oa.exception;

/**
 * 磁盘操作失败：写文件、合并分片、读文件出错。
 *
 * 这类错误是服务端的问题（磁盘满、权限不足、分片损坏），不是用户输入的问题，
 * 所以对应 500 而不是 400。
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
