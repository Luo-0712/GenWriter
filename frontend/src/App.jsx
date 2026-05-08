import { useState, useEffect, useCallback, useRef } from 'react';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import MemoryPanel from './components/MemoryPanel';
import KnowledgeBasePanel from './components/KnowledgeBasePanel';
import SettingsPanel from './components/SettingsPanel';
import WritingSkillLearningPanel from './components/WritingSkillLearningPanel';
import ExportDialog from './components/ExportDialog';
import * as sessionsApi from './api/sessions';
import * as messagesApi from './api/messages';
import * as projectsApi from './api/projects';
import { connectSSE } from './api/sse';
import './styles/global.css';

/* ---------- sessionStorage 思维链缓存 ---------- */
const chainStorageKey = (sessionId) => `genwriter_chain_${sessionId}`;

const saveChainToStorage = (sessionId, chainNodes, thinkingSteps, contentPrefix) => {
  try {
    const data = {
      chainNodes: chainNodes || [],
      thinkingSteps: thinkingSteps || [],
      contentPrefix: contentPrefix || '',
      timestamp: Date.now(),
    };
    sessionStorage.setItem(chainStorageKey(sessionId), JSON.stringify(data));
  } catch (e) {
    // sessionStorage 可能已满，静默失败
  }
};

const loadChainFromStorage = (sessionId) => {
  try {
    const raw = sessionStorage.getItem(chainStorageKey(sessionId));
    if (!raw) return null;
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
};

const clearChainStorage = (sessionId) => {
  try {
    sessionStorage.removeItem(chainStorageKey(sessionId));
  } catch (e) {
    // ignore
  }
};

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
  const [view, setView] = useState('chat');
  const [exportDialogMessage, setExportDialogMessage] = useState(null);

  const abortRef = useRef(null);

  useEffect(() => {
    let cancelled = false;
    async function init() {
      try {
        const projectList = await projectsApi.getAllProjects();
        if (cancelled) return;
        setProjects(projectList || []);
        if (projectList && projectList.length > 0) {
          const currentProject = projectList[0];
          setActiveProject(currentProject);
          const sessionList = await sessionsApi.getSessionsByProjectId(currentProject.id);
          if (cancelled) return;
          setSessions(sessionList || []);
          if (sessionList && sessionList.length > 0) {
            setActiveSession(sessionList[0]);
          } else {
            setActiveSession(null);
          }
        } else {
          setActiveProject(null);
          setSessions([]);
          setActiveSession(null);
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
          const mapped = (list || []).map((m) => {
            const msg = {
              id: m.id,
              role: m.role?.toLowerCase() === 'user' ? 'user' : 'assistant',
              content: m.content || '',
              timestamp: m.createdAt,
            };
            // 从后端 metadata 恢复思维链（支持长期持久化）
            if (m.metadata) {
              try {
                const meta = typeof m.metadata === 'string' ? JSON.parse(m.metadata) : m.metadata;
                if (meta.chainNodes) msg.chainNodes = meta.chainNodes;
                if (meta.thinkingSteps) msg.thinkingSteps = meta.thinkingSteps;
              } catch (e) {
                // ignore parse error
              }
            }
            return msg;
          });

          // 从 sessionStorage 恢复最新的思维链（覆盖后端数据，因为 sessionStorage 更实时）
          const cached = loadChainFromStorage(activeSession.id);
          if (cached && cached.chainNodes?.length > 0) {
            const lastAssistantIdx = mapped.reduce((last, m, i) =>
              m.role === 'assistant' ? i : last, -1
            );
            if (lastAssistantIdx >= 0) {
              mapped[lastAssistantIdx] = {
                ...mapped[lastAssistantIdx],
                chainNodes: cached.chainNodes,
                thinkingSteps: cached.thinkingSteps || mapped[lastAssistantIdx].thinkingSteps || [],
              };
            }
          }

          setMessages(mapped);
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

  /* ---------- 自动将思维链缓存到 sessionStorage ---------- */
  useEffect(() => {
    if (!activeSession?.id) return;
    const msgs = [...messages];
    const lastAssistant = msgs.reverse().find((m) => m.role === 'assistant');
    if (lastAssistant && (lastAssistant.chainNodes?.length > 0 || lastAssistant.thinkingSteps?.length > 0)) {
      saveChainToStorage(
        activeSession.id,
        lastAssistant.chainNodes,
        lastAssistant.thinkingSteps,
        lastAssistant.content?.substring(0, 100)
      );
    }
  }, [messages, activeSession?.id]);

  const handleSend = useCallback(
    async (content) => {
      if (isLoading) return;

      let project = activeProject;
      let session = activeSession;

      try {
        if (!project) {
          const createdProject = await projectsApi.createProject({
            name: '默认项目',
            description: '系统默认项目,用于组织您的写作会话',
          });
          setProjects([createdProject]);
          setActiveProject(createdProject);
          project = createdProject;
        }

        if (!session) {
          const createdSession = await sessionsApi.createSession({
            title: '新对话',
            type: 'CHAT',
            projectId: project.id,
          });
          setSessions([createdSession]);
          setActiveSession(createdSession);
          session = createdSession;
        }
      } catch (e) {
        setError('初始化会话失败: ' + e.message);
        return;
      }

      const sessionId = session.id;

      // 发送新消息前清除旧的思维链缓存
      clearChainStorage(sessionId);

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
    [activeProject, activeSession, isLoading]
  );

  const handleSelectProject = useCallback(async (project) => {
    setActiveProject(project);
    try {
      const sessionList = await sessionsApi.getSessionsByProjectId(project.id);
      setSessions(sessionList || []);
      if (sessionList && sessionList.length > 0) {
        setActiveSession(sessionList[0]);
      } else {
        setActiveSession(null);
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
        const updatedProjects = await projectsApi.getAllProjects();
        setProjects(updatedProjects || []);
        if (activeProject?.id === project.id) {
          if (updatedProjects && updatedProjects.length > 0) {
            const newActive = updatedProjects[0];
            setActiveProject(newActive);
            const sessionList = await sessionsApi.getSessionsByProjectId(newActive.id);
            setSessions(sessionList || []);
            setActiveSession(sessionList?.[0] || null);
          } else {
            setActiveProject(null);
            setSessions([]);
            setActiveSession(null);
          }
        }
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
        const [sessionList, projectList] = await Promise.all([
          sessionsApi.getSessionsByProjectId(activeProject?.id),
          projectsApi.getAllProjects(),
        ]);
        setSessions(sessionList || []);
        setProjects(projectList || []);
        if (activeSession?.id === session.id) {
          setActiveSession(sessionList?.[0] || null);
        }
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

  const handleExportMessage = useCallback((message) => {
    setExportDialogMessage(message);
  }, []);

  const handleUpdateProject = useCallback(
    async (project) => {
      try {
        await projectsApi.updateProject(project.id, project);
        setProjects((prev) =>
          prev.map((p) => (p.id === project.id ? { ...p, ...project } : p))
        );
        if (activeProject?.id === project.id) {
          setActiveProject((prev) => ({ ...prev, ...project }));
        }
      } catch (e) {
        setError('更新项目失败: ' + e.message);
      }
    },
    [activeProject]
  );

  const handleUpdateSession = useCallback(
    async (session) => {
      try {
        await sessionsApi.updateSession(session.id, session);
        setSessions((prev) =>
          prev.map((s) => (s.id === session.id ? { ...s, ...session } : s))
        );
        if (activeSession?.id === session.id) {
          setActiveSession((prev) => ({ ...prev, ...session }));
        }
      } catch (e) {
        setError('更新会话失败: ' + e.message);
      }
    },
    [activeSession]
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
        view={view}
        onSelectProject={handleSelectProject}
        onNewProject={handleNewProject}
        onDeleteProject={handleDeleteProject}
        onUpdateProject={handleUpdateProject}
        onSelectSession={handleSelectSession}
        onNewChat={handleNewChat}
        onDeleteSession={handleDeleteSession}
        onUpdateSession={handleUpdateSession}
        onNavigate={setView}
      />
      <main className="main-content">
        {view === 'chat' ? (
          <ChatArea
            messages={messages}
            onSend={handleSend}
            onSuggestionClick={handleSuggestionClick}
            onExport={handleExportMessage}
            isLoading={isLoading}
            hasContentStarted={hasContentStarted}
            loadingMessages={loadingMessages}
            isSessionLoading={isSessionLoading}
          />
        ) : view === 'knowledge-bases' ? (
          <KnowledgeBasePanel onBack={() => setView('chat')} />
        ) : view === 'memories' ? (
          <MemoryPanel
            projectId={activeProject?.id}
            onBack={() => setView('chat')}
          />
        ) : view === 'writing-skills' ? (
          <WritingSkillLearningPanel
            projectId={activeProject?.id}
            onBack={() => setView('chat')}
          />
        ) : view === 'settings' ? (
          <SettingsPanel />
        ) : null}
      </main>
      {exportDialogMessage && activeSession && (
        <ExportDialog
          message={exportDialogMessage}
          sessionId={activeSession.id}
          onClose={() => setExportDialogMessage(null)}
        />
      )}
    </div>
  );
}

export default App;
