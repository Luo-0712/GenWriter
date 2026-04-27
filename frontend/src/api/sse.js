/**
 * 建立 SSE 连接并处理流式消息
 * @param {string} sessionId - 会话ID
 * @param {object} handlers - 消息处理回调
 * @param {function} handlers.onMessage - 收到消息时调用
 * @param {function} handlers.onError - 发生错误时调用
 * @param {function} handlers.onComplete - 连接完成时调用
 * @param {function} [handlers.onOpen] - 连接建立时调用
 * @param {number} [after=0] - 断线重连的起始序列号
 * @returns {function} 关闭连接的函数
 */
export function connectSSE(sessionId, handlers, after = 0) {
  const { onMessage, onError, onComplete, onOpen } = handlers;
  const url = `/sse/subscribe/${sessionId}?after=${after}`;
  const eventSource = new EventSource(url);
  let isCompleted = false;
  let isOpen = false;

  // 使用 addEventListener 监听命名 message 事件，兼容性更好
  const handleMessage = (event) => {
    try {
      const channelMsg = JSON.parse(event.data);
      if (onMessage) {
        onMessage(channelMsg);
      }
      if (channelMsg.completed) {
        isCompleted = true;
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

  eventSource.addEventListener('message', handleMessage);

  eventSource.onopen = () => {
    isOpen = true;
    if (onOpen) {
      onOpen();
    }
  };

  eventSource.onerror = () => {
    // 连接尚未建立成功的错误（如 4xx/5xx）
    if (!isOpen) {
      eventSource.close();
      if (onError) {
        onError(new Error('SSE 连接失败'));
      }
      return;
    }

    // 已收到完成信号，正常关闭
    if (isCompleted) {
      eventSource.close();
      if (onComplete) {
        onComplete();
      }
      return;
    }

    // 连接建立后意外断开（非完成信号导致）
    eventSource.close();
    if (onError) {
      onError(new Error('SSE 连接意外断开'));
    }
  };

  return () => {
    eventSource.removeEventListener('message', handleMessage);
    eventSource.close();
    fetch(`/sse/unsubscribe/${sessionId}`, { method: 'DELETE' }).catch(() => {});
  };
}
