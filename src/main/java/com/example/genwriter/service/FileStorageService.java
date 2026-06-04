package com.example.genwriter.service;

import com.example.genwriter.event.AttachmentDeletedEvent;
import com.example.genwriter.model.entity.MessageAttachment;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件存储服务接口
 */
public interface FileStorageService {

    /**
     * 存储上传文件
     *
     * @param file      上传的文件
     * @param sessionId 会话ID
     * @return 附件实体
     */
    MessageAttachment storeFile(MultipartFile file, String sessionId);

    /**
     * 获取文件存储路径
     *
     * @param storedFilename 存储文件名
     * @return 文件完整路径
     */
    String getFilePath(String storedFilename);

    /**
     * 获取缩略图路径
     *
     * @param attachmentId 附件ID
     * @return 缩略图完整路径
     */
    String getThumbnailPath(String attachmentId);

    /**
     * 删除附件(数据库和磁盘)
     *
     * @param attachmentId 附件ID
     */
    void deleteFile(String attachmentId);

    /**
     * 根据ID列表批量查询附件
     *
     * @param ids 附件ID列表
     * @return 附件列表
     */
    List<MessageAttachment> getByIds(List<String> ids);

    /**
     * 根据ID查询附件
     *
     * @param id 附件ID
     * @return 附件实体
     */
    MessageAttachment getById(String id);

    /**
     * 根据会话ID查询附件列表
     *
     * @param sessionId 会话ID
     * @return 附件列表
     */
    List<MessageAttachment> getBySessionId(String sessionId);

    /**
     * 获取会话存储使用量(字节)
     *
     * @param sessionId 会话ID
     * @return 总文件大小
     */
    long getSessionStorageUsage(String sessionId);

    /**
     * 清理孤儿文件（定时任务）
     */
    void cleanupOrphanFiles();

    /**
     * 监听附件删除事件，删除物理文件
     */
    void handleAttachmentDeleted(AttachmentDeletedEvent event);
}
