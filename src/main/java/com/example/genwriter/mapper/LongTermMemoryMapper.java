package com.example.genwriter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.genwriter.model.entity.LongTermMemory;
import com.example.genwriter.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.JdbcType;

import java.util.List;

@Mapper
public interface LongTermMemoryMapper extends BaseMapper<LongTermMemory> {

    @Insert("INSERT INTO long_term_memory " +
            "(content, memory_type, scope, project_id, session_id, " +
            "embedding, embedding_model, importance, metadata, created_at, updated_at) " +
            "VALUES (#{content}, #{memoryType}, #{scope}, " +
            "CAST(#{projectId} AS uuid), CAST(#{sessionId} AS uuid), " +
            "#{embedding, typeHandler=com.example.genwriter.typehandler.PgVectorTypeHandler}::vector, " +
            "#{embeddingModel}, #{importance}, CAST(#{metadata} AS jsonb), #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(LongTermMemory memory);

    @Select("SELECT id, content, memory_type AS memoryType, scope, " +
            "project_id AS projectId, session_id AS sessionId, " +
            "embedding, embedding_model AS embeddingModel, importance, " +
            "metadata::text AS metadata, access_count AS accessCount, " +
            "last_accessed_at::timestamp AS lastAccessedAt, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM long_term_memory WHERE id = CAST(#{id} AS uuid)")
    @Results({
            @Result(property = "embedding", column = "embedding",
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    LongTermMemory selectById(String id);

    @Select("<script>" +
            "SELECT id, content, memory_type AS memoryType, scope, " +
            "project_id AS projectId, session_id AS sessionId, " +
            "embedding, embedding_model AS embeddingModel, importance, " +
            "metadata::text AS metadata, access_count AS accessCount, " +
            "last_accessed_at::timestamp AS lastAccessedAt, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt, " +
            "1 - (embedding &lt;=&gt; CAST(#{queryVector} AS vector)) AS similarity " +
            "FROM long_term_memory WHERE " +
            "<if test='types != null and !types.isEmpty()'>" +
            "memory_type IN " +
            "<foreach item='t' collection='types' open='(' separator=',' close=')'>#{t}</foreach> " +
            "AND " +
            "</if>" +
            "(" +
            "  scope = 'GLOBAL' " +
            "  <if test='projectId != null'>OR (scope = 'PROJECT' AND project_id = CAST(#{projectId} AS uuid))</if> " +
            ") " +
            "AND 1 - (embedding &lt;=&gt; CAST(#{queryVector} AS vector)) &gt; #{threshold} " +
            "ORDER BY similarity DESC LIMIT #{limit}" +
            "</script>")
    @Results({
            @Result(property = "embedding", column = "embedding",
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER),
            @Result(property = "similarity", column = "similarity")
    })
    List<LongTermMemory> similaritySearch(@Param("queryVector") String queryVector,
                                          @Param("types") List<String> types,
                                          @Param("projectId") String projectId,
                                          @Param("threshold") double threshold,
                                          @Param("limit") int limit);

    @Select("<script>" +
            "SELECT id, content, memory_type AS memoryType, scope, " +
            "project_id AS projectId, session_id AS sessionId, " +
            "embedding, embedding_model AS embeddingModel, importance, " +
            "metadata::text AS metadata, access_count AS accessCount, " +
            "last_accessed_at::timestamp AS lastAccessedAt, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt, " +
            "0.0 AS similarity " +
            "FROM long_term_memory WHERE " +
            "<if test='types != null and !types.isEmpty()'>" +
            "memory_type IN " +
            "<foreach item='t' collection='types' open='(' separator=',' close=')'>#{t}</foreach> " +
            "AND " +
            "</if>" +
            "(" +
            "  scope = 'GLOBAL' " +
            "  <if test='projectId != null'>OR (scope = 'PROJECT' AND project_id = CAST(#{projectId} AS uuid))</if> " +
            ") " +
            "<if test='terms != null and !terms.isEmpty()'>" +
            "AND (" +
            "<foreach item='term' collection='terms' separator=' OR '>" +
            "content ILIKE CONCAT('%', #{term}, '%') OR metadata::text ILIKE CONCAT('%', #{term}, '%')" +
            "</foreach>" +
            ") " +
            "</if>" +
            "ORDER BY " +
            "CASE importance WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END DESC, updated_at DESC " +
            "LIMIT #{limit}" +
            "</script>")
    @Results({
            @Result(property = "embedding", column = "embedding",
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER),
            @Result(property = "similarity", column = "similarity")
    })
    List<LongTermMemory> textSearch(@Param("terms") List<String> terms,
                                    @Param("types") List<String> types,
                                    @Param("projectId") String projectId,
                                    @Param("limit") int limit);

    @Select("<script>" +
            "SELECT id, content, memory_type AS memoryType, scope, " +
            "project_id AS projectId, session_id AS sessionId, " +
            "embedding, embedding_model AS embeddingModel, importance, " +
            "metadata::text AS metadata, access_count AS accessCount, " +
            "last_accessed_at::timestamp AS lastAccessedAt, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt, " +
            "1 - (embedding &lt;=&gt; CAST(#{queryVector} AS vector)) AS similarity " +
            "FROM long_term_memory WHERE " +
            "memory_type = #{memoryType} AND scope = #{scope} " +
            "<if test='projectId != null'>AND project_id = CAST(#{projectId} AS uuid)</if> " +
            "AND 1 - (embedding &lt;=&gt; CAST(#{queryVector} AS vector)) &gt; #{threshold} " +
            "ORDER BY similarity DESC LIMIT 1" +
            "</script>")
    @Results({
            @Result(property = "embedding", column = "embedding",
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER),
            @Result(property = "similarity", column = "similarity")
    })
    LongTermMemory findDuplicate(@Param("memoryType") String memoryType,
                                 @Param("scope") String scope,
                                 @Param("projectId") String projectId,
                                 @Param("queryVector") String queryVector,
                                 @Param("threshold") double threshold);

    @Select("<script>" +
            "SELECT id, content, memory_type AS memoryType, scope, " +
            "project_id AS projectId, session_id AS sessionId, " +
            "embedding, embedding_model AS embeddingModel, importance, " +
            "metadata::text AS metadata, access_count AS accessCount, " +
            "last_accessed_at::timestamp AS lastAccessedAt, " +
            "created_at::timestamp AS createdAt, updated_at::timestamp AS updatedAt " +
            "FROM long_term_memory WHERE 1=1 " +
            "<if test='type != null'>AND memory_type = #{type}</if>" +
            "<if test='scope != null'>AND scope = #{scope}</if>" +
            "<if test='projectId != null'>AND project_id = CAST(#{projectId} AS uuid)</if>" +
            "<if test='importance != null'>AND importance = #{importance}</if>" +
            "<if test='keyword != null'>AND (content ILIKE CONCAT('%', #{keyword}, '%') OR metadata::text ILIKE CONCAT('%', #{keyword}, '%'))</if>" +
            "ORDER BY updated_at DESC " +
            "LIMIT #{size} OFFSET #{offset}" +
            "</script>")
    @Results({
            @Result(property = "embedding", column = "embedding",
                    typeHandler = PgVectorTypeHandler.class, jdbcType = JdbcType.OTHER)
    })
    List<LongTermMemory> selectByFilter(@Param("type") String type,
                                        @Param("scope") String scope,
                                        @Param("projectId") String projectId,
                                        @Param("importance") String importance,
                                        @Param("keyword") String keyword,
                                        @Param("size") int size,
                                        @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM long_term_memory WHERE 1=1 " +
            "<if test='type != null'>AND memory_type = #{type}</if>" +
            "<if test='scope != null'>AND scope = #{scope}</if>" +
            "<if test='projectId != null'>AND project_id = CAST(#{projectId} AS uuid)</if>" +
            "<if test='importance != null'>AND importance = #{importance}</if>" +
            "<if test='keyword != null'>AND (content ILIKE CONCAT('%', #{keyword}, '%') OR metadata::text ILIKE CONCAT('%', #{keyword}, '%'))</if>" +
            "</script>")
    long countByFilter(@Param("type") String type,
                       @Param("scope") String scope,
                       @Param("projectId") String projectId,
                       @Param("importance") String importance,
                       @Param("keyword") String keyword);

    @Update("<script>" +
            "UPDATE long_term_memory " +
            "<set>" +
            "<if test='content != null'>content = #{content},</if>" +
            "<if test='memoryType != null'>memory_type = #{memoryType},</if>" +
            "<if test='scope != null'>scope = #{scope},</if>" +
            "<if test='scope != null and scope.equals(\"GLOBAL\")'>project_id = NULL,</if>" +
            "<if test='projectId != null and (scope == null or !scope.equals(\"GLOBAL\"))'>project_id = CAST(#{projectId} AS uuid),</if>" +
            "<if test='embedding != null'>embedding = #{embedding, typeHandler=com.example.genwriter.typehandler.PgVectorTypeHandler}::vector,</if>" +
            "<if test='embeddingModel != null'>embedding_model = #{embeddingModel},</if>" +
            "<if test='importance != null'>importance = #{importance},</if>" +
            "<if test='metadata != null'>metadata = CAST(#{metadata} AS jsonb),</if>" +
            "<if test='accessCount != null'>access_count = #{accessCount},</if>" +
            "<if test='lastAccessedAt != null'>last_accessed_at = #{lastAccessedAt},</if>" +
            "updated_at = NOW()" +
            "</set>" +
            "WHERE id = CAST(#{id} AS uuid)" +
            "</script>")
    int updateById(LongTermMemory memory);

    @Delete("DELETE FROM long_term_memory WHERE id = CAST(#{id} AS uuid)")
    int deleteById(String id);

    @Delete("<script>" +
            "DELETE FROM long_term_memory WHERE id IN " +
            "<foreach item='id' collection='ids' open='(' separator=',' close=')'>CAST(#{id} AS uuid)</foreach>" +
            "</script>")
    int deleteByIds(@Param("ids") List<String> ids);

    @Select("SELECT COUNT(*) FROM long_term_memory WHERE memory_type = #{type}")
    long countByType(@Param("type") String type);
}
