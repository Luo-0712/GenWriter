package com.example.genwriter.mapper;

import com.example.genwriter.model.entity.Message;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 消息数据访问层
 */
@Mapper
public interface MessageMapper {

    /**
     * 插入新消息
     *
     * @param message 消息实体
     * @return 影响行数
     */
    @Insert("INSERT INTO message " +
            "(session_id, role, type, content, metadata, parent_id, sequence, created_at, updated_at) " +
            "VALUES (CAST(#{sessionId} AS uuid), #{role}, #{type}, #{content}, " +
            "CAST(#{metadata} AS jsonb), " +
            "#{parentId}::uuid, #{sequence}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Message message);

    /**
     * 根据ID查询消息
     *
     * @param id 消息ID
     * @return 消息实体
     */
    @Select("SELECT id, session_id AS sessionId, role, type, content, metadata::text AS metadata, " +
            "parent_id AS parentId, sequence, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message WHERE id = CAST(#{id} AS uuid)")
    Message selectById(String id);

    /**
     * 根据会话ID查询消息列表(按序号升序)
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    @Select("SELECT id, session_id AS sessionId, role, type, content, metadata::text AS metadata, " +
            "parent_id AS parentId, sequence, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message WHERE session_id = CAST(#{sessionId} AS uuid) ORDER BY sequence ASC, created_at ASC")
    List<Message> selectBySessionId(String sessionId);

    /**
     * 根据会话ID查询最近N条消息
     *
     * @param sessionId 会话ID
     * @param limit 限制数量
     * @return 消息列表
     */
    @Select("SELECT id, session_id AS sessionId, role, type, content, metadata::text AS metadata, " +
            "parent_id AS parentId, sequence, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message WHERE session_id = CAST(#{sessionId} AS uuid) " +
            "ORDER BY sequence DESC, created_at DESC LIMIT #{limit}")
    List<Message> selectBySessionIdRecently(@Param("sessionId") String sessionId, @Param("limit") int limit);

    /**
     * 根据会话ID和角色查询消息
     *
     * @param sessionId 会话ID
     * @param role 消息角色
     * @return 消息列表
     */
    @Select("SELECT id, session_id AS sessionId, role, type, content, metadata::text AS metadata, " +
            "parent_id AS parentId, sequence, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message WHERE session_id = CAST(#{sessionId} AS uuid) AND role = #{role} " +
            "ORDER BY sequence ASC")
    List<Message> selectBySessionIdAndRole(@Param("sessionId") String sessionId, @Param("role") String role);

    /**
     * 根据ID删除消息
     *
     * @param id 消息ID
     * @return 影响行数
     */
    @Delete("DELETE FROM message WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 根据会话ID删除所有消息
     *
     * @param sessionId 会话ID
     * @return 影响行数
     */
    @Delete("DELETE FROM message WHERE session_id = CAST(#{sessionId} AS uuid)")
    int deleteBySessionId(String sessionId);

    /**
     * 根据ID更新消息
     *
     * @param message 消息实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE message " +
            "<set>" +
            "<if test='role != null'>role = #{role},</if>" +
            "<if test='type != null'>type = #{type},</if>" +
            "<if test='content != null'>content = #{content},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "<if test='parentId != null'>parent_id = CAST(#{parentId} AS uuid),</if>" +
            "<if test='sequence != null'>sequence = #{sequence},</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(Message message);

    /**
     * 获取会话中的最大序号
     *
     * @param sessionId 会话ID
     * @return 最大序号
     */
    @Select("SELECT COALESCE(MAX(sequence), 0) FROM message WHERE session_id = CAST(#{sessionId} AS uuid)")
    int getMaxSequenceBySessionId(String sessionId);

    /**
     * 统计会话中的消息数量
     *
     * @param sessionId 会话ID
     * @return 消息数量
     */
    @Select("SELECT COUNT(*) FROM message WHERE session_id = CAST(#{sessionId} AS uuid)")
    long countBySessionId(String sessionId);

    /**
     * 根据父消息ID查询回复消息
     *
     * @param parentId 父消息ID
     * @return 消息列表
     */
    @Select("SELECT id, session_id AS sessionId, role, type, content, metadata::text AS metadata, " +
            "parent_id AS parentId, sequence, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM message WHERE parent_id = CAST(#{parentId} AS uuid) ORDER BY sequence ASC")
    List<Message> selectByParentId(String parentId);
}
