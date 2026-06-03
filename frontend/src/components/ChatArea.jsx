import { useEffect, useRef } from 'react';
import MessageBubble from './MessageBubble';
import InputBox from './InputBox';
import WelcomeScreen from './WelcomeScreen';
import '../styles/global.css';

const ChatArea = ({ messages, onSend, onSuggestionClick, onExport, isLoading, hasContentStarted, loadingMessages, isSessionLoading, selectedKbId = '' }) => {
  const messagesEndRef = useRef(null);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="chat-area">
      {messages.length === 0 && !isSessionLoading ? (
        <WelcomeScreen onSuggestionClick={onSuggestionClick} />
      ) : (
        <div className="messages-container">
          {isSessionLoading && messages.length === 0 && (
            <div className="messages-loading">
              <div className="loading-spinner"></div>
              <p>加载消息中...</p>
            </div>
          )}
          {messages.map((message, index) => (
            <MessageBubble key={index} message={message} onExport={onExport} />
          ))}
          {isLoading && !hasContentStarted && (
            <div className="message-bubble assistant">
              <div className="message-avatar">G</div>
              <div className="message-content">
                <div className="thinking-status">
                  <span className="thinking-text">思考中</span>
                  <div className="thinking-dots">
                    <span></span>
                    <span></span>
                    <span></span>
                  </div>
                </div>
              </div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>
      )}
      <InputBox onSend={onSend} disabled={isSessionLoading} isLoading={isLoading} kbId={selectedKbId} />
    </div>
  );
};

export default ChatArea;
