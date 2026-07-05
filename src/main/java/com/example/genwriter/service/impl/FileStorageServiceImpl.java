package com.example.genwriter.service.impl;

import com.example.genwriter.event.AttachmentDeletedEvent;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.MessageAttachmentMapper;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.entity.MessageAttachment;
import com.example.genwriter.service.FileStorageService;
import com.example.genwriter.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;

/**
 * 文件存储服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private final MessageAttachmentMapper messageAttachmentMapper;
    private final SseService sseService;
    private final ApplicationEventPublisher eventPublisher;

    // 上传速率限制：跟踪每会话每分钟上传次数
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> uploadCounters = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${app.upload.path:.uploads}")
    private String uploadBasePath;

    @Value("${app.attachment.max-image-size:20971520}")
    private long maxImageSize;

    @Value("${app.attachment.max-document-size:52428800}")
    private long maxDocumentSize;

    @Value("${app.attachment.max-session-storage:209715200}")
    private long maxSessionStorage;

    @Value("${app.attachment.allowed-image-extensions:jpg,jpeg,png,gif,webp}")
    private String allowedImageExtensions;

    @Value("${app.attachment.allowed-document-extensions:pdf,doc,docx,txt,md,csv,json,xml}")
    private String allowedDocumentExtensions;

    private static final Map<String, byte[]> MAGIC_BYTES = new LinkedHashMap<>();

    static {
        MAGIC_BYTES.put("jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        MAGIC_BYTES.put("png", new byte[]{(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47});
        MAGIC_BYTES.put("gif", new byte[]{(byte) 0x47, (byte) 0x49, (byte) 0x46, (byte) 0x38});
        MAGIC_BYTES.put("pdf", new byte[]{(byte) 0x25, (byte) 0x50, (byte) 0x44, (byte) 0x2D});
        MAGIC_BYTES.put("docx", new byte[]{(byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04});
    }

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json", "xml");

    @Override
    public MessageAttachment storeFile(MultipartFile file, String sessionId) {
        // 0. 检查上传速率限制
        if (!checkUploadRateLimit(sessionId)) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_UPLOAD_LIMIT);
        }

        // 1. 基本验证
        if (file == null || file.isEmpty()) {
            throw new BizException(BizException.ErrorCode.PARAM_ERROR.getCode(), "上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BizException(BizException.ErrorCode.PARAM_ERROR.getCode(), "文件名不能为空");
        }

        // 2. 提取并验证扩展名
        String extension = getFileExtension(originalFilename).toLowerCase();
        String attachmentType = determineAttachmentType(extension);
        if (attachmentType == null) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
        }

        // 3. 验证文件大小
        validateFileSize(file.getSize(), attachmentType);

        // 4. 验证Magic Bytes
        validateMagicBytes(file, extension);

        // 5. 检查会话存储配额
        long currentUsage = getSessionStorageUsage(sessionId);
        if (currentUsage + file.getSize() > maxSessionStorage) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_QUOTA_EXCEEDED);
        }

        // 6. 生成存储文件名和路径
        String storedFilename = UUID.randomUUID().toString() + "." + extension;
        Path sessionDir = Paths.get(uploadBasePath, sessionId);
        Path filePath = sessionDir.resolve(storedFilename);

        try {
            // 7. 创建目录并保存文件
            Files.createDirectories(sessionDir);
            file.transferTo(filePath.toFile());
        } catch (IOException e) {
            log.error("文件保存失败: sessionId={}, filename={}", sessionId, originalFilename, e);
            throw new BizException(BizException.ErrorCode.SYSTEM_ERROR.getCode(), "文件保存失败");
        }

        // 8. 构建附件实体
        String mimeType = file.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = detectMimeType(extension);
        }

        MessageAttachment attachment = MessageAttachment.builder()
                .sessionId(sessionId)
                .originalFilename(originalFilename)
                .storedFilename(storedFilename)
                .filePath(filePath.toString())
                .fileSize(file.getSize())
                .mimeType(mimeType)
                .attachmentType(attachmentType)
                .processingStatus("PENDING")
                .metadata("{}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // 9. 保存到数据库
        messageAttachmentMapper.insert(attachment);

        // 10. 异步处理: 图片生成缩略图, 文档提取文本
        if ("IMAGE".equals(attachmentType)) {
            asyncGenerateThumbnail(attachment);
        } else if ("DOCUMENT".equals(attachmentType)) {
            asyncExtractText(attachment);
        } else {
            // 其他文件直接标记完成
            attachment.setProcessingStatus("COMPLETED");
            messageAttachmentMapper.updateById(attachment);
        }

        return attachment;
    }

    @Override
    public String getFilePath(String storedFilename) {
        return Paths.get(uploadBasePath, storedFilename).toString();
    }

    @Override
    public String getThumbnailPath(String attachmentId) {
        MessageAttachment attachment = getById(attachmentId);
        if (attachment == null) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_NOT_FOUND);
        }
        return attachment.getThumbnailPath();
    }

    @Override
    public void deleteFile(String attachmentId) {
        MessageAttachment attachment = getById(attachmentId);
        if (attachment == null) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_NOT_FOUND);
        }

        // 发布附件删除事件，由事件监听器删除物理文件
        eventPublisher.publishEvent(new AttachmentDeletedEvent(
                attachment.getFilePath(), attachment.getThumbnailPath()));

        // 删除数据库记录
        messageAttachmentMapper.deleteById(attachmentId);
    }

    @Override
    public List<MessageAttachment> getByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return messageAttachmentMapper.findByIds(ids);
    }

    @Override
    public MessageAttachment getById(String id) {
        MessageAttachment attachment = messageAttachmentMapper.selectById(id);
        if (attachment == null) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_NOT_FOUND);
        }
        return attachment;
    }

    @Override
    public List<MessageAttachment> getBySessionId(String sessionId) {
        return messageAttachmentMapper.selectBySessionId(sessionId);
    }

    @Override
    public long getSessionStorageUsage(String sessionId) {
        return messageAttachmentMapper.sumFileSizeBySessionId(sessionId);
    }

    /**
     * 异步生成缩略图
     */
    @Async("taskExecutor")
    protected void asyncGenerateThumbnail(MessageAttachment attachment) {
        try {
            attachment.setProcessingStatus("PROCESSING");
            messageAttachmentMapper.updateById(attachment);

            Path imagePath = Paths.get(attachment.getFilePath());
            if (!Files.exists(imagePath)) {
                log.warn("图片文件不存在: {}", imagePath);
                attachment.setProcessingStatus("FAILED");
                messageAttachmentMapper.updateById(attachment);
                return;
            }

            BufferedImage originalImage = ImageIO.read(imagePath.toFile());
            if (originalImage == null) {
                log.warn("无法读取图片: {}", imagePath);
                attachment.setProcessingStatus("FAILED");
                messageAttachmentMapper.updateById(attachment);
                return;
            }

            // 更新图片尺寸信息
            attachment.setWidth(originalImage.getWidth());
            attachment.setHeight(originalImage.getHeight());
            messageAttachmentMapper.updateById(attachment);

            // 生成缩略图(最大边256px)
            int maxEdge = 256;
            int originalWidth = originalImage.getWidth();
            int originalHeight = originalImage.getHeight();
            double scale = Math.min((double) maxEdge / originalWidth, (double) maxEdge / originalHeight);

            if (scale >= 1.0) {
                // 图片已经足够小,不需要缩略图
                attachment.setProcessingStatus("COMPLETED");
                messageAttachmentMapper.updateById(attachment);
                return;
            }

            int thumbWidth = (int) (originalWidth * scale);
            int thumbHeight = (int) (originalHeight * scale);

            BufferedImage thumbnail = new BufferedImage(thumbWidth, thumbHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = thumbnail.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(originalImage, 0, 0, thumbWidth, thumbHeight, null);
            g2d.dispose();

            // 保存缩略图
            String thumbFilename = "thumb_" + attachment.getStoredFilename();
            Path thumbPath = Paths.get(uploadBasePath, attachment.getSessionId(), thumbFilename);
            ImageIO.write(thumbnail, "jpg", thumbPath.toFile());

            attachment.setThumbnailPath(thumbPath.toString());
            attachment.setProcessingStatus("COMPLETED");
            messageAttachmentMapper.updateById(attachment);

            log.debug("缩略图生成完成: attachmentId={}", attachment.getId());
        } catch (Exception e) {
            log.error("缩略图生成失败: attachmentId={}", attachment.getId(), e);
            attachment.setProcessingStatus("FAILED");
            try {
                messageAttachmentMapper.updateById(attachment);
            } catch (Exception ex) {
                log.error("更新附件状态失败: attachmentId={}", attachment.getId(), ex);
            }
        }
    }

    /**
     * 异步提取文档文本
     */
    @Async("taskExecutor")
    protected void asyncExtractText(MessageAttachment attachment) {
        try {
            attachment.setProcessingStatus("PROCESSING");
            messageAttachmentMapper.updateById(attachment);

            Path docPath = Paths.get(attachment.getFilePath());
            if (!Files.exists(docPath)) {
                log.warn("文档文件不存在: {}", docPath);
                attachment.setProcessingStatus("FAILED");
                attachment.setMetadata("{\"error\":\"文档文件不存在\"}");
                messageAttachmentMapper.updateById(attachment);
                return;
            }

            String extension = getFileExtension(attachment.getOriginalFilename()).toLowerCase();
            String extractedText;

            if (TEXT_EXTENSIONS.contains(extension)) {
                // 纯文本类型直接读取
                extractedText = Files.readString(docPath);
            } else {
                // 使用 Apache Tika 提取文本 (PDF, DOC, DOCX 等)
                try {
                    Tika tika = new Tika();
                    extractedText = tika.parseToString(docPath.toFile());
                } catch (Exception e) {
                    log.warn("Tika提取文本失败: attachmentId={}", attachment.getId(), e);
                    throw e;
                }
            }

            // 限制提取文本长度
            if (extractedText != null && extractedText.length() > 500000) {
                extractedText = extractedText.substring(0, 500000) + "...(文本过长,已截断)";
            }

            attachment.setExtractedText(extractedText);
            attachment.setProcessingStatus("COMPLETED");
            messageAttachmentMapper.updateById(attachment);

            log.debug("文本提取完成: attachmentId={}, textLength={}", attachment.getId(),
                    extractedText != null ? extractedText.length() : 0);

            // 发布SSE通知：附件处理完成
            publishAttachmentProcessingEvent(attachment);

        } catch (Exception e) {
            log.error("文本提取失败: attachmentId={}", attachment.getId(), e);
            attachment.setProcessingStatus("FAILED");
            try {
                String errorJson = "{\"error\":\"" + e.getMessage().replace("\"", "'").replace("\\", "/") + "\"}";
                attachment.setMetadata(errorJson);
            } catch (Exception ex) {
                attachment.setMetadata("{\"error\":\"文本提取失败\"}");
            }
            try {
                messageAttachmentMapper.updateById(attachment);
            } catch (Exception ex) {
                log.error("更新附件状态失败: attachmentId={}", attachment.getId(), ex);
            }
        }
    }

    /**
     * 发布附件处理完成的SSE事件
     */
    private void publishAttachmentProcessingEvent(MessageAttachment attachment) {
        try {
            sseService.publish(attachment.getSessionId(), SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .statusText("【附件处理】" + attachment.getOriginalFilename() + " 处理完成")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE附件处理通知推送失败: {}", e.getMessage());
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1);
    }

    /**
     * 判断附件类型
     */
    private String determineAttachmentType(String extension) {
        Set<String> imageExts = new HashSet<>(Arrays.asList(allowedImageExtensions.split(",")));
        Set<String> docExts = new HashSet<>(Arrays.asList(allowedDocumentExtensions.split(",")));

        if (imageExts.contains(extension)) {
            return "IMAGE";
        } else if (docExts.contains(extension)) {
            return "DOCUMENT";
        }
        return null;
    }

    /**
     * 验证文件大小
     */
    private void validateFileSize(long fileSize, String attachmentType) {
        if ("IMAGE".equals(attachmentType) && fileSize > maxImageSize) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }
        if ("DOCUMENT".equals(attachmentType) && fileSize > maxDocumentSize) {
            throw new BizException(BizException.ErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }
    }

    /**
     * 验证文件Magic Bytes
     */
    private void validateMagicBytes(MultipartFile file, String extension) {
        try {
            byte[] fileHeader = new byte[8];
            try (InputStream is = file.getInputStream()) {
                int bytesRead = is.read(fileHeader);
                if (bytesRead < 4) {
                    return;
                }
            }

            // 检查已知的Magic Bytes
            byte[] expectedBytes = MAGIC_BYTES.get(extension);
            if (expectedBytes != null) {
                for (int i = 0; i < expectedBytes.length; i++) {
                    if (fileHeader[i] != expectedBytes[i]) {
                        log.warn("文件Magic Bytes不匹配: extension={}, expected={}, actual={}",
                                extension, bytesToHex(expectedBytes), bytesToHex(fileHeader));
                        throw new BizException(BizException.ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED.getCode(),
                                "文件内容与扩展名不匹配");
                    }
                }
            }
        } catch (IOException e) {
            log.warn("读取文件头失败,跳过Magic Bytes验证: extension={}", extension, e);
        }
    }

    /**
     * 字节数组转十六进制字符串
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    /**
     * 根据扩展名推断MIME类型
     */
    private String detectMimeType(String extension) {
        return switch (extension.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            default -> "application/octet-stream";
        };
    }

    // ==================== 附件删除事件监听 ====================

    /**
     * 监听附件删除事件，删除物理文件
     */
    @EventListener
    public void handleAttachmentDeleted(AttachmentDeletedEvent event) {
        deletePhysicalFile(event.getFilePath());
        if (event.getThumbnailPath() != null && !event.getThumbnailPath().isEmpty()) {
            deletePhysicalFile(event.getThumbnailPath());
        }
    }

    /**
     * 删除物理文件
     */
    private void deletePhysicalFile(String filePath) {
        if (filePath == null || filePath.isEmpty()) return;
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
            java.nio.file.Files.deleteIfExists(path);
            log.debug("已删除物理文件: {}", filePath);
        } catch (Exception e) {
            log.warn("删除物理文件失败: {}", filePath, e);
        }
    }

    // ==================== 孤儿文件清理 ====================

    /**
     * 定时扫描并清理孤儿文件（每天凌晨3点执行）
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOrphanFiles() {
        log.info("开始扫描孤儿文件...");
        try {
            java.nio.file.Path uploadDir = java.nio.file.Paths.get(uploadBasePath);
            if (!java.nio.file.Files.exists(uploadDir)) {
                return;
            }

            long deletedCount = 0;
            long totalSize = 0;

            try (var stream = java.nio.file.Files.walk(uploadDir)) {
                var files = stream.filter(java.nio.file.Files::isRegularFile).toList();
                for (java.nio.file.Path file : files) {
                    String storedFilename = file.getFileName().toString();
                    // 跳过缩略图（随父文件一起管理）
                    if (storedFilename.startsWith("thumb_")) continue;

                    String absolutePath = file.toAbsolutePath().toString();
                    boolean isOrphan = isOrphanFile(file, absolutePath);
                    if (isOrphan) {
                        long size = java.nio.file.Files.size(file);
                        java.nio.file.Files.deleteIfExists(file);
                        // 同时删除缩略图
                        java.nio.file.Path thumbPath = file.resolveSibling("thumb_" + storedFilename);
                        java.nio.file.Files.deleteIfExists(thumbPath);
                        deletedCount++;
                        totalSize += size;
                        log.debug("删除孤儿文件: {}", absolutePath);
                    }
                }
            }

            if (deletedCount > 0) {
                log.info("孤儿文件清理完成: 删除 {} 个文件, 释放 {} 字节", deletedCount, totalSize);
            } else {
                log.info("未发现孤儿文件");
            }
        } catch (Exception e) {
            log.error("孤儿文件清理失败", e);
        }
    }

    /**
     * 判断文件是否为孤儿文件（数据库中无对应记录）
     */
    private boolean isOrphanFile(java.nio.file.Path file, String absolutePath) {
        try {
            // 通过文件所在目录名（session目录）查找该会话的附件
            java.nio.file.Path sessionDir = file.getParent();
            String sessionDirName = sessionDir.getFileName().toString();
            var sessionAttachments = messageAttachmentMapper.selectBySessionId(sessionDirName);
            if (sessionAttachments.isEmpty()) {
                // 该会话无附件记录，文件为孤儿
                return true;
            }
            // 检查该文件是否被某个附件引用
            for (var att : sessionAttachments) {
                if (absolutePath.equals(att.getFilePath()) ||
                    absolutePath.equals(att.getThumbnailPath())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.debug("检查孤儿文件失败: {}", e.getMessage());
            return false; // 无法确认时不删除
        }
    }

    // ==================== 上传速率限制 ====================

    /**
     * 检查上传速率限制（每会话每分钟最多20次上传）
     */
    private boolean checkUploadRateLimit(String sessionId) {
        String key = sessionId;
        var counter = uploadCounters.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count > 20) {
            return false;
        }
        // 首次计数时，1分钟后重置
        if (count == 1) {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor().schedule(
                    () -> uploadCounters.remove(key), 60, java.util.concurrent.TimeUnit.SECONDS);
        }
        return true;
    }
}
