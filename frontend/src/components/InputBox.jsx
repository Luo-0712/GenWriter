import { useState, useRef, useEffect } from 'react';
import '../styles/global.css';

const MODES = [
  { value: 'AUTO', label: '自动识别', icon: '✦' },
  { value: 'CREATE', label: '新建文档', icon: '✎' },
  { value: 'CONTINUE', label: '续写', icon: '→' },
  { value: 'POLISH', label: '润色优化', icon: '✧' },
  { value: 'KNOWLEDGE_QA', label: '知识问答', icon: '?' },
];

const InputBox = ({ onSend, disabled, isLoading }) => {
  const [inputValue, setInputValue] = useState('');
  const [mode, setMode] = useState('AUTO');
  const textareaRef = useRef(null);

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 200) + 'px';
    }
  }, [inputValue]);

  const handleSubmit = () => {
    const trimmed = inputValue.trim();
    if (trimmed && !isLoading && !disabled) {
      onSend(trimmed, mode);
      setInputValue('');
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  return (
    <div className="input-container">
      <div className="input-wrapper">
        <textarea
          ref={textareaRef}
          className="input-textarea"
          placeholder="输入消息..."
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled || isLoading}
          rows={1}
        />
        <button
          className="send-btn"
          onClick={handleSubmit}
          disabled={!inputValue.trim() || isLoading || disabled}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="22" y1="2" x2="11" y2="13"></line>
            <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
          </svg>
        </button>
      </div>
      <div className="mode-selector">
        {MODES.map((m) => (
          <button
            key={m.value}
            className={`mode-pill${mode === m.value ? ' active' : ''}`}
            onClick={() => setMode(m.value)}
            type="button"
          >
            <span className="mode-pill-icon">{m.icon}</span>
            {m.label}
          </button>
        ))}
      </div>
      <p className="input-hint">按 Enter 发送，Shift + Enter 换行</p>
    </div>
  );
};

export default InputBox;
