import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import '../styles/global.css';

const renderStepData = (data) => {
  if (!data) return null;
  if (typeof data === 'string') {
    return <pre className="thinking-process-detail-text">{data}</pre>;
  }
  if (typeof data === 'object') {
    return (
      <dl className="thinking-process-detail-kv">
        {Object.entries(data).map(([k, v]) => (
          <div key={k} className="thinking-process-kv-row">
            <dt>{k}</dt>
            <dd>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</dd>
          </div>
        ))}
      </dl>
    );
  }
  return <span className="thinking-process-detail-text">{String(data)}</span>;
};

const MessageBubble = ({ message }) => {
  const isUser = message.role === 'user';
  const [thinkingExpanded, setThinkingExpanded] = useState(false);
  const thinkingSteps = message.thinkingSteps || [];
  const hasThinkingSteps = thinkingSteps.length > 0;
  const latestStep = hasThinkingSteps ? thinkingSteps[thinkingSteps.length - 1].text : '';

  return (
    <div className={`message-bubble ${isUser ? 'user' : 'assistant'}`}>
      <div className="message-avatar">
        {isUser ? 'U' : 'G'}
      </div>
      <div className="message-content">
        <div className="message-role">
          {isUser ? '你' : 'GenWriter'}
        </div>
        {message.statusText && !message.content && !hasThinkingSteps && (
          <div className="message-status">
            <span className="status-text">{message.statusText}</span>
            <div className="thinking-dots">
              <span></span>
              <span></span>
              <span></span>
            </div>
          </div>
        )}
        {hasThinkingSteps && (
          <div className="thinking-process">
            <div
              className="thinking-process-header"
              onClick={() => setThinkingExpanded(!thinkingExpanded)}
            >
              <span className="thinking-process-arrow">
                {thinkingExpanded ? '▲' : '▼'}
              </span>
              <span className="thinking-process-label">思考过程</span>
              {!thinkingExpanded && (
                <span className="thinking-process-latest">{latestStep}</span>
              )}
            </div>
            {thinkingExpanded && (
              <ol className="thinking-process-list">
                {thinkingSteps.map((step, i) => (
                  <li key={i} className="thinking-process-item">
                    <span className="thinking-process-step-num">{i + 1}.</span>
                    <div className="thinking-process-step-body">
                      <span className="thinking-process-step-text">{step.text}</span>
                      {renderStepData(step.data)}
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </div>
        )}
        <div className="message-text">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>
            {message.content || ''}
          </ReactMarkdown>
        </div>
      </div>
    </div>
  );
};

export default MessageBubble;
