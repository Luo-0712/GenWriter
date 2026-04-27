import { useState, useEffect, useCallback, useRef } from 'react';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import * as sessionsApi from './api/sessions';
import * as messagesApi from './api/messages';
import { connectSSE } from './api/sse';
import './styles/global.css';

function App() {
  const [sessions, setSessions] = useState([]);
  const [activeSession, setActiveSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [isSessionLoading, setIsSessionLoading] = useState(false);
  const [error, setError] = useState(null);
  const [hasContentStarted, setHasContentStarted] = useState(false);

  const abortRef = useRef(null);
  const msgBufferRef = useRef({});

  useEffect(() => {
    let cancelled = false;
    async function init() {
      try {
        const list = await sessionsApi.getAllSessions();
        if (cancelled) return;
        setSessions(list || []);
        if (list && list.length > 0) {
          setActiveSession(list[0]);
        } else {
          const created = await sessionsApi.createSession({
            title: '新对话',
            type: 'CHAT',
          });
          if (!cancelled) {
            setSessions([created]);
            setActiveSession(created);
          }
        }
      } catch (e) {
        if (!cancelled) {
          setError(e.message);
        }
      }
    }
    init();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!activeSession?.id) {
      setMessages([]);
      return;
    }
    let cancelled = false;
    async function loadMessages() {
      setIsSessionLoading(true);
      setMessages([]);
      try {
        const list = await messagesApi.getMessagesBySessionId(activeSession.id);
        if (!cancelled) {
          setMessages(
            (list || []).map((m) => ({
              id: m.id,
              role: m.role?.toLowerCase() === 'user' ? 'user' : 'assistant',
              content: m.content || '',
              timestamp: m.createdAt,
            }))
          );
        }
      } catch (e) {
        if (!cancelled) {
          setError(e.message);
        }
      } finally {
        if (!cancelled) {
          setIsSessionLoading(false);
        }
      }
    }
    loadMessages();
    return () => {
      cancelled = true;
    };
  }, [activeSession?.id]);

  useEffect(() => {
    return () => {
      if (abortRef.current) {
        abortRef.current();
        abortRef.current = null;
      }
    };
  }, []);

  const handleSend = useCallback(
    async (content) => {
      if (!activeSession?.id || isLoading) return;

      const sessionId = activeSession.id;

      const userMsg = {
        id: `local-user-${Date.now()}`,
        role: 'user',
        content,
        timestamp: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, userMsg]);
      setIsLoading(true);
      setHasContentStarted(false);
      setError(null);

      if (abortRef.current) {
        abortRef.current();
      }

      const assistantMsgId = `local-assistant-${Date.now()}`;
      let assistantContent = '';
      let statusText = '';

      setMessages((prev) => [
        ...prev,
        {
          id: assistantMsgId,
          role: 'assistant',
          content: '',
          statusText: '',
          thinkingSteps: [],
          timestamp: new Date().toISOString(),
        },
      ]);

      // 发送 chat 请求，在 SSE onOpen 回调中触发，确保连接就绪
      const doChat = async () => {
        try {
          await messagesApi.chat(sessionId, content);
        } catch (e) {
          setIsLoading(false);
          setError('触发聊天失败: ' + e.message);
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsgId
                ? { ...m, content: '服务暂时不可用，请重试。' }
                : m
            )
          );
          if (abortRef.current) {
            abortRef.current();
            abortRef.current = null;
          }
        }
      };

      abortRef.current = connectSSE(sessionId, {
        onOpen: () => {
          doChat();
        },
        onMessage: (channelMsg) => {
          const { payload, completed } = channelMsg;
          if (!payload) return;

          const ssePayload = payload.payload || {};
          const { data, statusText: st, done } = ssePayload;

          if (payload.type === 'TITLE_UPDATED') {
            const newTitle = typeof data === 'string' ? data : String(data);
            setSessions((prev) =>
              prev.map((s) =>
                s.id === sessionId ? { ...s, title: newTitle } : s
              )
            );
            setActiveSession((prev) =>
              prev?.id === sessionId ? { ...prev, title: newTitle } : prev
            );
            return;
          }

          if (st && st !== statusText) {
            statusText = st;
            setMessages((prev) =>
              prev.map((m) => {
                if (m.id !== assistantMsgId) return m;
                const stepEntry = { text: st, data: data || null, timestamp: new Date().toISOString() };
                return {
                  ...m,
                  statusText,
                  thinkingSteps: payload.type === 'AI_THINKING'
                    ? [...(m.thinkingSteps || []), stepEntry]
                    : (m.thinkingSteps || []),
                };
              })
            );
          }

          if (typeof data === 'string' && data) {
            setHasContentStarted(true);
            assistantContent += data;
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantMsgId
                  ? { ...m, content: assistantContent }
                  : m
              )
            );
          } else if (data && typeof data === 'object') {
            setHasContentStarted(true);
            const text = data.content || data.text || JSON.stringify(data);
            if (text) {
              assistantContent = text;
              setMessages((prev) =>
                prev.map((m) =>
                  m.id === assistantMsgId
                    ? { ...m, content: assistantContent }
                    : m
                )
              );
            }
          }

          if (done || completed) {
            setIsLoading(false);
            if (abortRef.current) {
              abortRef.current();
              abortRef.current = null;
            }
          }
        },
        onError: (err) => {
          setIsLoading(false);
          setError('流式响应出错: ' + (err?.message || ''));
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsgId
                ? {
                    ...m,
                    content: m.content || '响应异常，请重试。',
                  }
                : m
            )
          );
          if (abortRef.current) {
            abortRef.current();
            abortRef.current = null;
          }
        },
        onComplete: () => {
          setIsLoading(false);
          if (abortRef.current) {
            abortRef.current = null;
          }
        },
      });
    },
    [activeSession, isLoading]
  );

  const handleSelectSession = useCallback((session) => {
    if (abortRef.current) {
      abortRef.current();
      abortRef.current = null;
    }
    setIsLoading(false);
    setActiveSession(session);
  }, []);

  const handleNewChat = useCallback(async () => {
    try {
      const created = await sessionsApi.createSession({
        title: '新对话',
        type: 'CHAT',
      });
      setSessions((prev) => [created, ...prev]);
      setActiveSession(created);
    } catch (e) {
      setError('创建会话失败: ' + e.message);
    }
  }, []);

  const handleDeleteSession = useCallback(
    async (session) => {
      try {
        await sessionsApi.deleteSession(session.id);
        setSessions((prev) => {
          const remaining = prev.filter((s) => s.id !== session.id);
          if (activeSession?.id === session.id) {
            if (remaining.length > 0) {
              setActiveSession(remaining[0]);
            } else {
              sessionsApi
                .createSession({ title: '新对话', type: 'CHAT' })
                .then((created) => {
                  setSessions([created]);
                  setActiveSession(created);
                })
                .catch((err) => setError('创建会话失败: ' + err.message));
            }
          }
          return remaining;
        });
      } catch (e) {
        setError('删除会话失败: ' + e.message);
      }
    },
    [activeSession]
  );

  const handleSuggestionClick = useCallback(
    (text) => {
      handleSend(text);
    },
    [handleSend]
  );

  const clearError = () => setError(null);

  return (
    <div className="app-container">
      {error && (
        <div className="global-error" onClick={clearError}>
          {error}
        </div>
      )}
      <Sidebar
        sessions={sessions}
        activeSession={activeSession}
        onSelectSession={handleSelectSession}
        onNewChat={handleNewChat}
        onDeleteSession={handleDeleteSession}
      />
      <main className="main-content">
        <ChatArea
          messages={messages}
          onSend={handleSend}
          onSuggestionClick={handleSuggestionClick}
          isLoading={isLoading}
          hasContentStarted={hasContentStarted}
          loadingMessages={loadingMessages}
          isSessionLoading={isSessionLoading}
        />
      </main>
    </div>
  );
}

export default App;
