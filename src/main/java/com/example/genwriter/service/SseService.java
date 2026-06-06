package com.example.genwriter.service;

import com.example.genwriter.message.SseMessage;
import com.example.genwriter.sse.ChannelMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * SSE 服务接口 - 发布-订阅模式
 *
 * 核心特性：
 * - SSE连接断开 ≠ 任务停止，只是取消订阅
 * - 任务继续执行，消息继续缓存
 * - 用户切回页面可重连并恢复进度
 */
public interface SseService {

    void startRun(String sessionId);

    /**
     * 订阅频道（建立 SSE 连接）
     *
     * @param sessionId 会话 ID
     * @param afterSequenceId 只获取该序号之后的消息（0 表示不获取历史，用于断线重连恢复）
     * @return SseEmitter 实例
     */
    SseEmitter subscribe(String sessionId, long afterSequenceId);

    /**
     * 发布消息到频道
     * 消息会被缓存，并广播给所有订阅者
     *
     * @param sessionId 会话 ID
     * @param message SSE 消息
     * @return 生成的频道消息（包含序列号）
     */
    ChannelMessage publish(String sessionId, SseMessage message);

    /**
     * 标记频道完成
     * 发送完成信号给所有订阅者，频道可被后续清理
     *
     * @param sessionId 会话 ID
     */
    void complete(String sessionId);

    /**
     * 取消订阅（断开 SSE 连接）
     * 注意：这只是移除订阅者，不会关闭频道，任务继续执行
     *
     * @param sessionId 会话 ID
     */
    void unsubscribe(String sessionId);

    /**
     * 获取频道的历史消息
     * 用于前端主动拉取（可选功能）
     *
     * @param sessionId 会话 ID
     * @param afterSequenceId 起始序列号
     * @return 历史消息列表
     */
    List<ChannelMessage> getHistory(String sessionId, long afterSequenceId);

    /**
     * 检查频道是否存在
     *
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    boolean hasChannel(String sessionId);

    /**
     * 获取频道的最新序列号
     *
     * @param sessionId 会话 ID
     * @return 最新序列号，频道不存在返回 0
     */
    long getLastSequenceId(String sessionId);
}
