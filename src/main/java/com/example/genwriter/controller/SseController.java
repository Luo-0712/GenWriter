package com.example.genwriter.controller;

import com.example.genwriter.sse.ChannelMessage;
import com.example.genwriter.service.SseService;
import lombok.AllArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * SSE 控制器 - 发布-订阅模式
 */
@RestController
@RequestMapping("/sse")
@AllArgsConstructor
public class SseController {

    private final SseService sseService;

    /**
     * 订阅频道（建立 SSE 连接）
     *
     * @param sessionId 会话 ID
     * @param after 只获取该序号之后的消息（可选，默认 0，用于断线重连恢复）
     * @return SseEmitter 实例
     */
    @GetMapping(value = "/subscribe/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            @PathVariable String sessionId,
            @RequestParam(value = "after", defaultValue = "0") long after) {
        return sseService.subscribe(sessionId, after);
    }

    /**
     * 取消订阅（断开 SSE 连接）
     * 注意：这只是取消订阅，任务继续执行
     *
     * @param sessionId 会话 ID
     */
    @DeleteMapping(value = "/unsubscribe/{sessionId}")
    public ResponseEntity<Void> unsubscribe(@PathVariable String sessionId) {
        sseService.unsubscribe(sessionId);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取频道的历史消息
     * 用于前端主动拉取（可选功能）
     *
     * @param sessionId 会话 ID
     * @param after 起始序列号（可选，默认 0）
     * @return 历史消息列表
     */
    @GetMapping(value = "/history/{sessionId}")
    public ResponseEntity<List<ChannelMessage>> getHistory(
            @PathVariable String sessionId,
            @RequestParam(value = "after", defaultValue = "0") long after) {
        return ResponseEntity.ok(sseService.getHistory(sessionId, after));
    }

    /**
     * 检查频道状态
     *
     * @param sessionId 会话 ID
     * @return 频道状态信息
     */
    @GetMapping(value = "/status/{sessionId}")
    public ResponseEntity<ChannelStatus> getStatus(@PathVariable String sessionId) {
        boolean exists = sseService.hasChannel(sessionId);
        long lastSeq = sseService.getLastSequenceId(sessionId);
        return ResponseEntity.ok(new ChannelStatus(exists, lastSeq));
    }

    /**
     * 频道状态信息
     */
    public record ChannelStatus(boolean exists, long lastSequenceId) {}
}