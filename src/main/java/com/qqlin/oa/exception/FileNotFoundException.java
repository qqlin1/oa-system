package com.qqlin.oa.exception;

/**
 * 文件记录不存在，或磁盘上的物理文件已经丢了。
 *
 * 注意和 java.io.FileNotFoundException 区分：那个是 IO 层的，
 * 这个是业务层的（数据库里查不到这条记录）。
 */
public class FileNotFoundException extends RuntimeException {

    public FileNotFoundException(String message) {
        super(message);
    }
}
