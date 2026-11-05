import { useState, useRef, useEffect } from 'react';
import ContentTree from './ContentTree';

const navItems = [
  { key: 'chat', label: '对话', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
    </svg>
  )},
  { key: 'knowledge-bases', label: '知识库', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
      <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
    </svg>
  )},
  { key: 'memories', label: '记忆', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M12 2a10 10 0 1 0 10 10H12V2z"></path>
      <path d="M12 2a10 10 0 0 1 10 10"></path>
      <path d="M20.2 7.2A10 10 0 0 0 12 2v10"></path>
    </svg>
  )},
  { key: 'writing-skills', label: '风格学习', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M12 2a10 10 0 1 0 10 10H12V2z"></path>
      <path d="M12 2a10 10 0 0 1 10 10"></path>
      <path d="M20.2 7.2A10 10 0 0 0 12 2v10"></path>
      <path d="M12 12l-4 4"></path>
      <path d="M12 12l4 4"></path>
    </svg>
  )},
  { key: 'skills', label: '技能管理', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"></path>
    </svg>
  )},
  { key: 'settings', label: '设置', icon: (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="3"></circle>
      <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"></path>
    </svg>
  )},
];

const SIDEBAR_COLLAPSED_STORAGE_KEY = 'genwriter-sidebar-collapsed';
const SIDEBAR_WIDTH_STORAGE_KEY = 'genwriter-sidebar-width';
const SIDEBAR_COLLAPSED_WIDTH = 68;
const SIDEBAR_COLLAPSE_THRESHOLD = 179;
const SIDEBAR_MIN_EXPANDED_WIDTH = 180;
const SIDEBAR_MAX_WIDTH = 520;

const readSidebarWidth = () => {
  try {
    const savedWidth = Number(window.localStorage.getItem(SIDEBAR_WIDTH_STORAGE_KEY));
    return Number.isFinite(savedWidth) && savedWidth > SIDEBAR_COLLAPSE_THRESHOLD
      ? Math.min(savedWidth, SIDEBAR_MAX_WIDTH)
      : 260;
  } catch {
    return 260;
  }
};

