import { useState, useEffect, useCallback, useRef } from 'react';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import * as sessionsApi from './api/sessions';
import * as messagesApi from './api/messages';
import * as projectsApi from './api/projects';
import { connectSSE } from './api/sse';
import './styles/global.css';

function App() {
  const [projects, setProjects] = useState([]);
  const [activeProject, setActiveProject] = useState(null);
  const [sessions, setSessions] = useState([]);
  const [activeSession, setActiveSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [isSessionLoading, setIsSessionLoading] = useState(false);
  const [error, setError] = useState(null);
  const [hasContentStarted, setHasContentStarted] = useState(false);

  const abortRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    async function init() {
      try {
        let projectList = await projectsApi.getAllProjects();
        if (cancelled) return;
        if (!projectList || projectList.length === 0) {
          const defaultProject = await projectsApi.createProject({
            name: '默认项目',
            description: '系统默认项目,用于组织您的写作会话',
          });
          projectList = [defaultProject];
        }
        setProjects(projectList);
        const currentProject = projectList[0];
        setActiveProject(currentProject);

        const sessionList = await sessionsApi.getSessionsByProjectId(currentProject.id);
        if (cancelled) return;
        setSessions(sessionList || []);
        if (sessionList && sessionList.length > 0) {
          setActiveSession(sessionList[0]);
        } else {
          const created = await sessionsApi.createSession({
            title: '新对话',
            type: 'CHAT',
            projectId: currentProject.id,
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
          chainNodes: [],
          isStreaming: true,
          timestamp: new Date().toISOString(),
        },
      ]);

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

          if (payload.type === 'AI_CHAIN_EVENT') {
            if (data && typeof data === 'object') {
              const chainNode = data;
              setMessages((prev) =>
                prev.map((m) => {
                  if (m.id !== assistantMsgId) return m;
                  const existingNodes = m.chainNodes || [];
                  const existingIndex = existingNodes.findIndex(
                    (n) => n.nodeId === chainNode.nodeId
                  );
                  let newNodes;
                  if (existingIndex >= 0) {
                    newNodes = [...existingNodes];
                    newNodes[existingIndex] = {
                      ...newNodes[existingIndex],
                      ...chainNode,
                    };
                  } else {
                    newNodes = [...existingNodes, chainNode];
                  }
                  return { ...m, chainNodes: newNodes };
                })
              );
            }
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

          if (payload.type === 'AI_GENERATED_CONTENT') {
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
          }

          if (done || completed) {
            setIsLoading(false);
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantMsgId ? { ...m, isStreaming: false } : m
              )
            );
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
                    isStreaming: false,
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
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantMsgId ? { ...m, isStreaming: false } : m
            )
          );
          if (abortRef.current) {
            abortRef.current = null;
          }
        },
      });
    },
    [activeSession, isLoading]
  );

  const handleSelectProject = useCallback(async (project) => {
    setActiveProject(project);
    try {
      const sessionList = await sessionsApi.getSessionsByProjectId(project.id);
      setSessions(sessionList || []);
      if (sessionList && sessionList.length > 0) {
        setActiveSession(sessionList[0]);
      } else {
        const created = await sessionsApi.createSession({
          title: '新对话',
          type: 'CHAT',
          projectId: project.id,
        });
        setSessions([created]);
        setActiveSession(created);
      }
    } catch (e) {
      setError('加载项目会话失败: ' + e.message);
    }
  }, []);

  const handleSelectSession = useCallback((session) => {
    if (abortRef.current) {
      abortRef.current();
      abortRef.current = null;
    }
    setIsLoading(false);
    setActiveSession(session);
  }, []);

  const handleNewProject = useCallback(async () => {
    try {
      const created = await projectsApi.createProject({ name: '新项目' });
      setProjects((prev) => [created, ...prev]);
      setActiveProject(created);
      setSessions([]);
      setActiveSession(null);
      setMessages([]);
    } catch (e) {
      setError('创建项目失败: ' + e.message);
    }
  }, []);

  const handleDeleteProject = useCallback(
    async (project) => {
      try {
        await projectsApi.deleteProject(project.id);
        setProjects((prev) => {
          const remaining = prev.filter((p) => p.id !== project.id);
          if (activeProject?.id === project.id) {
            if (remaining.length > 0) {
              setActiveProject(remaining[0]);
              sessionsApi.getSessionsByProjectId(remaining[0].id).then((list) => {
                setSessions(list || []);
                setActiveSession(list?.[0] || null);
              });
            } else {
              projectsApi.createProject({ name: '默认项目' }).then((p) => {
                setProjects([p]);
                setActiveProject(p);
                setSessions([]);
                setActiveSession(null);
              });
            }
          }
          return remaining;
        });
      } catch (e) {
        setError('删除项目失败: ' + e.message);
      }
    },
    [activeProject]
  );

  const handleNewChat = useCallback(async () => {
    if (!activeProject?.id) return;
    try {
      const created = await sessionsApi.createSession({
        title: '新对话',
        type: 'CHAT',
        projectId: activeProject.id,
      });
      setSessions((prev) => [created, ...prev]);
      setActiveSession(created);
    } catch (e) {
      setError('创建会话失败: ' + e.message);
    }
  }, [activeProject]);

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
                .createSession({ title: '新对话', type: 'CHAT', projectId: activeProject?.id })
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
    [activeSession, activeProject]
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
        projects={projects}
        activeProject={activeProject}
        sessions={sessions}
        activeSession={activeSession}
        onSelectProject={handleSelectProject}
        onNewProject={handleNewProject}
        onDeleteProject={handleDeleteProject}
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
