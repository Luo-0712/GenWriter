package com.example.genwriter.mapper;

import com.example.genwriter.model.entity.KnowledgeChunk;
import com.example.genwriter.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.JdbcType;

import java.util.List;

/**
 * 知识片段数据访问层
 * 支持向量存储和相似度搜索
 */
@Mapper
public interface KnowledgeChunkMapper {

    /**
     * 插入新知识片段
     *
     * @param chunk 知识片段实体
     * @return 影响行数
     */
    @Insert("INSERT INTO knowledge_chunk " +
            "(kb_id, source_id, content, embedding, embedding_dimension, embedding_model, metadata, created_at, updated_at) " +
            "VALUES (CAST(#{kbId} AS uuid), #{sourceId}::uuid, #{content}, " +
            "#{embedding, typeHandler=com.example.genwriter.typehandler.PgVectorTypeHandler}::vector, " +
            "#{embeddingDimension}, #{embeddingModel}, CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(KnowledgeChunk chunk);

    /**
     * 根据ID查询知识片段
     *
     * @param id 片段ID
     * @return 知识片段实体
     */
    @Select("SELECT id, kb_id AS kbId, source_id AS sourceId, content, " +
            "embedding, embedding_dimension AS embeddingDimension, embedding_model AS embeddingModel, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_chunk WHERE id = CAST(#{id} AS uuid)")
    @Results({
            @Result(property = "embedding", column = "embedding", 
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    KnowledgeChunk selectById(String id);

    /**
     * 根据知识库ID查询所有片段
     *
     * @param kbId 知识库ID
     * @return 知识片段列表
     */
    @Select("SELECT id, kb_id AS kbId, source_id AS sourceId, content, " +
            "embedding, embedding_dimension AS embeddingDimension, embedding_model AS embeddingModel, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_chunk WHERE kb_id = CAST(#{kbId} AS uuid) " +
            "ORDER BY created_at DESC")
    @Results({
            @Result(property = "embedding", column = "embedding", 
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    List<KnowledgeChunk> selectByKbId(String kbId);

    /**
     * 根据源文档ID查询片段
     *
     * @param sourceId 源文档ID
     * @return 知识片段列表
     */
    @Select("SELECT id, kb_id AS kbId, source_id AS sourceId, content, " +
            "embedding, embedding_dimension AS embeddingDimension, embedding_model AS embeddingModel, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_chunk WHERE source_id = CAST(#{sourceId} AS uuid) " +
            "ORDER BY created_at ASC")
    @Results({
            @Result(property = "embedding", column = "embedding", 
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    List<KnowledgeChunk> selectBySourceId(String sourceId);

    /**
     * 向量相似度搜索
     * 使用PostgreSQL pgvector的<->操作符计算欧氏距离
     *
     * @param kbId 知识库ID
     * @param vectorLiteral 向量字符串表示
     * @param limit 返回结果数量限制
     * @return 相似的知识片段列表
     */
    @Select("SELECT id, kb_id AS kbId, source_id AS sourceId, content, " +
            "embedding, embedding_dimension AS embeddingDimension, embedding_model AS embeddingModel, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM knowledge_chunk WHERE kb_id = CAST(#{kbId} AS uuid) " +
            "ORDER BY embedding <-> CAST(#{vectorLiteral} AS vector) " +
            "LIMIT #{limit}")
    @Results({
            @Result(property = "embedding", column = "embedding", 
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    List<KnowledgeChunk> similaritySearch(@Param("kbId") String kbId, 
                                          @Param("vectorLiteral") String vectorLiteral, 
                                          @Param("limit") int limit);

    /**
     * 向量相似度搜索(带距离阈值)
     *
     * @param kbId 知识库ID
     * @param vectorLiteral 向量字符串表示
     * @param threshold 距离阈值
     * @param limit 返回结果数量限制
     * @return 相似的知识片段列表
     */
    @Select("SELECT id, kb_id AS kbId, source_id AS sourceId, content, " +
            "embedding, embedding_dimension AS embeddingDimension, embedding_model AS embeddingModel, " +
            "metadata::text AS metadata, created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt, " +
            "embedding <-> CAST(#{vectorLiteral} AS vector) AS distance " +
            "FROM knowledge_chunk WHERE kb_id = CAST(#{kbId} AS uuid) " +
            "AND embedding <-> CAST(#{vectorLiteral} AS vector) < #{threshold} " +
            "ORDER BY embedding <-> CAST(#{vectorLiteral} AS vector) " +
            "LIMIT #{limit}")
    @Results({
            @Result(property = "embedding", column = "embedding", 
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    List<KnowledgeChunk> similaritySearchWithThreshold(@Param("kbId") String kbId, 
                                                       @Param("vectorLiteral") String vectorLiteral,
                                                       @Param("threshold") double threshold, 
                                                       @Param("limit") int limit);

    /**
     * 根据ID删除知识片段
     *
     * @param id 片段ID
     * @return 影响行数
     */
    @Delete("DELETE FROM knowledge_chunk WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    /**
     * 根据知识库ID删除所有片段
     *
     * @param kbId 知识库ID
     * @return 影响行数
     */
    @Delete("DELETE FROM knowledge_chunk WHERE kb_id = CAST(#{kbId} AS uuid)")
    int deleteByKbId(String kbId);

    /**
     * 根据源文档ID删除片段
     *
     * @param sourceId 源文档ID
     * @return 影响行数
     */
    @Delete("DELETE FROM knowledge_chunk WHERE source_id = CAST(#{sourceId} AS uuid)")
    int deleteBySourceId(String sourceId);

    /**
     * 根据ID更新知识片段
     *
     * @param chunk 知识片段实体
     * @return 影响行数
     */
    @Update("<script>" +
            "UPDATE knowledge_chunk " +
            "<set>" +
            "<if test='kbId != null'>kb_id = CAST(#{kbId} AS uuid),</if>" +
            "<if test='sourceId != null'>source_id = CAST(#{sourceId} AS uuid),</if>" +
            "<if test='content != null'>content = #{content},</if>" +
            "<if test='embedding != null'>embedding = #{embedding, typeHandler=com.example.genwriter.typehandler.PgVectorTypeHandler}::vector,</if>" +
            "<if test='embeddingDimension != null'>embedding_dimension = #{embeddingDimension},</if>" +
            "<if test='embeddingModel != null'>embedding_model = #{embeddingModel},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(KnowledgeChunk chunk);

    /**
     * 统计知识库中的片段数量
     *
     * @param kbId 知识库ID
     * @return 片段数量
     */
    @Select("SELECT COUNT(*) FROM knowledge_chunk WHERE kb_id = CAST(#{kbId} AS uuid)")
    long countByKbId(String kbId);

    /**
     * 批量插入知识片段
     *
     * @param chunks 知识片段列表
     * @return 影响行数
     */
    @Insert("<script>" +
            "INSERT INTO knowledge_chunk " +
            "(kb_id, source_id, content, embedding, embedding_dimension, embedding_model, metadata, created_at, updated_at) " +
            "VALUES " +
            "<foreach collection='chunks' item='chunk' separator=','>" +
            "(CAST(#{chunk.kbId} AS uuid), #{chunk.sourceId}::uuid, #{chunk.content}, " +
            "#{chunk.embedding, typeHandler=com.example.genwriter.typehandler.PgVectorTypeHandler}::vector, " +
            "#{chunk.embeddingDimension}, #{chunk.embeddingModel}, " +
            "CAST(#{chunk.metadata} AS jsonb), #{chunk.createdAt}, #{chunk.updatedAt})" +
            "</foreach>" +
            "</script>")
    int batchInsert(@Param("chunks") List<KnowledgeChunk> chunks);
}
