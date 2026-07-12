package com.erchuang.scriptforge.web.controller;

import com.erchuang.scriptforge.infra.ApiResponse;
import com.erchuang.scriptforge.model.enums.ExportFormat;
import com.erchuang.scriptforge.service.ExportService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文档导出 REST 接口——文件导出与下载.
 *
 * @author ScriptForge Team
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 导出文档到指定格式.
     */
    @PostMapping
    public ApiResponse<String> export(@RequestBody ExportRequest request) {
        ExportFormat format = ExportFormat.valueOf(request.getFormat().toUpperCase());
        String resultPath = exportService.export(request.getContent(), format, request.getFileName());
        return ApiResponse.success("导出成功", "/api/export/download?path=" + resultPath);
    }

    /**
     * 下载导出文件.
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String path) {
        Path filePath = Paths.get(path);
        Resource resource = new FileSystemResource(filePath);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filePath.getFileName().toString() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    // ---- Request DTO ----

    @Data
    public static class ExportRequest {
        @NotBlank
        private String content;
        @NotBlank
        private String format;
        @NotBlank
        private String fileName;
    }
}
