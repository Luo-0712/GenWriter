package com.example.genwriter.controller;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.entity.MessageAttachment;
import com.example.genwriter.model.vo.MessageAttachmentVO;
import com.example.genwriter.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件上传控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileStorageService fileStorageService;

    /**
     * 上传文件
     * TODO: 可添加上传频率限制(如基于Redis的令牌桶限流)
     */
    @PostMapping("/upload")
    public ApiResponse<MessageAttachmentVO> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sessionId") String sessionId) {
        log.debug("文件上传请求: sessionId={}, filename={}, size={}", sessionId, file.getOriginalFilename(), file.getSize());
        MessageAttachment attachment = fileStorageService.storeFile(file, sessionId);
        return ApiResponse.success(convertToVO(attachment));
    }

    /**
     * 获取附件信息
     */
    @GetMapping("/{id}")
    public ApiResponse<MessageAttachmentVO> getAttachment(@PathVariable String id) {
        log.debug("查询附件: {}", id);
        MessageAttachment attachment = fileStorageService.getById(id);
        return ApiResponse.success(convertToVO(attachment));
    }

    /**
     * 获取文件流
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> getFile(@PathVariable String id) {
        log.debug("获取文件流: {}", id);
        MessageAttachment attachment = fileStorageService.getById(id);

        Path filePath = Paths.get(attachment.getFilePath());
        if (!Files.exists(filePath)) {
            log.warn("文件不存在: {}", filePath);
            return ResponseEntity.notFound().build();
        }

        try {
            File file = filePath.toFile();
            InputStreamResource resource = new InputStreamResource(new FileInputStream(file));

            String encodedFilename = URLEncoder.encode(attachment.getOriginalFilename(), StandardCharsets.UTF_8)
                    .replace("+", "%20");

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename*=UTF-8''" + encodedFilename)
                    .contentType(MediaType.parseMediaType(
                            attachment.getMimeType() != null ? attachment.getMimeType() : "application/octet-stream"))
                    .contentLength(file.length())
                    .body(resource);
        } catch (IOException e) {
            log.error("读取文件失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取缩略图
     * 如果缩略图尚未生成,返回202 Accepted并附带Retry-After头
     */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<?> getThumbnail(@PathVariable String id) {
        log.debug("获取缩略图: {}", id);
        MessageAttachment attachment = fileStorageService.getById(id);

        // 非图片类型没有缩略图
        if (!"IMAGE".equals(attachment.getAttachmentType())) {
            return ResponseEntity.badRequest().build();
        }

        // 缩略图尚未生成
        if (attachment.getThumbnailPath() == null || attachment.getProcessingStatus() == null
                || "PENDING".equals(attachment.getProcessingStatus())
                || "PROCESSING".equals(attachment.getProcessingStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header("Retry-After", "2")
                    .build();
        }

        // 处理失败
        if ("FAILED".equals(attachment.getProcessingStatus())) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }

        Path thumbPath = Paths.get(attachment.getThumbnailPath());
        if (!Files.exists(thumbPath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            File thumbFile = thumbPath.toFile();
            InputStreamResource resource = new InputStreamResource(new FileInputStream(thumbFile));

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(thumbFile.length())
                    .body(resource);
        } catch (IOException e) {
            log.error("读取缩略图失败: id={}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 删除附件
     * 通过sessionId进行所有权校验
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteAttachment(
            @PathVariable String id,
            @RequestParam("sessionId") String sessionId) {
        log.debug("删除附件: id={}, sessionId={}", id, sessionId);
        MessageAttachment attachment = fileStorageService.getById(id);

        // 所有权校验
        if (!sessionId.equals(attachment.getSessionId())) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_SESSION_MISMATCH);
        }

        fileStorageService.deleteFile(id);
        return ApiResponse.success(null);
    }

    /**
     * 获取会话的所有附件
     */
    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<MessageAttachmentVO>> getSessionAttachments(@PathVariable String sessionId) {
        log.debug("查询会话附件: {}", sessionId);
        List<MessageAttachment> attachments = fileStorageService.getBySessionId(sessionId);
        List<MessageAttachmentVO> vos = attachments.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 实体转换为VO
     */
    private MessageAttachmentVO convertToVO(MessageAttachment entity) {
        return MessageAttachmentVO.fromEntity(entity);
    }
}
