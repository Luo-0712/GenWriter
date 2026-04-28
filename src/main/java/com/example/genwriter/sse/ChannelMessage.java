package com.example.genwriter.sse;

import com.example.genwriter.message.SseMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 频道消息 - 带序列号的消息封装
 *
 * 用于发布-订阅模式中，支持断线重连后的增量同步。
 * 前端可以记录已收到的 sequenceId，重连时只请求该序号之后的消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelMessage {

    /**
     * 全局递增序号
     * 用于增量同步，前端重连时传入 afterSequenceId 只接收新消息
     */
    private long sequenceId;

    /**
     * 消息时间戳
     */
    private long timestamp;

    /**
     * 实际消息内容
     */
    private SseMessage payload;

    /**
     * 是否为完成信号
     * true 表示该写作任务已结束，频道可以被清理
     */
    private boolean completed;
}
