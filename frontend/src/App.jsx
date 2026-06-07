import { useState, useEffect, useCallback, useRef } from 'react';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import MemoryPanel from './components/MemoryPanel';
import KnowledgeBasePanel from './components/KnowledgeBasePanel';
import SettingsPanel from './components/SettingsPanel';
import WritingSkillLearningPanel from './components/WritingSkillLearningPanel';
import SkillPanel from './components/SkillPanel';
import ExportDialog from './components/ExportDialog';
import * as sessionsApi from './api/sessions';
import * as messagesApi from './api/messages';
import * as projectsApi from './api/projects';
import { connectSSE } from './api/sse';
import './styles/global.css';

/* ---------- sessionStorage 执行轨迹缓存 ---------- */
const chainStorageKey = (sessionId) => `genwriter_chain_${sessionId}`;

const saveChainToStorage = (sessionId, chainNodes, thinkingSteps, contentPrefix, sources, contentState = {}) => {
  try {
    const data = {
      chainNodes: chainNodes || [],
      thinkingSteps: thinkingSteps || [],
      sources: sources || [],
      contentPrefix: contentPrefix || '',
      contentStage: contentState.contentStage || '',
      contentStageLabel: contentState.contentStageLabel || '',
      contentStatus: contentState.contentStatus || '',
      contentActivityText: contentState.contentActivityText || '',
      isDraftStreaming: !!contentState.isDraftStreaming,
      isFinalContent: !!contentState.isFinalContent,
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

const CONTENT_STAGE_LABELS = {
  outline: '结构草稿',
  draft: '初稿',
  polish: '润色稿',
  direct_answer: '回答',
  final: '最终稿',
};

const normalizeGeneratedContentEvent = (data) => {
  if (!data || typeof data !== 'object') return null;

  const finalContent = data.finalContent === true;
  const artifact = data.artifact === true || finalContent;
  if (!artifact) return null;

  const stage = data.stage || (finalContent ? 'final' : '');
  const mode = data.mode || (finalContent ? 'final' : data.delta != null ? 'delta' : 'snapshot');
  const content = typeof data.content === 'string'
    ? data.content
    : typeof data.text === 'string'
      ? data.text
      : '';
  const delta = typeof data.delta === 'string' ? data.delta : '';

  return {
    finalContent,
    stage,
    stageLabel: data.stageLabel || CONTENT_STAGE_LABELS[stage] || '实时草稿',
    mode,
    content,
    delta,
    status: data.status || (finalContent ? 'COMPLETED' : 'RUNNING'),
    revision: typeof data.revision === 'number' ? data.revision : 0,
    updatedAt: data.updatedAt || Date.now(),
    document: data.document,
    sources: Array.isArray(data.sources) ? data.sources : null,
  };
};

const ACTIVITY_VARIANTS = {
  research: [
    '正在调研相关资料',
    '检索信息中，请稍候',
    '搜索相关内容',
    '调研正在进行中',
    '正在检索可用信息',
    '搜集相关资料中',
    '正在查找参考内容',
  ],
  review: [
    '正在评审当前内容',
    '评审进行中',
    '正在审核生成内容',
    '内容审核中，请稍候',
    '评审工作中',
    '正在审查输出质量',
    '审核正在进行',
  ],
  planning: [
    '正在规划写作结构',
    '规划下一步内容',
    '构思框架中',
    '规划写作思路',
    '正在拟定写作计划',
    '组织内容结构中',
  ],
  tool: [
    '工具正在处理中',
    '调用外部工具中',
    '工具执行中，请稍候',
    '正在使用工具处理',
    '工具调用进行中',
    '外部工具处理中',
    '正在通过工具完成任务',
  ],
};

const pickRandom = (arr) => arr[Math.floor(Math.random() * arr.length)];

const getStableContentActivityText = (statusText) => {
  if (!statusText) return '';
  if (/调研|检索|搜索|research/i.test(statusText)) return pickRandom(ACTIVITY_VARIANTS.research);
  if (/评审|审核|review/i.test(statusText)) return pickRandom(ACTIVITY_VARIANTS.review);
  if (/规划|计划|planning/i.test(statusText)) return pickRandom(ACTIVITY_VARIANTS.planning);
  if (/工具|tool/i.test(statusText)) return pickRandom(ACTIVITY_VARIANTS.tool);
  return '';
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
  const [selectedKbId, setSelectedKbId] = useState('');
  const [documentRefresh, setDocumentRefresh] = useState({ token: 0, documentId: '' });

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
            // 从后端 metadata 恢复执行轨迹（预留长期持久化能力）
            if (m.metadata) {
              try {
                const meta = typeof m.metadata === 'string' ? JSON.parse(m.metadata) : m.metadata;
                if (meta.chainNodes) msg.chainNodes = meta.chainNodes;
                if (meta.traceEvents) msg.traceEvents = meta.traceEvents;
                if (meta.thinkingSteps) msg.thinkingSteps = meta.thinkingSteps;
                if (meta.sources) msg.sources = meta.sources;
              } catch (e) {
                // ignore parse error
              }
            }
            return msg;
          });

          // 从 sessionStorage 恢复最新的执行轨迹（覆盖后端数据，因为 sessionStorage 更实时）
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
                sources: cached.sources || mapped[lastAssistantIdx].sources || [],
                contentStage: cached.contentStage || mapped[lastAssistantIdx].contentStage || '',
                contentStageLabel: cached.contentStageLabel || mapped[lastAssistantIdx].contentStageLabel || '',
                contentStatus: cached.contentStatus || mapped[lastAssistantIdx].contentStatus || '',
                contentActivityText: cached.contentActivityText || mapped[lastAssistantIdx].contentActivityText || '',
                isDraftStreaming: !!cached.isDraftStreaming,
                isFinalContent: !!cached.isFinalContent,
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

  /* ---------- 自动将执行轨迹缓存到 sessionStorage ---------- */
  useEffect(() => {
    if (!activeSession?.id) return;
    const msgs = [...messages];
    const lastAssistant = msgs.reverse().find((m) => m.role === 'assistant');
    if (lastAssistant && (
      lastAssistant.chainNodes?.length > 0 ||
      lastAssistant.thinkingSteps?.length > 0 ||
      lastAssistant.sources?.length > 0
    )) {
      saveChainToStorage(
        activeSession.id,
        lastAssistant.chainNodes,
        lastAssistant.thinkingSteps,
        lastAssistant.content?.substring(0, 100),
        lastAssistant.sources,
        {
          contentStage: lastAssistant.contentStage,
          contentStageLabel: lastAssistant.contentStageLabel,
          contentStatus: lastAssistant.contentStatus,
          contentActivityText: lastAssistant.contentActivityText,
          isDraftStreaming: lastAssistant.isDraftStreaming,
          isFinalContent: lastAssistant.isFinalContent,
        }
      );
    }
  }, [messages, activeSession?.id]);

  const handleSend = useCallback(
    async (content, mode = 'AUTO', webSearch = true, kbId = '', attachmentIds = [], documentId = '') => {
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

      // 发送新消息前清除旧的执行轨迹缓存
      clearChainStorage(sessionId);

      const userMsg = {
        id: `local-user-${Date.now()}`,
        role: 'user',
        content,
        timestamp: new Date().toISOString(),
        attachments: attachmentIds.length > 0 ? attachmentIds.map(id => ({ attachmentId: id })) : undefined,
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
          traceEvents: [],
          sources: [],
          contentStage: '',
          contentStageLabel: '',
          contentStatus: '',
          contentActivityText: '',
          isDraftStreaming: false,
          isFinalContent: false,
          isStreaming: true,
          timestamp: new Date().toISOString(),
        },
      ]);

      const doChat = async () => {
        try {
          await messagesApi.chat(sessionId, content, mode, webSearch, kbId || selectedKbId, attachmentIds, documentId);
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

          if (payload.type === 'AI_TRACE_EVENT') {
            if (data && typeof data === 'object') {
              const traceEvent = data;
              setMessages((prev) =>
                prev.map((m) => {
                  if (m.id !== assistantMsgId) return m;
                  return {
                    ...m,
                    traceEvents: [...(m.traceEvents || []), traceEvent],
                  };
                })
              );
            }
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

          // Reasoning content chunks from reasoning models (DeepSeek-R1 etc.)
          if (payload.type === 'AI_THINKING' && data && typeof data === 'object' && data.nodeId && data.reasoningChunk) {
            setMessages((prev) =>
              prev.map((m) => {
                if (m.id !== assistantMsgId) return m;
                const existingNodes = m.chainNodes || [];
                const nodeIndex = existingNodes.findIndex((n) => n.nodeId === data.nodeId);
                if (nodeIndex >= 0) {
                  const newNodes = [...existingNodes];
                  const existing = newNodes[nodeIndex];
                  newNodes[nodeIndex] = {
                    ...existing,
                    reasoningContent: (existing.reasoningContent || '') + data.reasoningChunk,
                  };
                  return { ...m, chainNodes: newNodes };
                }
                return m;
              })
            );
            return;
          }

          if (st && st !== statusText) {
            statusText = st;
            setMessages((prev) =>
              prev.map((m) => {
                if (m.id !== assistantMsgId) return m;
                const stepEntry = { text: st, data: data || null, timestamp: new Date().toISOString() };
                const contentActivityText = getStableContentActivityText(st);
                return {
                  ...m,
                  statusText,
                  ...(contentActivityText ? { contentActivityText } : {}),
                  thinkingSteps: payload.type === 'AI_THINKING'
                    ? [...(m.thinkingSteps || []), stepEntry]
                    : (m.thinkingSteps || []),
                };
              })
            );
          }

          if (payload.type === 'AI_GENERATED_CONTENT') {
            const event = normalizeGeneratedContentEvent(data);
            if (event) {
              setHasContentStarted(true);
              if (event.document?.id) {
                setDocumentRefresh({ token: Date.now(), documentId: event.document.id });
              }

              if (event.mode === 'stage_start') {
                assistantContent = '';
              } else if (event.mode === 'delta') {
                assistantContent += event.delta;
              } else {
                assistantContent = event.content;
              }

              setMessages((prev) =>
                prev.map((m) => {
                  if (m.id !== assistantMsgId) return m;
                  return {
                    ...m,
                    content: assistantContent,
                    contentStage: event.stage,
                    contentStageLabel: event.stageLabel,
                    contentStatus: event.status,
                    contentActivityText: '',
                    isDraftStreaming: !event.finalContent && event.status !== 'COMPLETED',
                    isFinalContent: event.finalContent,
                    ...(event.sources && event.sources.length > 0
                      ? { sources: event.sources }
                      : {}),
                  };
                })
              );
            }
          }

          if (done || completed) {
            setIsLoading(false);
            setMessages((prev) =>
              prev.map((m) =>
                m.id === assistantMsgId
                  ? { ...m, isStreaming: false, isDraftStreaming: false }
                  : m
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
                    contentStatus: m.content ? 'ERROR' : m.contentStatus,
                    contentActivityText: m.content ? '未完成草稿' : m.contentActivityText,
                    isDraftStreaming: false,
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
              m.id === assistantMsgId
                ? { ...m, isStreaming: false, isDraftStreaming: false }
                : m
            )
          );
          if (abortRef.current) {
            abortRef.current = null;
          }
        },
      });
    },
    [activeProject, activeSession, isLoading, selectedKbId]
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
            selectedKbId={selectedKbId}
            sessionId={activeSession?.id || ''}
            documentRefresh={documentRefresh}
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
        ) : view === 'skills' ? (
          <SkillPanel onBack={() => setView('chat')} />
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
