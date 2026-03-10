package com.webtestpro.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应包装
 * 规范：code=0 成功，非 0 为业务错误码，所有接口必须使用 Result<T> 包装
 */
@Data
public class Result<T> implements Serializable {

    private int code;
    private String msg;
    private T data;
    private String traceId;

    private Result() {}

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.code = 0;
        result.msg = "success";
        result.data = data;
        return result;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = msg;
        return result;
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public boolean isSuccess() {
        return this.code == 0;
    }
}
