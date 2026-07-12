package com.erchuang.scriptforge.config;

import com.erchuang.scriptforge.infra.ApiResponse;
import com.erchuang.scriptforge.infra.BusinessException;
import com.erchuang.scriptforge.infra.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器——统一转换各类异常为ApiResponse格式返回.
 *
 * @author ScriptForge Team
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business exception: code={}, message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    /**
     * 处理参数校验异常 (@Valid).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation error: {}", message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.PARAM_VALIDATION_ERROR, message));
    }

    /**
     * 处理约束违反异常.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.PARAM_VALIDATION_ERROR, ex.getMessage()));
    }

    /**
     * 处理非法参数异常.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.PARAM_VALIDATION_ERROR, ex.getMessage()));
    }

    /**
     * 处理所有未捕获的异常.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SYSTEM_ERROR, "服务器内部错误: " + ex.getMessage()));
    }
}
