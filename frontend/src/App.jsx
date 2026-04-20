import { useState } from 'react';
import Sidebar from './components/Sidebar';
import ChatArea from './components/ChatArea';
import './styles/global.css';

function App() {
  const [sessions, setSessions] = useState([
    { id: 1, title: '新对话', messages: [] },
  ]);
  const [activeSession, setActiveSession] = useState(sessions[0]);
  const [isLoading, setIsLoading] = useState(false);
  const [loadingMessages, setLoadingMessages] = useState(false);
  const [isSessionLoading, setIsSessionLoading] = useState(false);

  const handleSend = (content) => {
    const newMessage = { role: 'user', content, timestamp: new Date() };

    setSessions((prev) => {
      const updated = prev.map((s) =>
        s.id === activeSession.id
          ? { ...s, messages: [...s.messages, newMessage] }
          : s
      );
      setActiveSession(updated.find((s) => s.id === activeSession.id));
      return updated;
    });

    setIsLoading(true);

    setTimeout(() => {
      const assistantMessage = {
        role: 'assistant',
        content: '这是一个初始化的前端页面，具体功能将在后续实现。',
        timestamp: new Date(),
      };

      setSessions((prev) => {
        const updated = prev.map((s) =>
          s.id === activeSession.id
            ? { ...s, messages: [...s.messages, assistantMessage] }
            : s
        );
        setActiveSession(updated.find((s) => s.id === activeSession.id));
        return updated;
      });
      setIsLoading(false);
    }, 1000);
  };

  const handleSelectSession = (session) => {
    setActiveSession(session);
  };

  const handleNewChat = () => {
    const newSession = {
      id: Date.now(),
      title: '新对话',
      messages: [],
    };
    setSessions((prev) => [newSession, ...prev]);
    setActiveSession(newSession);
  };

  const handleDeleteSession = (session) => {
    const remaining = sessions.filter((s) => s.id !== session.id);
    setSessions(remaining);
    if (activeSession?.id === session.id) {
      setActiveSession(remaining.length > 0 ? remaining[0] : null);
    }
  };

  const handleSuggestionClick = (text) => {
    handleSend(text);
  };

  if (!activeSession) {
    return (
      <div className="app-container">
        <div className="loading-screen">
          <div className="loading-spinner"></div>
          <p>加载中...</p>
        </div>
      </div>
    );
  }

  return (
    <div className="app-container">
      <Sidebar
        sessions={sessions}
        activeSession={activeSession}
        onSelectSession={handleSelectSession}
        onNewChat={handleNewChat}
        onDeleteSession={handleDeleteSession}
      />
      <main className="main-content">
        <ChatArea
          messages={activeSession?.messages || []}
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
