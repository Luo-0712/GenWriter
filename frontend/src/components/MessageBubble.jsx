import { useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import '../styles/global.css';

/* ---------- 大纲渲染 ---------- */
const renderOutlineDetail = (data) => (
  <div className="tp-outline-preview">
    <ReactMarkdown remarkPlugins={[remarkGfm]}>{data}</ReactMarkdown>
  </div>
);

/* ---------- 意图识别渲染 ---------- */
const intentBadgeClass = (intent) => {
  switch (intent) {
    case 'WRITING_TASK': return 'tp-badge-intent-writing';
    case 'KNOWLEDGE_QA': return 'tp-badge-intent-qa';
    case 'POLISH_TASK': return 'tp-badge-intent-polish';
    default: return 'tp-badge-intent-unknown';
  }
};

const typeBadgeClass = (type) => {
  switch (type) {
    case 'CREATE': return 'tp-badge-type-create';
    case 'CONTINUE': return 'tp-badge-type-continue';
    case 'POLISH': return 'tp-badge-type-polish';
    default: return 'tp-badge-type-default';
  }
};

const renderIntentDetail = (data) => (
  <div className="tp-intent-card">
    <div className="tp-intent-badges">
      <span className={`tp-badge ${intentBadgeClass(data['意图'])}`}>{data['意图']}</span>
      <span className={`tp-badge ${typeBadgeClass(data['写作类型'])}`}>{data['写作类型']}</span>
    </div>
    {data['分析'] && (
      <blockquote className="tp-feedback-quote">{data['分析']}</blockquote>
    )}
  </div>
);

/* ---------- 评审结果渲染 ---------- */
const scoreColor = (score) => {
  if (score >= 9) return '#27ae60';
  if (score >= 7) return '#2ecc71';
  if (score >= 5) return '#f39c12';
  return '#e74c3c';
};

const verdictClass = (verdict) => {
  if (verdict === 'PASS') return 'tp-verdict-pass';
  if (verdict === 'REVISE_DRAFT') return 'tp-verdict-draft';
  if (verdict === 'REVISE_POLISH') return 'tp-verdict-polish';
  return '';
};

const verdictLabel = (verdict) => {
  if (verdict === 'PASS') return '✓ 通过';
  if (verdict === 'REVISE_DRAFT') return '✗ 需重新起草';
  if (verdict === 'REVISE_POLISH') return '↻ 需再次润色';
  return verdict;
};

const renderReviewDetail = (data) => {
  const dimensions = ['结构', '内容', '语言', '逻辑', '相关性'].filter(k => data[k] != null);
  return (
    <div className="tp-review-card">
      <div className="tp-score-header">
        <span className="tp-score-label">综合评分</span>
        <span className="tp-score-number" style={{ color: scoreColor(data['综合评分']) }}>
          {data['综合评分']}<span className="tp-score-max">/10</span>
        </span>
        <span className={`tp-verdict-badge ${verdictClass(data['结论'])}`}>
          {verdictLabel(data['结论'])}
        </span>
      </div>
      {dimensions.length > 0 && (
        <div className="tp-dimensions">
          {dimensions.map(dim => (
            <div key={dim} className="tp-dimension-row">
              <span className="tp-dim-label">{dim}</span>
              <div className="tp-dim-bar-bg">
                <div
                  className="tp-dim-bar-fill"
                  style={{ width: `${(Number(data[dim]) / 10) * 100}%`, backgroundColor: scoreColor(data[dim]) }}
                />
              </div>
              <span className="tp-dim-score" style={{ color: scoreColor(data[dim]) }}>{data[dim]}</span>
            </div>
          ))}
        </div>
      )}
      {data['反馈意见'] && (
        <blockquote className="tp-feedback-quote">{data['反馈意见']}</blockquote>
      )}
    </div>
  );
};

/* ---------- 通用分发器 ---------- */
const renderStepData = (data) => {
  if (!data) return null;
  if (typeof data === 'string') return renderOutlineDetail(data);
  if (typeof data === 'object') {
    if ('综合评分' in data || '结论' in data) return renderReviewDetail(data);
    if ('意图' in data) return renderIntentDetail(data);
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

/* ---------- 主组件 ---------- */
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
