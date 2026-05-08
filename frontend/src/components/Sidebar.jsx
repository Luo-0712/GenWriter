import { useState, useRef, useEffect } from 'react';
import '../styles/global.css';

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
}) => {
  const [hoveredSession, setHoveredSession] = useState(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);
  const [confirmDeleteProjectId, setConfirmDeleteProjectId] = useState(null);

  // 项目编辑状态
  const [editingProjectId, setEditingProjectId] = useState(null);
  const [editingProjectName, setEditingProjectName] = useState('');
  const projectInputRef = useRef(null);

  // 会话编辑状态
  const [editingSessionId, setEditingSessionId] = useState(null);
  const [editingSessionTitle, setEditingSessionTitle] = useState('');
  const sessionInputRef = useRef(null);

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
    <aside className="sidebar">
      <div className="sidebar-header">
        <button className="new-chat-btn" onClick={onNewChat}>
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="12" y1="5" x2="12" y2="19"></line>
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
          新对话
        </button>
      </div>

      <div className="sidebar-content">
        <div className="project-section">
          <div className="project-section-header">
            <span className="section-label">项目</span>
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

        <div className="session-section">
          <div className="session-section-header">
            <span className="section-label">会话</span>
          </div>
          <div className="session-list">
            {sessions.map((session) => (
              <div
                key={session.id}
                className={`session-item ${activeSession?.id === session.id ? 'active' : ''}`}
                onClick={() => onSelectSession(session)}
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

      <div className="sidebar-nav">
        <button
          className={`sidebar-nav-btn ${view === 'chat' ? 'active' : ''}`}
          onClick={() => onNavigate('chat')}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
          </svg>
          对话
        </button>
        <button
          className={`sidebar-nav-btn ${view === 'knowledge-bases' ? 'active' : ''}`}
          onClick={() => onNavigate('knowledge-bases')}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
          </svg>
          知识库
        </button>
        <button
          className={`sidebar-nav-btn ${view === 'memories' ? 'active' : ''}`}
          onClick={() => onNavigate('memories')}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M12 2a10 10 0 1 0 10 10H12V2z"></path>
            <path d="M12 2a10 10 0 0 1 10 10"></path>
            <path d="M20.2 7.2A10 10 0 0 0 12 2v10"></path>
          </svg>
          记忆
        </button>
        <button
          className={`sidebar-nav-btn ${view === 'settings' ? 'active' : ''}`}
          onClick={() => onNavigate('settings')}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <circle cx="12" cy="12" r="3"></circle>
            <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"></path>
          </svg>
          设置
        </button>
      </div>
      <div className="sidebar-footer">
        <div className="user-info">
          <div className="user-avatar">U</div>
          <span className="user-name">用户</span>
        </div>
      </div>
    </aside>
  );
};

export default Sidebar;
