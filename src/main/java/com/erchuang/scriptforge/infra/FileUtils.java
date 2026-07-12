package com.erchuang.scriptforge.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件工具类，提供文件读写、目录管理、磁盘空间检查等通用操作.
 *
 * @author ScriptForge Team
 */
public final class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    /** 磁盘空间不足阈值（字节），默认2GB */
    private static final long MIN_FREE_SPACE_BYTES = 2L * 1024 * 1024 * 1024;

    private FileUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 确保目录存在，若不存在则创建.
     *
     * @param dirPath 目录路径
     * @throws BusinessException 如果目录创建失败
     */
    public static void ensureDirectoryExists(String dirPath) {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
                log.info("Created directory: {}", dirPath);
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "无法创建目录: " + dirPath, e);
            }
        }
    }

    /**
     * 读取文件全部内容为字符串.
     *
     * @param filePath 文件路径（支持classpath:前缀）
     * @return 文件内容
     * @throws BusinessException 如果读取失败
     */
    public static String readFileContent(String filePath) {
        try {
            Path path = Paths.get(filePath);
            return Files.readString(path);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "无法读取文件: " + filePath, e);
        }
    }

    /**
     * 写入字符串到文件（覆盖模式）.
     *
     * @param filePath 文件路径
     * @param content  文件内容
     * @throws BusinessException 如果写入失败
     */
    public static void writeFileContent(String filePath, String content) {
        try {
            Path path = Paths.get(filePath);
            ensureDirectoryExists(path.getParent().toString());
            Files.writeString(path, content);
            log.debug("File written: {}", filePath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "无法写入文件: " + filePath, e);
        }
    }

    /**
     * 写入字节数组到文件（覆盖模式）.
     *
     * @param filePath 文件路径
     * @param data     字节数据
     * @throws BusinessException 如果写入失败
     */
    public static void writeFileBytes(String filePath, byte[] data) {
        try {
            Path path = Paths.get(filePath);
            ensureDirectoryExists(path.getParent().toString());
            Files.write(path, data);
            log.debug("File written (bytes): {}", filePath);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "无法写入文件: " + filePath, e);
        }
    }

    /**
     * 检查磁盘剩余空间是否满足最低要求.
     *
     * @param filePath 要写入的文件路径（检查其所在分区的可用空间）
     * @throws BusinessException 如果剩余空间不足
     */
    public static void checkDiskSpace(String filePath) {
        Path path = Paths.get(filePath).toAbsolutePath();
        File file = path.toFile();
        // 如果文件不存在，逐级向上查找存在的父目录
        while (!file.exists()) {
            file = file.getParentFile();
        }
        long freeSpace = file.getFreeSpace();
        if (freeSpace < MIN_FREE_SPACE_BYTES) {
            throw new BusinessException(ErrorCode.DISK_SPACE_INSUFFICIENT,
                    String.format("磁盘空间不足，剩余 %.2f GB，需要至少 2 GB",
                            freeSpace / (1024.0 * 1024.0 * 1024.0)));
        }
    }

    /**
     * 删除文件或目录.
     *
     * @param filePath 文件或目录路径
     * @return 是否删除成功
     */
    public static boolean deleteFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", filePath, e);
            return false;
        }
    }

    /**
     * 获取文件扩展名（小写，不含点号）.
     *
     * @param fileName 文件名
     * @return 扩展名，若无扩展名则返回空字符串
     */
    public static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase();
    }

    /**
     * 获取不带扩展名的文件名.
     *
     * @param fileName 文件名
     * @return 不带扩展名的文件名
     */
    public static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0) {
            return fileName;
        }
        return fileName.substring(0, lastDot);
    }

    /**
     * 生成安全的文件名（移除非法字符）.
     *
     * @param fileName 原始文件名
     * @return 安全的文件名
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "untitled";
        }
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
