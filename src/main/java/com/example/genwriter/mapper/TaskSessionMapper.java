package com.example.genwriter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.genwriter.model.entity.TaskSession;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 任务会话数据访问层
 */
@Mapper
public interface TaskSessionMapper extends BaseMapper<TaskSession> {

    /**
     * 插入新会话
     *
     * @param taskSession 会话实体
     * @return 影响行数
     */
    @Insert("<script>" +
            "INSERT INTO task_session " +
            "(<if test='projectId != null'>project_id,</if> title, type, status, topic, style, metadata, created_at, updated_at) " +
            "VALUES (<if test='projectId != null'>CAST(#{projectId} AS uuid),</if> #{title}, #{type}, #{status}, #{topic}, #{style}, " +
            "CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})" +
            "</script>")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(TaskSession taskSession);

    /**
     * 根据ID查询会话
     *
     * @param id 会话ID
     * @return 会话实体
     */
    @Select("SELECT id, project_id::text AS projectId, title, type, status, topic, style, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM task_session WHERE id = CAST(#{id} AS uuid)")
    TaskSession selectById(String id);

    /**
     * 查询所有会话(按更新时间倒序)
     *
     * @return 会话列表
     */
    @Select("SELECT id, project_id::text AS projectId, title, type, status, topic, style, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM task_session ORDER BY updated_at DESC")
    List<TaskSession> selectAll();

    /**
     * 根据状态查询会话
     *
     * @param status 会话状态
     * @return 会话列表
     */
    @Select("SELECT id, project_id::text AS projectId, title, type, status, topic, style, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM task_session WHERE status = #{status} ORDER BY updated_at DESC")
    List<TaskSession> selectByStatus(String status);

    /**
     * 根据类型查询会话
     *
     * @param type 会话类型
     * @return 会话列表
     */
    @Select("SELECT id, project_id::text AS projectId, title, type, status, topic, style, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM task_session WHERE type = #{type} ORDER BY updated_at DESC")
    List<TaskSession> selectByType(String type);

    /**
     * 根据ID删除会话
     *
     * @param id 会话ID
     * @return 影响行数
     */
    @Delete("DELETE FROM task_session WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 根据ID更新会话
     *
     * @param taskSession 会话实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE task_session " +
            "<set>" +
            "<if test='projectId != null'>project_id = CAST(#{projectId} AS uuid),</if>" +
            "<if test='title != null'>title = #{title},</if>" +
            "<if test='type != null'>type = #{type},</if>" +
            "<if test='status != null'>status = #{status},</if>" +
            "<if test='topic != null'>topic = #{topic},</if>" +
            "<if test='style != null'>style = #{style},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(TaskSession taskSession);

    /**
     * 批量删除会话
     *
     * @param ids 会话ID列表
     * @return 影响行数
     */
    @Delete("<script>" +
            "DELETE FROM task_session WHERE id IN " +
            "<foreach item='id' collection='ids' separator=',' open='(' close=')'>" +
            "CAST(#{id} AS uuid)" +
            "</foreach>" +
            "</script>")
    int deleteByIds(@Param("ids") List<String> ids);

    /**
     * 统计会话数量
     *
     * @return 会话总数
     */
    @Select("SELECT COUNT(*) FROM task_session")
    long count();

    /**
     * 根据状态统计会话数量
     *
     * @param status 会话状态
     * @return 会话数量
     */
    @Select("SELECT COUNT(*) FROM task_session WHERE status = #{status}")
    long countByStatus(String status);

    /**
     * 根据项目ID查询会话
     *
     * @param projectId 项目ID
     * @return 会话列表
     */
    @Select("SELECT id, project_id::text AS projectId, title, type, status, topic, style, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM task_session WHERE project_id = CAST(#{projectId} AS uuid) ORDER BY updated_at DESC")
    List<TaskSession> selectByProjectId(String projectId);

    /**
     * 根据项目ID统计会话数量
     *
     * @param projectId 项目ID
     * @return 会话数量
     */
    @Select("SELECT COUNT(*) FROM task_session WHERE project_id = CAST(#{projectId} AS uuid)")
    long countByProjectId(String projectId);
}
