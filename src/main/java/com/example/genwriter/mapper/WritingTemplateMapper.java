package com.example.genwriter.mapper;

import com.example.genwriter.model.entity.WritingTemplate;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 写作模板数据访问层
 */
@Mapper
public interface WritingTemplateMapper {

    /**
     * 插入新模板
     *
     * @param template 模板实体
     * @return 影响行数
     */
    @Insert("INSERT INTO writing_template " +
            "(name, description, type, category, content, variables, example, is_system, usage_count, metadata, created_at, updated_at) " +
            "VALUES (#{name}, #{description}, #{type}, #{category}, #{content}, " +
            "CAST(#{variables} AS jsonb), #{example}, #{isSystem}, #{usageCount}, " +
            "CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(WritingTemplate template);

    /**
     * 根据ID查询模板
     *
     * @param id 模板ID
     * @return 模板实体
     */
    @Select("SELECT id, name, description, type, category, content, variables::text AS variables, " +
            "example, is_system AS isSystem, usage_count AS usageCount, metadata::text AS metadata, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM writing_template WHERE id = CAST(#{id} AS uuid)")
    WritingTemplate selectById(String id);

    /**
     * 查询所有模板(按使用次数倒序)
     *
     * @return 模板列表
     */
    @Select("SELECT id, name, description, type, category, content, variables::text AS variables, " +
            "example, is_system AS isSystem, usage_count AS usageCount, metadata::text AS metadata, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM writing_template ORDER BY usage_count DESC, updated_at DESC")
    List<WritingTemplate> selectAll();

    /**
     * 根据类型查询模板
     *
     * @param type 模板类型
     * @return 模板列表
     */
    @Select("SELECT id, name, description, type, category, content, variables::text AS variables, " +
            "example, is_system AS isSystem, usage_count AS usageCount, metadata::text AS metadata, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM writing_template WHERE type = #{type} ORDER BY usage_count DESC")
    List<WritingTemplate> selectByType(String type);

    /**
     * 根据分类查询模板
     *
     * @param category 模板分类
     * @return 模板列表
     */
    @Select("SELECT id, name, description, type, category, content, variables::text AS variables, " +
            "example, is_system AS isSystem, usage_count AS usageCount, metadata::text AS metadata, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM writing_template WHERE category = #{category} ORDER BY usage_count DESC")
    List<WritingTemplate> selectByCategory(String category);

    /**
     * 查询系统模板
     *
     * @return 模板列表
     */
    @Select("SELECT id, name, description, type, category, content, variables::text AS variables, " +
            "example, is_system AS isSystem, usage_count AS usageCount, metadata::text AS metadata, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM writing_template WHERE is_system = true ORDER BY usage_count DESC")
    List<WritingTemplate> selectSystemTemplates();

    /**
     * 根据ID删除模板
     *
     * @param id 模板ID
     * @return 影响行数
     */
    @Delete("DELETE FROM writing_template WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 根据ID更新模板
     *
     * @param template 模板实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE writing_template " +
            "<set>" +
            "<if test='name != null'>name = #{name},</if>" +
            "<if test='description != null'>description = #{description},</if>" +
            "<if test='type != null'>type = #{type},</if>" +
            "<if test='category != null'>category = #{category},</if>" +
            "<if test='content != null'>content = #{content},</if>" +
            "<if test='variables != null'>variables = CAST(#{variables} AS jsonb),</if>" +
            "<if test='example != null'>example = #{example},</if>" +
            "<if test='isSystem != null'>is_system = #{isSystem},</if>" +
            "<if test='usageCount != null'>usage_count = #{usageCount},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(WritingTemplate template);

    /**
     * 增加模板使用次数
     *
     * @param id 模板ID
     * @return 影响行数
     */
    @Update("UPDATE writing_template SET usage_count = usage_count + 1, updated_at = NOW() " +
            "WHERE id = CAST(#{id} AS uuid)")
    int incrementUsageCount(String id);

    /**
     * 根据名称模糊查询模板
     *
     * @param name 名称关键词
     * @return 模板列表
     */
    @Select("SELECT id, name, description, type, category, content, variables::text AS variables, " +
            "example, is_system AS isSystem, usage_count AS usageCount, metadata::text AS metadata, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM writing_template WHERE name LIKE CONCAT('%', #{name}, '%') " +
            "ORDER BY usage_count DESC")
    List<WritingTemplate> selectByNameLike(String name);

    /**
     * 统计模板数量
     *
     * @return 模板总数
     */
    @Select("SELECT COUNT(*) FROM writing_template")
    long count();

    /**
     * 根据类型统计模板数量
     *
     * @param type 模板类型
     * @return 模板数量
     */
    @Select("SELECT COUNT(*) FROM writing_template WHERE type = #{type}")
    long countByType(String type);
}
