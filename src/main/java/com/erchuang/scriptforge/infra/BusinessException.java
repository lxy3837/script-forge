package com.erchuang.scriptforge.infra;

import lombok.Getter;

/**
 * 业务异常，用于在Service和Agent层抛出可识别的业务错误.
 * <p>
 * 配合全局异常处理器 {@code GlobalExceptionHandler} 统一转换为ApiResponse返回。
 * </p>
 *
 * @author ScriptForge Team
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 错误码 */
    private final int code;

    /**
     * 基于ErrorCode构造业务异常.
     *
     * @param errorCode 错误码枚举
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 基于ErrorCode和自定义消息构造业务异常.
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 基于ErrorCode、自定义消息和原因构造业务异常.
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param cause     原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }
}
