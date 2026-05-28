package com.example.genwriter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.genwriter.model.entity.Project;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 项目数据访问层
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 插入新项目
     *
     * @param project 项目实体
     * @return 影响行数
     */
    @Insert("INSERT INTO project " +
            "(name, description, status, metadata, created_at, updated_at) " +
            "VALUES (#{name}, #{description}, #{status}, " +
            "CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(Project project);

    /**
     * 根据ID查询项目
     *
     * @param id 项目ID
     * @return 项目实体
     */
    @Select("SELECT id, name, description, status, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM project WHERE id = CAST(#{id} AS uuid)")
    Project selectById(String id);

    /**
     * 查询所有项目(按更新时间倒序)
     *
     * @return 项目列表
     */
    @Select("SELECT id, name, description, status, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM project ORDER BY updated_at DESC")
    List<Project> selectAll();

    /**
     * 根据状态查询项目
     *
     * @param status 项目状态
     * @return 项目列表
     */
    @Select("SELECT id, name, description, status, metadata::text AS metadata, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM project WHERE status = #{status} ORDER BY updated_at DESC")
    List<Project> selectByStatus(String status);

    /**
     * 根据ID更新项目
     *
     * @param project 项目实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE project " +
            "<set>" +
            "<if test='name != null'>name = #{name},</if>" +
            "<if test='description != null'>description = #{description},</if>" +
            "<if test='status != null'>status = #{status},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(Project project);

    /**
     * 根据ID删除项目
     *
     * @param id 项目ID
     * @return 影响行数
     */
    @Delete("DELETE FROM project WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 批量删除项目
     *
     * @param ids 项目ID列表
     * @return 影响行数
     */
    @Delete("<script>" +
            "DELETE FROM project WHERE id IN " +
            "<foreach item='id' collection='ids' separator=',' open='(' close=')'>" +
            "CAST(#{id} AS uuid)" +
            "</foreach>" +
            "</script>")
    int deleteByIds(@Param("ids") List<String> ids);

    /**
     * 统计项目数量
     *
     * @return 项目总数
     */
    @Select("SELECT COUNT(*) FROM project")
    long count();
}
