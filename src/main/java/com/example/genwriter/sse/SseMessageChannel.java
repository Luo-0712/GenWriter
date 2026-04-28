package com.example.genwriter.sse;

import com.example.genwriter.message.SseMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 消息频道 - 发布-订阅模式核心组件
 *
 * 每个 writing session 对应一个频道，职责：
 * 1. 缓存消息（环形缓冲区），支持断线重连恢复
 * 2. 管理多个订阅者，同一 session 可被多个前端页面订阅
 * 3. 任务完成后自动标记完成状态
 *
 * 关键特性：
 * - SSE连接断开 ≠ 任务停止，只是取消订阅
 * - 任务继续执行，消息继续缓存
 * - 用户切回页面可重连并恢复进度
 */
@Slf4j
public class SseMessageChannel {

    /** 环形缓冲区大小：保留最近 100 条消息 */
    private static final int BUFFER_SIZE = 100;

    /** 消息序列号生成器 */
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    /** 环形缓冲区：缓存最近消息，支持重连恢复 */
    private final ChannelMessage[] messageBuffer = new ChannelMessage[BUFFER_SIZE];
    private int bufferHead = 0;  // 写入位置

    /** 订阅者列表：支持多个前端同时订阅同一 session */
    private final CopyOnWriteArrayList<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    /** 频道是否已完成（任务结束） */
    private volatile boolean completed = false;

    /** 完成时的最终消息 */
    private volatile ChannelMessage completeMessage;

    private final ObjectMapper objectMapper;
    private final String sessionId;

    public SseMessageChannel(String sessionId, ObjectMapper objectMapper) {
        this.sessionId = sessionId;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布消息到频道
     * 存入缓冲区 + 广播给所有订阅者
     *
     * @param message SSE 消息
     * @return 生成的频道消息（包含序列号）
     */
    public ChannelMessage publish(SseMessage message) {
        long seq = sequenceGenerator.incrementAndGet();
        ChannelMessage channelMessage = ChannelMessage.builder()
                .sequenceId(seq)
                .timestamp(System.currentTimeMillis())
                .payload(message)
                .completed(false)
                .build();

        // 存入环形缓冲区
        synchronized (this) {
            messageBuffer[bufferHead] = channelMessage;
            bufferHead = (bufferHead + 1) % BUFFER_SIZE;
        }

        // 广播给所有订阅者
        broadcast(channelMessage);

        log.debug("频道发布消息：sessionId={}, seq={}, subscribers={}",
                sessionId, seq, subscribers.size());

        return channelMessage;
    }

    /**
     * 标记频道完成
     * 发送完成信号给所有订阅者，并标记频道已完成
     */
    public void complete() {
        long seq = sequenceGenerator.incrementAndGet();

        SseMessage doneMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_DONE)
                .payload(SseMessage.Payload.builder()
                        .done(true)
                        .build())
                .build();

        completeMessage = ChannelMessage.builder()
                .sequenceId(seq)
                .timestamp(System.currentTimeMillis())
                .payload(doneMessage)
                .completed(true)
                .build();

        completed = true;

        // 广播完成信号
        broadcast(completeMessage);

        // 关闭所有订阅者的连接
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("关闭订阅者连接失败：sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
        subscribers.clear();

        log.debug("频道完成：sessionId={}, seq={}", sessionId, seq);
    }

    /**
     * 订阅频道
     * 加入订阅列表，并返回历史消息（用于重连恢复）
     *
     * @param emitter SSE 发射器
     * @param afterSequenceId 只获取该序号之后的消息（0 表示不获取历史）
     * @return 错过的时间消息列表
     */
    public List<ChannelMessage> subscribe(SseEmitter emitter, long afterSequenceId) {
        subscribers.add(emitter);

        // 设置断开回调：只移除订阅者，不关闭频道
        emitter.onCompletion(() -> {
            subscribers.remove(emitter);
            log.debug("订阅者断开：sessionId={}, remaining={}", sessionId, subscribers.size());
        });

        emitter.onTimeout(() -> {
            subscribers.remove(emitter);
            log.debug("订阅者超时：sessionId={}, remaining={}", sessionId, subscribers.size());
        });

        emitter.onError(e -> {
            subscribers.remove(emitter);
            log.debug("订阅者错误：sessionId={}, error={}, remaining={}",
                    sessionId, e.getMessage(), subscribers.size());
        });

        // 获取历史消息（用于重连恢复）
        List<ChannelMessage> history = getMessagesAfter(afterSequenceId);

        log.debug("新订阅者加入：sessionId={}, afterSeq={}, historyCount={}, totalSubscribers={}",
                sessionId, afterSequenceId, history.size(), subscribers.size());

        return history;
    }

    /**
     * 取消订阅
     * 注意：这只是移除订阅者，不会关闭频道
     */
    public void unsubscribe(SseEmitter emitter) {
        subscribers.remove(emitter);
        log.debug("取消订阅：sessionId={}, remaining={}", sessionId, subscribers.size());
    }

    /**
     * 获取指定序号之后的消息
     * 用于断线重连后恢复错过的事件
     */
    public List<ChannelMessage> getMessagesAfter(long afterSequenceId) {
        List<ChannelMessage> result = new ArrayList<>();

        synchronized (this) {
            // 从缓冲区中筛选
            for (int i = 0; i < BUFFER_SIZE; i++) {
                ChannelMessage msg = messageBuffer[i];
                if (msg != null && msg.getSequenceId() > afterSequenceId) {
                    result.add(msg);
                }
            }
        }

        // 按序列号排序
        result.sort((a, b) -> Long.compare(a.getSequenceId(), b.getSequenceId()));

        // 如果频道已完成，添加完成消息
        if (completed && completeMessage != null &&
            completeMessage.getSequenceId() > afterSequenceId) {
            if (!result.contains(completeMessage)) {
                result.add(completeMessage);
            }
        }

        return result;
    }

    /**
     * 广播消息给所有订阅者
     */
    private void broadcast(ChannelMessage channelMessage) {
        if (subscribers.isEmpty()) {
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(channelMessage);

            for (SseEmitter emitter : subscribers) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(json));
                } catch (IOException e) {
                    // 发送失败，移除该订阅者
                    subscribers.remove(emitter);
                    log.debug("广播失败，移除订阅者：sessionId={}, error={}",
                            sessionId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("序列化消息失败：sessionId={}", sessionId, e);
        }
    }

    /**
     * 频道是否已完成
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * 获取当前订阅者数量
     */
    public int getSubscriberCount() {
        return subscribers.size();
    }

    /**
     * 获取最新消息序列号
     */
    public long getLastSequenceId() {
        return sequenceGenerator.get();
    }
}
