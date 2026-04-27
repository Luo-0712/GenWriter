/**
 * 建立 SSE 连接并处理流式消息
 * @param {string} sessionId - 会话ID
 * @param {object} handlers - 消息处理回调
 * @param {function} handlers.onMessage - 收到消息时调用
 * @param {function} handlers.onError - 发生错误时调用
 * @param {function} handlers.onComplete - 连接完成时调用
 * @param {number} [after=0] - 断线重连的起始序列号
 * @returns {function} 关闭连接的函数
 */
export function connectSSE(sessionId, handlers, after = 0) {
  const { onMessage, onError, onComplete } = handlers;
  const url = `/sse/subscribe/${sessionId}?after=${after}`;
  const eventSource = new EventSource(url);

  eventSource.onmessage = (event) => {
    try {
      const channelMsg = JSON.parse(event.data);
      if (onMessage) {
        onMessage(channelMsg);
      }
      if (channelMsg.completed) {
        eventSource.close();
        if (onComplete) {
          onComplete();
        }
      }
    } catch (e) {
      if (onError) {
        onError(e);
      }
    }
  };

  eventSource.onerror = (err) => {
    if (onError) {
      onError(err);
    }
    eventSource.close();
  };

  return () => {
    eventSource.close();
    fetch(`/sse/unsubscribe/${sessionId}`, { method: 'DELETE' }).catch(() => {});
  };
}
