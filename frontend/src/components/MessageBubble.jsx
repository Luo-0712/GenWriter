import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import '../styles/global.css';

const MessageBubble = ({ message }) => {
  const isUser = message.role === 'user';

  return (
    <div className={`message-bubble ${isUser ? 'user' : 'assistant'}`}>
      <div className="message-avatar">
        {isUser ? 'U' : 'G'}
      </div>
      <div className="message-content">
        <div className="message-role">
          {isUser ? '你' : 'GenWriter'}
        </div>
        <div className="message-text">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>
            {message.content}
          </ReactMarkdown>
        </div>
      </div>
    </div>
  );
};

export default MessageBubble;
