package com.qqlin.oa.common;

public class Result<T> {
    Integer code;
    String message;
    T data;

    public Result(){}
    public Result(Integer code, String message, T data) {
        this.message = message;
        this.code = code;
        this.data = data;
    }
    public static <T> Result<T> success(){
        return new Result<>(200,"操作成功",null);
    }
    public static <T> Result<T> success(T data){
        return new Result<>(200,"操作成功",data);
    }
    public static <T> Result<T> fail(Integer code,String message){
        return new Result<>(code,message,null);
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}