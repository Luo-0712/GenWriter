package com.example.genwriter.agent.graph.checkpoint;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的 StateGraph Checkpoint 持久化实现
 * 支持 Graph 执行状态的断点续作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCheckpointSaver implements BaseCheckpointSaver {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GraphCheckpointProperties properties;

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String threadId = config.threadId().orElse("default");
        String key = buildKey(threadId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            Checkpoint checkpoint = objectMapper.readValue(json, Checkpoint.class);
            return Collections.singletonList(checkpoint);
        } catch (Exception e) {
            log.error("Checkpoint 反序列化失败: threadId={}", threadId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String threadId = config.threadId().orElse("default");
        String key = buildKey(threadId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            Checkpoint checkpoint = objectMapper.readValue(json, Checkpoint.class);
            return Optional.of(checkpoint);
        } catch (Exception e) {
            log.error("Checkpoint 反序列化失败: threadId={}", threadId, e);
            return Optional.empty();
        }
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        String threadId = config.threadId().orElse("default");
        String key = buildKey(threadId);
        try {
            String json = objectMapper.writeValueAsString(checkpoint);
            redisTemplate.opsForValue().set(key, json, properties.getTtlHours(), TimeUnit.HOURS);
            log.debug("Checkpoint 已保存: threadId={}, nodeId={}", threadId, checkpoint.getNodeId());
        } catch (Exception e) {
            log.error("Checkpoint 保存失败: threadId={}", threadId, e);
            // Checkpoint 保存失败不应中断主流程
        }
        return config;
    }

    @Override
    public boolean clear(RunnableConfig config) {
        String threadId = config.threadId().orElse("default");
        String key = buildKey(threadId);
        Boolean deleted = redisTemplate.delete(key);
        log.debug("Checkpoint 已清理: threadId={}", threadId);
        return deleted != null && deleted;
    }

    private String buildKey(String threadId) {
        return properties.getKeyPrefix() + threadId;
    }
}
