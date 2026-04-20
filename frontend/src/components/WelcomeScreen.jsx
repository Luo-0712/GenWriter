import '../styles/global.css';

const WelcomeScreen = ({ onSuggestionClick }) => {
  const suggestions = [
    { icon: '✍️', text: '帮我写一篇文章' },
    { icon: '📝', text: '帮我修改一段文字' },
    { icon: '💡', text: '帮我构思一个故事' },
    { icon: '📚', text: '帮我整理一份笔记' },
  ];

  return (
    <div className="welcome-screen">
      <div className="welcome-content">
        <div className="welcome-logo">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--accent-color)" strokeWidth="1.5">
            <path d="M12 2L2 7l10 5 10-5-10-5z"></path>
            <path d="M2 17l10 5 10-5"></path>
            <path d="M2 12l10 5 10-5"></path>
          </svg>
        </div>
        <h1 className="welcome-title">你好，我是 GenWriter</h1>
        <p className="welcome-subtitle">你的 AI 写作助手</p>
        
        <div className="suggestions-grid">
          {suggestions.map((suggestion, index) => (
            <button
              key={index}
              className="suggestion-card"
              onClick={() => onSuggestionClick(suggestion.text)}
            >
              <span className="suggestion-icon">{suggestion.icon}</span>
              <span className="suggestion-text">{suggestion.text}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};

export default WelcomeScreen;
