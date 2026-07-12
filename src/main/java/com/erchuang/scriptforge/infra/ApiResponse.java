package com.erchuang.scriptforge.infra;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一API响应体，所有REST接口均使用此格式返回.
 *
 * <pre>
 * {
 *   "code": 0,
 *   "message": "success",
 *   "data": { },
 *   "timestamp": 1718700000000
 * }
 * </pre>
 *
 * @param <T> 响应数据的类型
 * @author ScriptForge Team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 状态码，0表示成功 */
    private int code;

    /** 响应消息 */
    private String message;

    /** 响应数据 */
    private T data;

    /** 响应时间戳（毫秒） */
    private long timestamp;

    /**
     * 创建成功响应（无数据）.
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(0, "success", null, System.currentTimeMillis());
    }

    /**
     * 创建成功响应（含数据）.
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, System.currentTimeMillis());
    }

    /**
     * 创建成功响应（自定义消息+数据）.
     *
     * @param message 成功消息
     * @param data    响应数据
     * @param <T>     数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(0, message, data, System.currentTimeMillis());
    }

    /**
     * 创建失败响应.
     *
     * @param errorCode 错误码枚举
     * @param <T>       数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null, System.currentTimeMillis());
    }

    /**
     * 创建失败响应（自定义消息）.
     *
     * @param errorCode 错误码枚举
     * @param message   自定义错误消息
     * @param <T>       数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, System.currentTimeMillis());
    }

    /**
     * 创建失败响应（自定义码+消息）.
     *
     * @param code    错误码
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, System.currentTimeMillis());
    }
}
