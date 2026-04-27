package com.example.genwriter.mapper;

import com.example.genwriter.model.entity.KnowledgeBase;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 知识库数据访问层
 */
@Mapper
public interface KnowledgeBaseMapper {

    /**
     * 插入新知识库
     *
     * @param knowledgeBase 知识库实体
     * @return 影响行数
     */
    @Insert("INSERT INTO knowledge_base " +
            "(name, description, type, metadata, created_at, updated_at) " +
            "VALUES (#{name}, #{description}, #{type}, CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(KnowledgeBase knowledgeBase);

    /**
     * 根据ID查询知识库
     *
     * @param id 知识库ID
     * @return 知识库实体
     */
    @Select("SELECT id, name, description, type, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_base WHERE id = CAST(#{id} AS uuid)")
    KnowledgeBase selectById(String id);

    /**
     * 查询所有知识库(按更新时间倒序)
     *
     * @return 知识库列表
     */
    @Select("SELECT id, name, description, type, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_base ORDER BY updated_at DESC")
    List<KnowledgeBase> selectAll();

    /**
     * 根据类型查询知识库
     *
     * @param type 知识库类型
     * @return 知识库列表
     */
    @Select("SELECT id, name, description, type, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_base WHERE type = #{type} ORDER BY updated_at DESC")
    List<KnowledgeBase> selectByType(String type);

    /**
     * 根据ID批量查询知识库
     *
     * @param ids 知识库ID列表
     * @return 知识库列表
     */
    @Select("<script>" +
            "SELECT id, name, description, type, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_base WHERE id IN " +
            "<foreach item='id' collection='ids' separator=',' open='(' close=')'>" +
            "CAST(#{id} AS uuid)" +
            "</foreach>" +
            "</script>")
    List<KnowledgeBase> selectByIdBatch(@Param("ids") List<String> ids);

    /**
     * 根据ID删除知识库
     *
     * @param id 知识库ID
     * @return 影响行数
     */
    @Delete("DELETE FROM knowledge_base WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 根据ID更新知识库
     *
     * @param knowledgeBase 知识库实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE knowledge_base " +
            "<set>" +
            "<if test='name != null'>name = #{name},</if>" +
            "<if test='description != null'>description = #{description},</if>" +
            "<if test='type != null'>type = #{type},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(KnowledgeBase knowledgeBase);

    /**
     * 批量删除知识库
     *
     * @param ids 知识库ID列表
     * @return 影响行数
     */
    @Delete("<script>" +
            "DELETE FROM knowledge_base WHERE id IN " +
            "<foreach item='id' collection='ids' separator=',' open='(' close=')'>" +
            "CAST(#{id} AS uuid)" +
            "</foreach>" +
            "</script>")
    int deleteByIds(@Param("ids") List<String> ids);

    /**
     * 根据名称模糊查询知识库
     *
     * @param name 名称关键词
     * @return 知识库列表
     */
    @Select("SELECT id, name, description, type, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_base WHERE name LIKE CONCAT('%', #{name}, '%') " +
            "ORDER BY updated_at DESC")
    List<KnowledgeBase> selectByNameLike(String name);

    /**
     * 统计知识库数量
     *
     * @return 知识库总数
     */
    @Select("SELECT COUNT(*) FROM knowledge_base")
    long count();

    /**
     * 根据类型统计知识库数量
     *
     * @param type 知识库类型
     * @return 知识库数量
     */
    @Select("SELECT COUNT(*) FROM knowledge_base WHERE type = #{type}")
    long countByType(String type);
}
