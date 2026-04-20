package com.example.genwriter.service.impl;

import com.example.genwriter.message.SseMessage;
import com.example.genwriter.sse.ChannelMessage;
import com.example.genwriter.sse.SseMessageChannel;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SSE 服务实现 - 发布-订阅模式
 *
 * 核心特性：
 * - 每个写作任务对应一个 MessageChannel
 * - 频道独立缓存消息，不依赖订阅者存在
 * - SSE连接断开只是取消订阅，任务继续执行
 * - 重连时可从 lastSequenceId 开始恢复进度
 */
@Slf4j
@Service
@AllArgsConstructor
public class SseServiceImpl implements SseService {

    /** 频道映射：sessionId → SseMessageChannel */
    private final ConcurrentMap<String, SseMessageChannel> channels = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter subscribe(String sessionId, long afterSequenceId) {
        // 获取或创建频道
        SseMessageChannel channel = channels.computeIfAbsent(sessionId,
                id -> new SseMessageChannel(id, objectMapper));

        // 创建 SseEmitter，超时时间 30 分钟
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 获取历史消息（用于重连恢复）
        List<ChannelMessage> history = channel.subscribe(emitter, afterSequenceId);

        // 先发送历史消息
        try {
            // 发送连接成功事件
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("{\"connected\":true,\"lastSeq\":" + channel.getLastSequenceId() + "}"));

            // 发送历史消息
            for (ChannelMessage msg : history) {
                String json = objectMapper.writeValueAsString(msg);
                emitter.send(SseEmitter.event()
                        .name("message")
                        .data(json));
            }

            log.info("SSE 订阅成功：sessionId={}, afterSeq={}, historyCount={}",
                    sessionId, afterSequenceId, history.size());

        } catch (IOException e) {
            log.error("发送历史消息失败：sessionId={}", sessionId, e);
            channel.unsubscribe(emitter);
            throw new RuntimeException("发送历史消息失败", e);
        }

        return emitter;
    }

    @Override
    public ChannelMessage publish(String sessionId, SseMessage message) {
        // 获取或创建频道，确保消息被缓存
        SseMessageChannel channel = channels.computeIfAbsent(sessionId,
                id -> new SseMessageChannel(id, objectMapper));

        // 发布消息到频道
        ChannelMessage channelMessage = channel.publish(message);

        log.debug("消息已发布：sessionId={}, seq={}, type={}",
                sessionId, channelMessage.getSequenceId(), message.getType());

        return channelMessage;
    }

    @Override
    public void complete(String sessionId) {
        SseMessageChannel channel = channels.get(sessionId);
        if (channel != null) {
            channel.complete();
            // 频道完成后可以延迟清理，给前端时间重连获取最终状态
            // 这里暂时保留频道，实际清理可以由定时任务处理
            log.info("频道已标记完成：sessionId={}", sessionId);
        }
    }

    @Override
    public void unsubscribe(String sessionId) {
        // 注意：这里只是移除频道，实际订阅者在各自的 emitter 回调中处理
        // 这个方法主要用于前端主动断开连接的场景
        SseMessageChannel channel = channels.get(sessionId);
        if (channel != null) {
            // 如果频道已完成且无订阅者，可以清理
            if (channel.isCompleted() && channel.getSubscriberCount() == 0) {
                channels.remove(sessionId);
                log.info("频道已清理：sessionId={}", sessionId);
            }
        }
    }

    @Override
    public List<ChannelMessage> getHistory(String sessionId, long afterSequenceId) {
        SseMessageChannel channel = channels.get(sessionId);
        if (channel == null) {
            return Collections.emptyList();
        }
        return channel.getMessagesAfter(afterSequenceId);
    }

    @Override
    public boolean hasChannel(String sessionId) {
        return channels.containsKey(sessionId);
    }

    @Override
    public long getLastSequenceId(String sessionId) {
        SseMessageChannel channel = channels.get(sessionId);
        return channel != null ? channel.getLastSequenceId() : 0;
    }

    /**
     * 清理已完成的频道（可由定时任务调用）
     *
     * @param ttlMinutes 完成后保留时间（分钟）
     */
    public void cleanupCompletedChannels(int ttlMinutes) {
        long threshold = System.currentTimeMillis() - ttlMinutes * 60 * 1000L;

        channels.entrySet().removeIf(entry -> {
            SseMessageChannel channel = entry.getValue();
            if (channel.isCompleted() && channel.getSubscriberCount() == 0) {
                log.info("清理已完成频道：sessionId={}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}