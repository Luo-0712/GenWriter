import { useState } from 'react';
import '../styles/global.css';

const Sidebar = ({ sessions, activeSession, onSelectSession, onNewChat, onDeleteSession }) => {
  const [hoveredSession, setHoveredSession] = useState(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState(null);

  const handleDeleteClick = (e, session) => {
    e.stopPropagation();
    if (confirmDeleteId === session.id) {
      onDeleteSession(session);
      setConfirmDeleteId(null);
    } else {
      setConfirmDeleteId(session.id);
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
              <span className="session-title">{session.title}</span>
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
