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

  const abortRef = useRef(null);
  const msgBufferRef = useRef({});

  // 初始加载会话列表
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

  // 切换会话时加载消息
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

  // 清理 SSE 连接
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

      // 本地添加用户消息（后端 chat 流程会自动持久化）
      const userMsg = {
        id: `local-user-${Date.now()}`,
        role: 'user',
        content,
        timestamp: new Date().toISOString(),
      };
      setMessages((prev) => [...prev, userMsg]);
      setIsLoading(true);
      setError(null);

      // 建立 SSE 连接
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
          timestamp: new Date().toISOString(),
        },
      ]);

      abortRef.current = connectSSE(
        sessionId,
        {
          onMessage: (channelMsg) => {
            const { payload, completed } = channelMsg;
            if (!payload) return;

            const ssePayload = payload.payload || {};
            const { data, statusText: st, done } = ssePayload;

            if (st && st !== statusText) {
              statusText = st;
              setMessages((prev) =
                prev.map((m) =
                  m.id === assistantMsgId ? { ...m, statusText } : m
                )
              );
            }

            if (typeof data === 'string' && data) {
              assistantContent += data;
              setMessages((prev) =
                prev.map((m) =
                  m.id === assistantMsgId
                    ? { ...m, content: assistantContent }
                    : m
                )
              );
            } else if (data && typeof data === 'object') {
              // 有时后端会返回完整对象
              const text = data.content || data.text || JSON.stringify(data);
              if (text) {
                assistantContent = text;
                setMessages((prev) =
                  prev.map((m) =
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
            setError('流式响应出错');
            setMessages((prev) =
              prev.map((m) =
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
        }
      );

      // 触发 AI 响应
      try {
        await messagesApi.chat(sessionId, content);
      } catch (e) {
        setIsLoading(false);
        setError('触发聊天失败: ' + e.message);
        setMessages((prev) =
          prev.map((m) =
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
          loadingMessages={loadingMessages}
          isSessionLoading={isSessionLoading}
        />
      </main>
    </div>
  );
}

export default App;
