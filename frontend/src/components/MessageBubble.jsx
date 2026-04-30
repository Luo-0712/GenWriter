import { useState, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import ThinkingPanel from './ThinkingPanel';
import '../styles/global.css';

const renderOutlineDetail = (data) => (
  <div className="tp-outline-preview">
    <ReactMarkdown remarkPlugins={[remarkGfm]}>{data}</ReactMarkdown>
  </div>
);

const INTENT_LABELS = {
  WRITING_TASK: '写作任务',
  KNOWLEDGE_QA: '知识问答',
  POLISH_TASK:  '润色任务',
};

const TYPE_LABELS = {
  CREATE:   '创作',
  CONTINUE: '续写',
  POLISH:   '润色',
};

const intentLabel = (intent) => INTENT_LABELS[intent] || intent || '未知意图';
const typeLabel = (type) => TYPE_LABELS[type] || type || '未知类型';

const STAGE_CONFIG = {
  '任务启动': { label: '启动', color: '#3b82f6', bg: '#dbeafe' },
  '意图识别': { label: '意图识别', color: '#7c3aed', bg: '#ede9fe' },
  '知识库检索': { label: '知识检索', color: '#0891b2', bg: '#cffafe' },
  '大纲生成': { label: '大纲生成', color: '#ea580c', bg: '#fff7ed' },
  '正文写作': { label: '正文写作', color: '#2563eb', bg: '#dbeafe' },
  '内容评审': { label: '内容评审', color: '#d97706', bg: '#fef3c7' },
  '润色': { label: '润色', color: '#16a34a', bg: '#dcfce7' },
  '直接回答': { label: '直接回答', color: '#0891b2', bg: '#cffafe' },
  '任务完成': { label: '完成', color: '#16a34a', bg: '#dcfce7' },
  '任务失败': { label: '失败', color: '#dc2626', bg: '#fee2e2' },
};

const parseStatusText = (text) => {
  if (!text) return null;
  const match = text.match(/^【(.+?)】(.+)$/);
  if (match) return { stage: match[1], description: match[2] };
  const match2 = text.match(/^【(.+?)】$/);
  if (match2) return { stage: match2[1], description: '' };
  return null;
};

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
      <span className={`tp-badge ${intentBadgeClass(data['意图'])}`}>{intentLabel(data['意图'])}</span>
      <span className={`tp-badge ${typeBadgeClass(data['写作类型'])}`}>{typeLabel(data['写作类型'])}</span>
    </div>
    {data['分析'] && (
      <blockquote className="tp-feedback-quote">{data['分析']}</blockquote>
    )}
  </div>
);

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

const StreamingText = ({ text, isStreaming }) => {
  const [displayed, setDisplayed] = useState('');

  useEffect(() => {
    if (!isStreaming) {
      setDisplayed(text);
      return;
    }
    let i = 0;
    setDisplayed('');
    const timer = setInterval(() => {
      i++;
      setDisplayed(text.slice(0, i));
      if (i >= text.length) {
        clearInterval(timer);
      }
    }, 25);
    return () => clearInterval(timer);
  }, [text, isStreaming]);

  return (
    <span className={isStreaming ? 'streaming-text' : ''}>
      {displayed}
    </span>
  );
};

const StatusStageBadge = ({ stage }) => {
  const config = STAGE_CONFIG[stage] || { label: stage, color: '#6b7280', bg: '#f3f4f6' };
  return (
    <span className="status-stage-badge" style={{ color: config.color, backgroundColor: config.bg }}>
      {config.label}
    </span>
  );
};

const StatusWithBadge = ({ text, streamDesc }) => {
  const parsed = parseStatusText(text);
  if (!parsed) {
    return <span className="status-text">{text}</span>;
  }
  return (
    <span className="status-with-badge">
      <StatusStageBadge stage={parsed.stage} />
      {parsed.description && (
        streamDesc ? (
          <StreamingText text={parsed.description} isStreaming={true} />
        ) : (
          <span className="status-desc">{parsed.description}</span>
        )
      )}
    </span>
  );
};

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

const MessageBubble = ({ message }) => {
  const isUser = message.role === 'user';
  const [thinkingExpanded, setThinkingExpanded] = useState(false);
  const thinkingSteps = message.thinkingSteps || [];
  const chainNodes = message.chainNodes || [];
  const hasChainNodes = chainNodes.length > 0;
  const hasThinkingSteps = thinkingSteps.length > 0;
  const hasThinking = hasChainNodes || hasThinkingSteps;
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
        {message.statusText && !message.content && !hasThinking && (
          <div className="message-status">
            <StatusWithBadge text={message.statusText} streamDesc={false} />
            <div className="thinking-dots">
              <span></span>
              <span></span>
              <span></span>
            </div>
          </div>
        )}
        {hasChainNodes && (
          <ThinkingPanel
            chainNodes={chainNodes}
            isStreaming={message.isStreaming}
          />
        )}
        {!hasChainNodes && hasThinkingSteps && (
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
                <span className="thinking-process-latest">
                  {(() => {
                    const p = parseStatusText(latestStep);
                    return p ? p.description || p.stage : latestStep;
                  })()}
                </span>
              )}
            </div>
            {thinkingExpanded && (
              <ol className="thinking-process-list">
                {thinkingSteps.map((step, i) => {
                  const isLast = i === thinkingSteps.length - 1;
                  const showStream = isLast && message.isStreaming;
                  return (
                    <li key={i} className={`thinking-process-item ${isLast ? 'is-latest' : ''}`}>
                      <span className="thinking-process-step-num">{i + 1}.</span>
                      <div className="thinking-process-step-body">
                        <span className="thinking-process-step-text">
                          <StatusWithBadge text={step.text} streamDesc={showStream} />
                        </span>
                        {renderStepData(step.data)}
                      </div>
                    </li>
                  );
                })}
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
