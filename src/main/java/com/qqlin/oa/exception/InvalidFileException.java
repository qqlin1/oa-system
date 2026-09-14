package com.qqlin.oa.exception;

/**
 * 上传的文件本身有问题：空文件、超出大小限制、扩展名不在白名单里、分片序号越界等。
 *
 * 对应 400 —— 是用户传的东西不对，不是服务端的错。
 */
public class InvalidFileException extends RuntimeException {

    public InvalidFileException(String message) {
        super(message);
    }
}
