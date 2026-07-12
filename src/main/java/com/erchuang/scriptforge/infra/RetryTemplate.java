package com.erchuang.scriptforge.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Agent重试模板，提供统一的执行重试机制.
 * <p>
 * 当Agent调用DeepSeek或其他外部服务失败时，自动进行重试，最多重试3次。
 * 每次重试间隔递增（1秒、3秒、5秒）。
 * </p>
 *
 * @author ScriptForge Team
 */
public final class RetryTemplate {

    private static final Logger log = LoggerFactory.getLogger(RetryTemplate.class);

    /** 默认最大重试次数 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /** 重试间隔（毫秒） */
    private static final long[] RETRY_DELAYS = {1000L, 3000L, 5000L};

    private RetryTemplate() {
        // 工具类，禁止实例化
    }

    /**
     * 使用默认重试次数（3次）执行任务.
     *
     * @param <T>      返回类型
     * @param task     待执行的任务
     * @param taskName 任务名称（用于日志）
     * @return 任务执行结果
     * @throws BusinessException 当所有重试均失败时抛出
     */
    public static <T> T execute(Callable<T> task, String taskName) {
        return execute(task, taskName, DEFAULT_MAX_RETRIES, null);
    }

    /**
     * 使用指定重试次数执行任务.
     *
     * @param <T>       返回类型
     * @param task      待执行的任务
     * @param taskName  任务名称（用于日志）
     * @param maxRetries 最大重试次数
     * @return 任务执行结果
     * @throws BusinessException 当所有重试均失败时抛出
     */
    public static <T> T execute(Callable<T> task, String taskName, int maxRetries) {
        return execute(task, taskName, maxRetries, null);
    }

    /**
     * 使用指定重试次数执行任务，并在重试失败时触发回调.
     *
     * @param <T>          返回类型
     * @param task         待执行的任务
     * @param taskName     任务名称（用于日志）
     * @param maxRetries   最大重试次数
     * @param failureCallback 最终失败时的回调（可为null）
     * @return 任务执行结果
     * @throws BusinessException 当所有重试均失败时抛出
     */
    public static <T> T execute(Callable<T> task, String taskName, int maxRetries,
                                 Consumer<Exception> failureCallback) {
        Exception lastException = null;
        int actualMaxRetries = Math.min(maxRetries, RETRY_DELAYS.length);

        for (int attempt = 0; attempt <= actualMaxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("{} - 第{}次重试", taskName, attempt);
                }
                T result = task.call();
                if (attempt > 0) {
                    log.info("{} - 重试成功", taskName);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                if (attempt < actualMaxRetries) {
                    long delay = RETRY_DELAYS[attempt];
                    log.warn("{} - 执行失败，将在{}ms后进行第{}次重试: {}",
                            taskName, delay, attempt + 1, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED,
                                taskName + " - 重试被中断", ie);
                    }
                }
            }
        }

        String errorMsg = taskName + " - 已达最大重试次数(" + actualMaxRetries + "次)";
        log.error(errorMsg, lastException);

        if (failureCallback != null && lastException != null) {
            failureCallback.accept(lastException);
        }

        throw new BusinessException(ErrorCode.AGENT_EXECUTION_FAILED,
                errorMsg + ": " + (lastException != null ? lastException.getMessage() : "未知错误"),
                lastException);
    }

    /**
     * 执行无返回值的任务（Runnable包装）.
     *
     * @param task     待执行的任务
     * @param taskName 任务名称
     */
    public static void executeRunnable(Runnable task, String taskName) {
        execute(() -> {
            task.run();
            return null;
        }, taskName);
    }
}
