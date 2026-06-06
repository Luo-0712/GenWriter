import { useCallback, useEffect, useRef, useState } from 'react';
import MessageBubble from './MessageBubble';
import InputBox from './InputBox';
import WelcomeScreen from './WelcomeScreen';
import WritingWorkbench from './WritingWorkbench';
import '../styles/global.css';

const WORKBENCH_WIDTH_KEY = 'genwriter_workbench_width';
const WORKBENCH_COLLAPSED_KEY = 'genwriter_workbench_collapsed';
const MIN_WORKBENCH_WIDTH = 320;
const MAX_WORKBENCH_WIDTH = 680;
const DEFAULT_WORKBENCH_WIDTH = 460;

const clampWorkbenchWidth = (value) =>
  Math.min(MAX_WORKBENCH_WIDTH, Math.max(MIN_WORKBENCH_WIDTH, value));

const readSavedWorkbenchWidth = () => {
  if (typeof window === 'undefined') return DEFAULT_WORKBENCH_WIDTH;
  const saved = Number(window.localStorage.getItem(WORKBENCH_WIDTH_KEY));
  return Number.isFinite(saved) ? clampWorkbenchWidth(saved) : DEFAULT_WORKBENCH_WIDTH;
};

const readSavedCollapsed = () => {
  if (typeof window === 'undefined') return false;
  return window.localStorage.getItem(WORKBENCH_COLLAPSED_KEY) === 'true';
};

const ChatArea = ({ messages, onSend, onSuggestionClick, onExport, isLoading, hasContentStarted, isSessionLoading, selectedKbId = '', sessionId = '', documentRefresh }) => {
  const messagesEndRef = useRef(null);
  const [workbenchWidth, setWorkbenchWidth] = useState(readSavedWorkbenchWidth);
  const [workbenchCollapsed, setWorkbenchCollapsed] = useState(readSavedCollapsed);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const handleWorkbenchCollapse = useCallback((collapsed) => {
    setWorkbenchCollapsed(collapsed);
    window.localStorage.setItem(WORKBENCH_COLLAPSED_KEY, String(collapsed));
  }, []);

  const handleResizeStart = useCallback((event) => {
    event.preventDefault();
    const previousCursor = document.body.style.cursor;
    const previousUserSelect = document.body.style.userSelect;
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';

    const handleMove = (moveEvent) => {
      const nextWidth = clampWorkbenchWidth(window.innerWidth - moveEvent.clientX);
      setWorkbenchWidth(nextWidth);
      window.localStorage.setItem(WORKBENCH_WIDTH_KEY, String(nextWidth));
    };

    const handleEnd = () => {
      document.body.style.cursor = previousCursor;
      document.body.style.userSelect = previousUserSelect;
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleEnd);
      window.removeEventListener('pointercancel', handleEnd);
    };

    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleEnd);
    window.addEventListener('pointercancel', handleEnd);
  }, []);

  const handleResetWidth = useCallback(() => {
    setWorkbenchWidth(DEFAULT_WORKBENCH_WIDTH);
    window.localStorage.setItem(WORKBENCH_WIDTH_KEY, String(DEFAULT_WORKBENCH_WIDTH));
  }, []);

  return (
    <div className={`chat-area${workbenchCollapsed ? ' workbench-is-collapsed' : ''}`} style={{ '--workbench-width': `${workbenchWidth}px` }}>
      <div className="chat-conversation">
        {messages.length === 0 && !isSessionLoading ? (
          <WelcomeScreen onSuggestionClick={onSuggestionClick} />
        ) : (
          <div className="messages-container">
            {isSessionLoading && messages.length === 0 && (
              <div className="messages-loading">
                <div className="loading-spinner"></div>
                <p>加载消息中...</p>
              </div>
            )}
            {messages.map((message, index) => (
              <MessageBubble key={index} message={message} onExport={onExport} />
            ))}
            {isLoading && !hasContentStarted && (
              <div className="message-bubble assistant">
                <div className="message-avatar">G</div>
                <div className="message-content">
                  <div className="thinking-status">
                    <span className="thinking-text">思考中</span>
                    <div className="thinking-dots">
                      <span></span>
                      <span></span>
                      <span></span>
                    </div>
                  </div>
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
        )}
        <InputBox onSend={onSend} disabled={isSessionLoading} isLoading={isLoading} kbId={selectedKbId} sessionId={sessionId} />
      </div>
      {!workbenchCollapsed && (
        <button
          className="workbench-resizer"
          type="button"
          onPointerDown={handleResizeStart}
          onDoubleClick={handleResetWidth}
          title="拖拽调整工作台宽度，双击恢复默认"
          aria-label="调整工作台宽度"
        />
      )}
      <WritingWorkbench
        sessionId={sessionId}
        isLoading={isLoading}
        selectedKbId={selectedKbId}
        documentRefresh={documentRefresh}
        onIterate={onSend}
        collapsed={workbenchCollapsed}
        onCollapse={() => handleWorkbenchCollapse(true)}
        onExpand={() => handleWorkbenchCollapse(false)}
      />
    </div>
  );
};

export default ChatArea;
