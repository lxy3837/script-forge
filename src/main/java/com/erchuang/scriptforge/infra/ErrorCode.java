package com.erchuang.scriptforge.infra;

/**
 * 错误码枚举，定义系统中所有可能的错误编码及对应消息.
 *
 * @author ScriptForge Team
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "success"),

    /** 参数校验失败 */
    PARAM_VALIDATION_ERROR(1001, "参数校验失败"),

    /** 资源不存在 */
    RESOURCE_NOT_FOUND(1002, "资源不存在"),

    /** Agent执行超时 */
    AGENT_TIMEOUT(2001, "Agent执行超时"),

    /** Agent执行失败（已达最大重试次数） */
    AGENT_EXECUTION_FAILED(2002, "Agent执行失败，已达最大重试次数"),

    /** DeepSeek API调用失败 */
    DEEPSEEK_API_ERROR(3001, "DeepSeek API调用失败"),

    /** Embedding服务异常 */
    EMBEDDING_SERVICE_ERROR(3002, "Embedding服务异常"),

    /** 文件导出失败 */
    EXPORT_FAILED(4001, "文件导出失败"),

    /** 磁盘空间不足 */
    DISK_SPACE_INSUFFICIENT(4002, "磁盘空间不足"),

    /** 未知系统异常 */
    SYSTEM_ERROR(5001, "未知系统异常"),

    /** 项目状态不允许当前操作 */
    PROJECT_STATUS_FORBIDDEN(1003, "项目状态不允许当前操作"),

    /** 知识库条目已存在 */
    KNOWLEDGE_ENTRY_EXISTS(1004, "知识库条目已存在"),

    /** LLM生成内容质量不达标 */
    QUALITY_NOT_MET(3003, "LLM生成内容质量不达标"),

    /** 大纲版本数量已达上限 */
    OUTLINE_VERSION_LIMIT(2003, "大纲版本数量已达上限"),

    /** 工作流步骤不匹配 */
    WORKFLOW_STEP_MISMATCH(2004, "工作流步骤不匹配");

    /** 错误码数值 */
    private final int code;

    /** 错误码描述 */
    private final String message;

    /**
     * 构造函数.
     *
     * @param code    错误码数值
     * @param message 错误码描述
     */
    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 根据错误码数值查找对应枚举.
     *
     * @param code 错误码数值
     * @return 对应的ErrorCode枚举，找不到则返回SYSTEM_ERROR
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode errorCode : values()) {
            if (errorCode.code == code) {
                return errorCode;
            }
        }
        return SYSTEM_ERROR;
    }
}
