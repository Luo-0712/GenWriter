package com.example.genwriter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.genwriter.model.entity.MessageAttachment;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 消息附件数据访问层
 */
@Mapper
public interface MessageAttachmentMapper extends BaseMapper<MessageAttachment> {

    /**
     * 插入新附件
     *
     * @param attachment 附件实体
     * @return 影响行数
     */
    @Insert("INSERT INTO message_attachment " +
            "(message_id, session_id, original_filename, stored_filename, file_path, file_size, " +
            "mime_type, attachment_type, width, height, thumbnail_path, extracted_text, " +
            "processing_status, metadata, created_at, updated_at) " +
            "VALUES (CAST(#{messageId} AS uuid), CAST(#{sessionId} AS uuid), " +
            "#{originalFilename}, #{storedFilename}, #{filePath}, #{fileSize}, " +
            "#{mimeType}, #{attachmentType}, #{width}, #{height}, #{thumbnailPath}, #{extractedText}, " +
            "#{processingStatus}, CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(MessageAttachment attachment);

    /**
     * 根据ID查询附件
     *
     * @param id 附件ID
     * @return 附件实体
     */
    @Select("SELECT id, message_id AS messageId, session_id AS sessionId, " +
            "original_filename AS originalFilename, stored_filename AS storedFilename, " +
            "file_path AS filePath, file_size AS fileSize, mime_type AS mimeType, " +
            "attachment_type AS attachmentType, width, height, " +
            "thumbnail_path AS thumbnailPath, extracted_text AS extractedText, " +
            "processing_status AS processingStatus, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message_attachment WHERE id = CAST(#{id} AS uuid)")
    MessageAttachment selectById(String id);

    /**
     * 根据消息ID查询附件列表
     *
     * @param messageId 消息ID
     * @return 附件列表
     */
    @Select("SELECT id, message_id AS messageId, session_id AS sessionId, " +
            "original_filename AS originalFilename, stored_filename AS storedFilename, " +
            "file_path AS filePath, file_size AS fileSize, mime_type AS mimeType, " +
            "attachment_type AS attachmentType, width, height, " +
            "thumbnail_path AS thumbnailPath, extracted_text AS extractedText, " +
            "processing_status AS processingStatus, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message_attachment WHERE message_id = CAST(#{messageId} AS uuid) " +
            "ORDER BY created_at ASC")
    List<MessageAttachment> selectByMessageId(String messageId);

    /**
     * 根据会话ID查询附件列表
     *
     * @param sessionId 会话ID
     * @return 附件列表
     */
    @Select("SELECT id, message_id AS messageId, session_id AS sessionId, " +
            "original_filename AS originalFilename, stored_filename AS storedFilename, " +
            "file_path AS filePath, file_size AS fileSize, mime_type AS mimeType, " +
            "attachment_type AS attachmentType, width, height, " +
            "thumbnail_path AS thumbnailPath, extracted_text AS extractedText, " +
            "processing_status AS processingStatus, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message_attachment WHERE session_id = CAST(#{sessionId} AS uuid) " +
            "ORDER BY created_at ASC")
    List<MessageAttachment> selectBySessionId(String sessionId);

    /**
     * 根据ID删除附件
     *
     * @param id 附件ID
     * @return 影响行数
     */
    @Delete("DELETE FROM message_attachment WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 根据会话ID删除所有附件
     *
     * @param sessionId 会话ID
     * @return 影响行数
     */
    @Delete("DELETE FROM message_attachment WHERE session_id = CAST(#{sessionId} AS uuid)")
    int deleteBySessionId(String sessionId);

    /**
     * 根据ID列表批量查询附件
     *
     * @param ids 附件ID列表
     * @return 附件列表
     */
    @Select("<script>" +
            "SELECT id, message_id AS messageId, session_id AS sessionId, " +
            "original_filename AS originalFilename, stored_filename AS storedFilename, " +
            "file_path AS filePath, file_size AS fileSize, mime_type AS mimeType, " +
            "attachment_type AS attachmentType, width, height, " +
            "thumbnail_path AS thumbnailPath, extracted_text AS extractedText, " +
            "processing_status AS processingStatus, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message_attachment WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>" +
            "CAST(#{id} AS uuid)" +
            "</foreach>" +
            "</script>")
    List<MessageAttachment> findByIds(@Param("ids") List<String> ids);

    /**
     * 根据ID更新附件
     *
     * @param attachment 附件实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE message_attachment " +
            "<set>" +
            "<if test='messageId != null'>message_id = CAST(#{messageId} AS uuid),</if>" +
            "<if test='sessionId != null'>session_id = CAST(#{sessionId} AS uuid),</if>" +
            "<if test='originalFilename != null'>original_filename = #{originalFilename},</if>" +
            "<if test='storedFilename != null'>stored_filename = #{storedFilename},</if>" +
            "<if test='filePath != null'>file_path = #{filePath},</if>" +
            "<if test='fileSize != null'>file_size = #{fileSize},</if>" +
            "<if test='mimeType != null'>mime_type = #{mimeType},</if>" +
            "<if test='attachmentType != null'>attachment_type = #{attachmentType},</if>" +
            "<if test='width != null'>width = #{width},</if>" +
            "<if test='height != null'>height = #{height},</if>" +
            "<if test='thumbnailPath != null'>thumbnail_path = #{thumbnailPath},</if>" +
            "<if test='extractedText != null'>extracted_text = #{extractedText},</if>" +
            "<if test='processingStatus != null'>processing_status = #{processingStatus},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(MessageAttachment attachment);

    /**
     * 统计会话的附件总大小
     *
     * @param sessionId 会话ID
     * @return 总文件大小(字节)
     */
    @Select("SELECT COALESCE(SUM(file_size), 0) FROM message_attachment WHERE session_id = CAST(#{sessionId} AS uuid)")
    long sumFileSizeBySessionId(String sessionId);
}
