package com.example.genwriter.mapper;

import com.example.genwriter.model.entity.Document;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 文档数据访问层
 */
@Mapper
public interface DocumentMapper {

    /**
     * 插入新文档
     *
     * @param document 文档实体
     * @return 影响行数
     */
    @Insert("INSERT INTO document " +
            "(session_id, title, type, content, format, version, status, metadata, created_at, updated_at) " +
            "VALUES (CAST(#{sessionId} AS uuid), #{title}, #{type}, #{content}, #{format}, " +
            "#{version}, #{status}, CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Document document);

    /**
     * 根据ID查询文档
     *
     * @param id 文档ID
     * @return 文档实体
     */
    @Select("SELECT id, session_id AS sessionId, title, type, content, format, version, status, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM document WHERE id = CAST(#{id} AS uuid)")
    Document selectById(String id);

    /**
     * 根据会话ID查询文档列表
     *
     * @param sessionId 会话ID
     * @return 文档列表
     */
    @Select("SELECT id, session_id AS sessionId, title, type, content, format, version, status, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM document WHERE session_id = CAST(#{sessionId} AS uuid) ORDER BY version DESC, updated_at DESC")
    List<Document> selectBySessionId(String sessionId);

    /**
     * 根据会话ID和类型查询文档
     *
     * @param sessionId 会话ID
     * @param type 文档类型
     * @return 文档列表
     */
    @Select("SELECT id, session_id AS sessionId, title, type, content, format, version, status, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM document WHERE session_id = CAST(#{sessionId} AS uuid) AND type = #{type} " +
            "ORDER BY version DESC")
    List<Document> selectBySessionIdAndType(@Param("sessionId") String sessionId, @Param("type") String type);

    /**
     * 查询会话的最新版本文档
     *
     * @param sessionId 会话ID
     * @return 文档实体
     */
    @Select("SELECT id, session_id AS sessionId, title, type, content, format, version, status, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM document WHERE session_id = CAST(#{sessionId} AS uuid) " +
            "ORDER BY version DESC LIMIT 1")
    Document selectLatestBySessionId(String sessionId);

    /**
     * 根据ID删除文档
     *
     * @param id 文档ID
     * @return 影响行数
     */
    @Delete("DELETE FROM document WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 根据会话ID删除所有文档
     *
     * @param sessionId 会话ID
     * @return 影响行数
     */
    @Delete("DELETE FROM document WHERE session_id = CAST(#{sessionId} AS uuid)")
    int deleteBySessionId(String sessionId);

    /**
     * 根据ID更新文档
     *
     * @param document 文档实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE document " +
            "<set>" +
            "<if test='title != null'>title = #{title},</if>" +
            "<if test='type != null'>type = #{type},</if>" +
            "<if test='content != null'>content = #{content},</if>" +
            "<if test='format != null'>format = #{format},</if>" +
            "<if test='version != null'>version = #{version},</if>" +
            "<if test='status != null'>status = #{status},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(Document document);

    /**
     * 获取会话中的最大版本号
     *
     * @param sessionId 会话ID
     * @return 最大版本号
     */
    @Select("SELECT COALESCE(MAX(version), 0) FROM document WHERE session_id = CAST(#{sessionId} AS uuid)")
    int getMaxVersionBySessionId(String sessionId);

    /**
     * 查询所有文档(按更新时间倒序)
     *
     * @return 文档列表
     */
    @Select("SELECT id, session_id AS sessionId, title, type, content, format, version, status, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM document ORDER BY updated_at DESC")
    List<Document> selectAll();

    /**
     * 根据状态查询文档
     *
     * @param status 文档状态
     * @return 文档列表
     */
    @Select("SELECT id, session_id AS sessionId, title, type, content, format, version, status, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM document WHERE status = #{status} ORDER BY updated_at DESC")
    List<Document> selectByStatus(String status);

    /**
     * 统计会话中的文档数量
     *
     * @param sessionId 会话ID
     * @return 文档数量
     */
    @Select("SELECT COUNT(*) FROM document WHERE session_id = CAST(#{sessionId} AS uuid)")
    long countBySessionId(String sessionId);
}
