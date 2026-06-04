import { useState, useEffect } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import ThinkingPanel from './ThinkingPanel';
import FileViewerModal from './FileViewerModal';
import { getAttachmentUrl, getThumbnailUrl } from '../api/attachments';
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
  '任务启动': { label: '启动', color: '#6b7280', bg: '#f3f4f6' },
  '意图识别': { label: '意图识别', color: '#6b7280', bg: '#f3f4f6' },
  '知识库检索': { label: '知识检索', color: '#6b7280', bg: '#f3f4f6' },
  '大纲生成': { label: '大纲生成', color: '#6b7280', bg: '#f3f4f6' },
  '正文写作': { label: '正文写作', color: '#6b7280', bg: '#f3f4f6' },
  '内容评审': { label: '内容评审', color: '#6b7280', bg: '#f3f4f6' },
  '润色': { label: '润色', color: '#6b7280', bg: '#f3f4f6' },
  '直接回答': { label: '直接回答', color: '#6b7280', bg: '#f3f4f6' },
  '任务完成': { label: '完成', color: '#059669', bg: '#ecfdf5' },
  '任务失败': { label: '失败', color: '#dc2626', bg: '#fef2f2' },
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
  if (score >= 9) return '#059669';
  if (score >= 7) return '#10b981';
  if (score >= 5) return '#f59e0b';
  return '#ef4444';
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
  if (data == null) return null;
  if (typeof data === 'string') return data ? renderOutlineDetail(data) : null;
  if (typeof data === 'object') {
    if ('综合评分' in data || '结论' in data) return renderReviewDetail(data);
    if ('意图' in data) return renderIntentDetail(data);
    const entries = Object.entries(data).filter(([, v]) => v != null);
    if (entries.length === 0) return null;
    return (
      <dl className="thinking-process-detail-kv">
        {entries.map(([k, v]) => (
          <div key={k} className="thinking-process-kv-row">
            <dt>{k}</dt>
            <dd>{typeof v === 'object' ? JSON.stringify(v) : String(v)}</dd>
          </div>
        ))}
      </dl>
    );
  }
  const str = String(data);
  if (str === 'null' || str === 'undefined') return null;
  return <span className="thinking-process-detail-text">{str}</span>;
};

const SourcesList = ({ sources }) => {
  const [expanded, setExpanded] = useState(false);
  const visibleSources = expanded ? sources : sources.slice(0, 3);
  const hasMore = sources.length > 3;

  return (
    <div className="sources-section">
      <div
        className="sources-header"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="sources-icon">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
            <path d="M6.5 3H3.5C2.67 3 2 3.67 2 4.5V7.5C2 8.33 2.67 9 3.5 9H6.5C7.33 9 8 8.33 8 7.5V4.5C8 3.67 7.33 3 6.5 3Z" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M9.5 7H12.5C13.33 7 14 7.67 14 8.5V11.5C14 12.33 13.33 13 12.5 13H9.5C8.67 13 8 12.33 8 11.5V8.5C8 7.67 8.67 7 9.5 7Z" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
            <path d="M14 4.5L8 11.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/>
          </svg>
        </span>
        <span className="sources-title">参考来源</span>
        <span className="sources-count">{sources.length}</span>
        {hasMore && (
          <span className="sources-toggle">
            {expanded ? '收起' : `展开全部`}
          </span>
        )}
      </div>
      <div className="sources-list">
        {visibleSources.map((s, i) => (
          <div key={i} className="source-item">
            <span className="source-index">{i + 1}</span>
            <a
              className="source-link"
              href={s.url}
              target="_blank"
              rel="noopener noreferrer"
              title={s.url}
            >
              {s.title || s.url}
            </a>
            {s.title && s.url && (
              <span className="source-domain">
                {(() => {
                  try { return new URL(s.url).hostname.replace(/^www\./, ''); }
                  catch { return ''; }
                })()}
              </span>
            )}
          </div>
        ))}
      </div>
    </div>
  );
};

const MessageBubble = ({ message, onExport }) => {
  const isUser = message.role === 'user';
  const [thinkingExpanded, setThinkingExpanded] = useState(false);
  const [viewerUrl, setViewerUrl] = useState(null);
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
            {message.content && message.content !== 'null' ? message.content : ''}
          </ReactMarkdown>
        </div>
        {isUser && message.attachments && message.attachments.length > 0 && (
          <div className="message-attachments">
            {message.attachments.map((att, i) => {
              if (att.attachmentType === 'IMAGE') {
                return (
                  <img
                    key={i}
                    className="message-image"
                    src={att.thumbnailUrl || (att.attachmentId ? getThumbnailUrl(att.attachmentId) : '')}
                    alt={att.originalFilename || '图片'}
                    onClick={() => setViewerUrl(att.attachmentId ? getAttachmentUrl(att.attachmentId) : att.thumbnailUrl)}
                  />
                );
              }
              return (
                <div key={i} className="message-attachment-doc">
                  <span>{att.originalFilename || '文档'}</span>
                  {att.attachmentId && (
                    <a href={getAttachmentUrl(att.attachmentId)} target="_blank" rel="noopener noreferrer">下载</a>
                  )}
                </div>
              );
            })}
          </div>
        )}
        {!isUser && message.sources && message.sources.length > 0 && !message.isStreaming && (
          <SourcesList sources={message.sources} />
        )}
        {!isUser && message.content && !message.isStreaming && (
          <div className="message-actions">
            <button className="message-action-btn" onClick={() => onExport?.(message)}>
              导出
            </button>
          </div>
        )}
      </div>
      <FileViewerModal imageUrl={viewerUrl} onClose={() => setViewerUrl(null)} />
    </div>
  );
};

export default MessageBubble;
