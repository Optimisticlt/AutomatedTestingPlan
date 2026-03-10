package com.webtestpro.common.exception;

import com.webtestpro.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常
 * 使用：throw new BizException(ErrorCode.RESOURCE_NOT_FOUND)
 *       throw new BizException(ErrorCode.PARAM_ERROR, "projectId 不能为空")
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