const Sidebar = ({
  projects,
  activeProject,
  sessions,
  activeSession,
  view,
  onSelectProject,
  onNewProject,
  onDeleteProject,
  onUpdateProject,
  onSelectSession,
  onNewChat,
  onDeleteSession,
  onUpdateSession,
  onNavigate,
  workspace,
  onSelectNode,
  onLegacy,
}) => {
  const [isCollapsed, setIsCollapsed] = useState(() => {
    try {
      return window.localStorage.getItem(SIDEBAR_COLLAPSED_STORAGE_KEY) === 'true';
    } catch {
      return false;
    }
  });
  const [sidebarWidth, setSidebarWidth] = useState(readSidebarWidth);
  const [isResizing, setIsResizing] = useState(false);
  const [hoveredSession, setHoveredSession] = useState(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  const [confirmDeleteProjectId, setConfirmDeleteProjectId] = useState(null);
  const [isUserMenuOpen, setIsUserMenuOpen] = useState(false);
  const userMenuRef = useRef(null);
  const resizeStateRef = useRef(null);

  // 项目编辑状态
  const [editingProjectId, setEditingProjectId] = useState(null);
  const [editingProjectName, setEditingProjectName] = useState('');
  const projectInputRef = useRef(null);

  // 会话编辑状态
  const [editingSessionId, setEditingSessionId] = useState(null);
  const [editingSessionTitle, setEditingSessionTitle] = useState('');
  const sessionInputRef = useRef(null);

  useEffect(() => {
    const narrow = window.matchMedia('(max-width: 760px)');
    const update = () => { if (narrow.matches) setIsCollapsed(true); };
    update();
    narrow.addEventListener('change', update);
    return () => narrow.removeEventListener('change', update);
  }, []);

  const setSidebarCollapsed = (collapsed) => {
    setIsCollapsed(collapsed);
    try {
      window.localStorage.setItem(SIDEBAR_COLLAPSED_STORAGE_KEY, String(collapsed));
    } catch {
      // Keep the sidebar usable when browser storage is unavailable.
    }
  };

  const persistSidebarWidth = (width) => {
    try {
      window.localStorage.setItem(SIDEBAR_WIDTH_STORAGE_KEY, String(Math.round(width)));
    } catch {
      // Keep the sidebar usable when browser storage is unavailable.
    }
  };

  const toggleCollapsed = () => setSidebarCollapsed(!isCollapsed);

  const handleResizePointerDown = (event) => {
    if (event.button !== 0) return;
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    resizeStateRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startWidth: isCollapsed ? SIDEBAR_COLLAPSED_WIDTH : sidebarWidth,
      lastExpandedWidth: sidebarWidth,
      collapsed: isCollapsed,
    };
    setIsResizing(true);
  };

  const handleResizePointerMove = (event) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;

    const nextWidth = Math.max(0, Math.min(
      SIDEBAR_MAX_WIDTH,
      resizeState.startWidth + event.clientX - resizeState.startX,
    ));
    const shouldCollapse = nextWidth <= SIDEBAR_COLLAPSE_THRESHOLD;
    if (resizeState.collapsed !== shouldCollapse) {
      resizeState.collapsed = shouldCollapse;
      setSidebarCollapsed(shouldCollapse);
    }

    if (!shouldCollapse) {
      resizeState.lastExpandedWidth = nextWidth;
      setSidebarWidth(nextWidth);
    }
  };

  const handleResizePointerEnd = (event) => {
    const resizeState = resizeStateRef.current;
    if (!resizeState || resizeState.pointerId !== event.pointerId) return;
    persistSidebarWidth(resizeState.lastExpandedWidth);
    resizeStateRef.current = null;
    setIsResizing(false);
  };

  const handleResizeKeyDown = (event) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();

    const nextWidth = isCollapsed && event.key === 'ArrowRight'
      ? SIDEBAR_MIN_EXPANDED_WIDTH
      : sidebarWidth + (event.key === 'ArrowRight' ? 16 : -16);
    if (nextWidth <= SIDEBAR_COLLAPSE_THRESHOLD) {
      setSidebarCollapsed(true);
      return;
    }

    const boundedWidth = Math.min(nextWidth, SIDEBAR_MAX_WIDTH);
    setSidebarWidth(boundedWidth);
    setSidebarCollapsed(false);
    persistSidebarWidth(boundedWidth);
  };

  // 项目编辑聚焦
  useEffect(() => {
    if (editingProjectId && projectInputRef.current) {
      projectInputRef.current.focus();
      projectInputRef.current.select();
    }
  }, [editingProjectId]);

  // 会话编辑聚焦
  useEffect(() => {
    if (editingSessionId && sessionInputRef.current) {
      sessionInputRef.current.focus();
      sessionInputRef.current.select();
    }
  }, [editingSessionId]);

  // 点击外部关闭用户菜单
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (userMenuRef.current && !userMenuRef.current.contains(e.target)) {
        setIsUserMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleDeleteClick = (e, session) => {
    e.stopPropagation();
    if (confirmDeleteId === session.id) {
      onDeleteSession(session);
      setConfirmDeleteId(null);
    } else {
      setConfirmDeleteId(session.id);
    }
  };

  const handleDeleteProjectClick = (e, project) => {
    e.stopPropagation();
    if (confirmDeleteProjectId === project.id) {
      onDeleteProject(project);
      setConfirmDeleteProjectId(null);
    } else {
      setConfirmDeleteProjectId(project.id);
    }
  };

  // ---------- 项目重命名 ----------
  const startEditingProject = (e, project) => {
    e.stopPropagation();
    setEditingProjectId(project.id);
    setEditingProjectName(project.name);
  };

  const commitProjectEdit = () => {
    if (!editingProjectId) return;
    const trimmed = editingProjectName.trim();
    if (trimmed) {
      const project = projects.find((p) => p.id === editingProjectId);
      if (project && project.name !== trimmed) {
        onUpdateProject({ ...project, name: trimmed });
      }
    }
    setEditingProjectId(null);
    setEditingProjectName('');
  };

  const handleProjectKeyDown = (e) => {
    if (e.key === 'Enter') {
      commitProjectEdit();
    } else if (e.key === 'Escape') {
      setEditingProjectId(null);
      setEditingProjectName('');
    }
  };

  // ---------- 会话重命名 ----------
  const startEditingSession = (e, session) => {
    e.stopPropagation();
    setEditingSessionId(session.id);
    setEditingSessionTitle(session.title);
  };

  const commitSessionEdit = () => {
    if (!editingSessionId) return;
    const trimmed = editingSessionTitle.trim();
    if (trimmed) {
      const session = sessions.find((s) => s.id === editingSessionId);
      if (session && session.title !== trimmed) {
        onUpdateSession({ ...session, title: trimmed });
      }
    }
    setEditingSessionId(null);
    setEditingSessionTitle('');
  };

  const handleSessionKeyDown = (e) => {
    if (e.key === 'Enter') {
      commitSessionEdit();
    } else if (e.key === 'Escape') {
      setEditingSessionId(null);
      setEditingSessionTitle('');
    }
  };

  return (
    <aside
      className={`sidebar ${isCollapsed ? 'is-collapsed' : ''}${isResizing ? ' is-resizing' : ''}`}
      style={{ '--sidebar-current-width': `${sidebarWidth}px` }}
    >
      <div className="sidebar-header">
        <button
          className="sidebar-collapse-btn"
          onClick={toggleCollapsed}
          title={isCollapsed ? '展开侧栏' : '收起侧栏'}
          aria-label={isCollapsed ? '展开侧栏' : '收起侧栏'}
          aria-expanded={!isCollapsed}
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <rect x="3" y="4" width="18" height="16" rx="2"></rect>
            <path d={isCollapsed ? 'M9 4v16m6-10 3 2-3 2' : 'M9 4v16m6-4-3-2 3-2'}></path>
          </svg>
        </button>
        <button className="new-chat-btn" onClick={onNewChat} title="新对话" aria-label="新对话">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="12" y1="5" x2="12" y2="19"></line>
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
          {!isCollapsed && <span>新对话</span>}
        </button>
      </div>

      {isCollapsed && (
        <nav className="sidebar-compact-nav" aria-label="主导航">
          {navItems.map((item) => (
            <button
              key={item.key}
              className={`sidebar-compact-nav-item ${view === item.key ? 'active' : ''}`}
              onClick={() => onNavigate(item.key)}
              title={item.label}
              aria-label={item.label}
              aria-current={view === item.key ? 'page' : undefined}
            >
              {item.icon}
            </button>
          ))}
        </nav>
      )}

      <div className="sidebar-content">
        <div className="project-section">
          <div className="project-section-header">
            <span className="section-label">作品项目</span>
            <button className="section-action-btn" onClick={onNewProject} title="新建项目">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
              </svg>
            </button>
          </div>
          <div className="project-list">
            {projects.map((project) => (
              <div
                key={project.id}
                className={`project-item ${activeProject?.id === project.id ? 'active' : ''}`}
                onClick={() => onSelectProject(project)}
                onMouseLeave={() => setConfirmDeleteProjectId(null)}
              >
                <svg className="project-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                </svg>
                {editingProjectId === project.id ? (
                  <input
                    ref={projectInputRef}
                    className="sidebar-edit-input"
                    value={editingProjectName}
                    onChange={(e) => setEditingProjectName(e.target.value)}
                    onBlur={commitProjectEdit}
                    onKeyDown={handleProjectKeyDown}
                    onClick={(e) => e.stopPropagation()}
                  />
                ) : (
                  <span
                    className="project-name"
                    onDoubleClick={(e) => startEditingProject(e, project)}
                    title="双击重命名"
                  >
                    {project.name}
                  </span>
                )}
                <span className="project-session-count">{project.sessionCount || 0}</span>
                <button
                  className="project-delete-btn"
                  onClick={(e) => handleDeleteProjectClick(e, project)}
                  title={confirmDeleteProjectId === project.id ? '再次点击确认删除' : '删除项目'}
                >
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                    {confirmDeleteProjectId === project.id ? (
                      <polyline points="20 6 9 17 4 12"></polyline>
                    ) : (
                      <>
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                      </>
                    )}
                  </svg>
                </button>
              </div>
            ))}
          </div>
        </div>

        <div className="divider" />

        {workspace && activeProject && <ContentTree workspace={workspace} onSelect={onSelectNode} onLegacy={onLegacy} />}

        <div className={`session-section${workspace?.mode === 'studio' ? ' studio-sessions' : ''}`}>
          <div className="session-section-header">
            <span className="section-label">会话</span>
          </div>
          <div className="session-list">
            {sessions.map((session) => (
              <div
                key={session.id}
                className={`session-item ${activeSession?.id === session.id ? 'active' : ''}`}
                onClick={() => { onSelectSession(session); onNavigate('chat'); workspace?.preferences({ agentView: 'chat' }); }}
                onMouseEnter={() => setHoveredSession(session.id)}
                onMouseLeave={() => {
                  setHoveredSession(null);
                  setConfirmDeleteId(null);
                }}
              >
                <svg className="session-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                </svg>
                {editingSessionId === session.id ? (
                  <input
                    ref={sessionInputRef}
                    className="sidebar-edit-input"
                    value={editingSessionTitle}
                    onChange={(e) => setEditingSessionTitle(e.target.value)}
                    onBlur={commitSessionEdit}
                    onKeyDown={handleSessionKeyDown}
                    onClick={(e) => e.stopPropagation()}
                  />
                ) : (
                  <span
                    className="session-title"
                    onDoubleClick={(e) => startEditingSession(e, session)}
                    title="双击重命名"
                  >
                    {session.title}
                  </span>
                )}
                {hoveredSession === session.id && (
                  <button
                    className="session-delete-btn"
                    onClick={(e) => handleDeleteClick(e, session)}
                    title={confirmDeleteId === session.id ? '再次点击确认删除' : '删除对话'}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      {confirmDeleteId === session.id ? (
                        <polyline points="20 6 9 17 4 12"></polyline>
                      ) : (
                        <>
                          <polyline points="3 6 5 6 21 6"></polyline>
                          <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                        </>
                      )}
                    </svg>
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="sidebar-footer" ref={userMenuRef}>
        <div
          className="user-info"
          onClick={() => setIsUserMenuOpen((prev) => !prev)}
        >
          <div className="user-avatar">U</div>
          <span className="user-name">用户</span>
          <svg
            className={`user-menu-arrow ${isUserMenuOpen ? 'open' : ''}`}
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <polyline points="6 9 12 15 18 9"></polyline>
          </svg>
        </div>
        {isUserMenuOpen && (
          <div className="user-dropdown-menu">
            {navItems.map((item) => (
              <button
                key={item.key}
                className={`user-dropdown-item ${view === item.key ? 'active' : ''}`}
                onClick={() => {
                  onNavigate(item.key);
                  setIsUserMenuOpen(false);
                }}
              >
                {item.icon}
                {item.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <div
        className="sidebar-resize-handle"
        role="separator"
        aria-label="调整侧栏宽度"
        aria-orientation="vertical"
        aria-valuemin={SIDEBAR_COLLAPSED_WIDTH}
        aria-valuemax={SIDEBAR_MAX_WIDTH}
        aria-valuenow={isCollapsed ? SIDEBAR_COLLAPSED_WIDTH : Math.round(sidebarWidth)}
        tabIndex={0}
        onPointerDown={handleResizePointerDown}
        onPointerMove={handleResizePointerMove}
        onPointerUp={handleResizePointerEnd}
        onPointerCancel={handleResizePointerEnd}
        onKeyDown={handleResizeKeyDown}
      />
    </aside>
  );
};

export default Sidebar;
