import { useState } from 'react';
import '../styles/global.css';

const NODE_TYPE_CONFIG = {
  PLANNING:  { icon: '◈', color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb', label: '规划' },
  THINKING:  { icon: '◉', color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb', label: '思考' },
  TOOL_CALL: { icon: '⟐', color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb', label: '工具' },
  EXECUTION: { icon: '▶', color: '#6b7280', bg: '#f3f4f6', border: '#e5e7eb', label: '执行' },
  RESULT:    { icon: '✓', color: '#10b981', bg: '#ecfdf5', border: '#a7f3d0', label: '结果' },
  ERROR:     { icon: '✗', color: '#ef4444', bg: '#fef2f2', border: '#fecaca', label: '错误' },
};

const STATUS_CONFIG = {
  STARTED: { label: '开始', dotClass: 'chain-node-dot-started' },
  RUNNING: { label: '运行中', dotClass: 'chain-node-dot-running' },
  COMPLETED: { label: '完成', dotClass: 'chain-node-dot-completed' },
  ERROR: { label: '错误', dotClass: 'chain-node-dot-error' },
};

const formatDuration = (ms) => {
  if (!ms) return '';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
};

const formatNum = (n) => {
  if (n == null) return '';
  return Number(n).toLocaleString();
};

const WORKER_LABELS = {
  intent_recognition: '意图识别',
  outline: '大纲生成',
  draft: '正文写作',
  polish: '润色优化',
  review: '内容评审',
  researcher: '网络调研',
  direct_answer: '直接回答',
};

const VERDICT_LABELS = {
  PASS: '通过',
  REVISE_DRAFT: '需修改草稿',
  REVISE_POLISH: '需修改润色',
};

const getOutputSummary = (node) => {
  if (!node || node.status !== 'COMPLETED') return null;
  const { nodeType, output } = node;
  if (!output || typeof output !== 'object') return null;

  // Planning node: show planned steps
  if (nodeType === 'PLANNING' && output.steps && Array.isArray(output.steps)) {
    const stepLabels = output.steps.map(s => WORKER_LABELS[s] || s);
    return `计划执行: ${stepLabels.join(' → ')}`;
  }
  if (nodeType === 'PLANNING' && output.reasoning) {
    return output.reasoning;
  }

  // Worker execution wrapper from SupervisorNode
  if (output.worker) {
    const workerName = output.worker;
    if (workerName === 'intent_recognition' && output.intent) {
      const type = output.writingType || '';
      return `识别为 ${output.intent}${type ? ` (${type})` : ''}`;
    }
    if (workerName === 'researcher' && output.searchRounds != null) {
      const parts = [`执行 ${output.searchRounds} 次搜索`];
      if (output.sourcesCount) parts.push(`${output.sourcesCount} 个来源`);
      return parts.join('，');
    }
    if (workerName === 'review' && output.verdict) {
      const verdict = VERDICT_LABELS[output.verdict] || output.verdict;
      const fb = output.feedback ? ` — ${output.feedback}` : '';
      return `${verdict}${fb}`;
    }
    if (workerName === 'draft' && output.draftLength != null) return `生成草稿 ${formatNum(output.draftLength)} 字`;
    if (workerName === 'polish' && output.polishedLength != null) return `润色完成 ${formatNum(output.polishedLength)} 字`;
    if (workerName === 'outline' && output.outlineLength != null) return `生成大纲 ${formatNum(output.outlineLength)} 字`;
    if (workerName === 'direct_answer' && output.outputLength != null) return `生成回答 ${formatNum(output.outputLength)} 字`;
    if (output.draftLength != null) return `生成草稿 ${formatNum(output.draftLength)} 字`;
    if (output.polishedLength != null) return `润色完成 ${formatNum(output.polishedLength)} 字`;
    if (output.outlineLength != null) return `生成大纲 ${formatNum(output.outlineLength)} 字`;
    if (output.outputLength != null) return `生成回答 ${formatNum(output.outputLength)} 字`;
  }

  // Direct worker outputs
  if (nodeType === 'THINKING' && output.intent) {
    const type = output.writingType || '';
    return `识别为 ${output.intent}${type ? ` (${type})` : ''}`;
  }
  if (nodeType === 'THINKING' && output.score != null) {
    const verdict = VERDICT_LABELS[output.verdict] || output.verdict || '';
    const fb = output.feedback ? ` — ${output.feedback}` : '';
    return `评分 ${output.score}/10${verdict ? ` — ${verdict}` : ''}${fb}`;
  }
  if (nodeType === 'TOOL_CALL' && output.searchRounds != null) {
    const parts = [`执行 ${output.searchRounds} 次搜索`];
    if (output.reportLength) parts.push(`生成 ${formatNum(output.reportLength)} 字报告`);
    if (output.sourcesCount) parts.push(`${output.sourcesCount} 个来源`);
    return parts.join('，');
  }
  if (output.length != null) {
    return `${formatNum(output.length)} 字`;
  }
  if (output.newSteps && Array.isArray(output.newSteps)) {
    const stepLabels = output.newSteps.map(s => WORKER_LABELS[s] || s);
    return `调整为: ${stepLabels.join(' → ')}`;
  }

  return null;
};

const formatOutput = (output) => {
  if (output == null) return null;
  if (typeof output === 'string') return output || null;
  if (typeof output === 'object') {
    const entries = Object.entries(output).filter(([, v]) => v != null);
    if (entries.length === 0) return null;
    return entries.map(([k, v]) => (
      <div key={k} className="chain-node-detail-kv">
        <span className="chain-node-detail-key">{k}</span>
        <span className="chain-node-detail-value">
          {typeof v === 'object' ? JSON.stringify(v) : String(v)}
        </span>
      </div>
    ));
  }
  const str = String(output);
  return str === 'null' || str === 'undefined' ? null : str;
};

const ChainNodeItem = ({ node, isLast }) => {
  const [expanded, setExpanded] = useState(false);
  const typeConfig = NODE_TYPE_CONFIG[node.nodeType] || NODE_TYPE_CONFIG.EXECUTION;
  const statusConfig = STATUS_CONFIG[node.status] || STATUS_CONFIG.STARTED;
  const hasDetails = node.input || node.output || node.error;
  const isRunning = node.status === 'STARTED' || node.status === 'RUNNING';
  const outputSummary = getOutputSummary(node);

  const dotStyle = {
    borderColor: node.status === 'ERROR' ? '#ef4444' : typeConfig.color,
    backgroundColor: typeConfig.bg,
    boxShadow: node.status === 'RUNNING' || node.status === 'STARTED'
      ? `0 0 0 3px ${typeConfig.bg}`
      : 'none',
  };

  return (
    <div className={`chain-node-item ${isRunning ? 'chain-node-running' : ''}`}>
      <div className="chain-node-timeline">
        <div
          className={`chain-node-dot ${statusConfig.dotClass}`}
          style={dotStyle}
        >
          {node.status === 'COMPLETED' ? (
            <svg width="10" height="10" viewBox="0 0 12 12" fill="none">
              <path d="M2.5 6.5L4.5 8.5L9.5 3.5" stroke={typeConfig.color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          ) : node.status === 'ERROR' ? (
            <svg width="8" height="8" viewBox="0 0 10 10" fill="none">
              <path d="M2 2L8 8M8 2L2 8" stroke="#ef4444" strokeWidth="1.5" strokeLinecap="round"/>
            </svg>
          ) : (
            <span style={{ color: typeConfig.color, fontSize: '9px' }}>{typeConfig.icon}</span>
          )}
        </div>
        {!isLast && (
          <div
            className="chain-node-line"
            style={{
              background: node.status === 'COMPLETED'
                ? `linear-gradient(to bottom, ${typeConfig.color}40, var(--border-color))`
                : undefined
            }}
          />
        )}
      </div>
      <div className="chain-node-content">
        <div className="chain-node-header" onClick={() => hasDetails && setExpanded(!expanded)}>
          <div className="chain-node-title-row">
            <span className="chain-node-name">{node.nodeName}</span>
            <span
              className="chain-node-type-badge"
              style={{
                color: typeConfig.color,
                backgroundColor: typeConfig.bg,
                borderColor: typeConfig.border,
              }}
            >
              {typeConfig.label}
            </span>
            {isRunning && (
              <span className="chain-node-running-indicator">
                <span className="chain-pulse" style={{ backgroundColor: typeConfig.color }} />
              </span>
            )}
          </div>
          <div className="chain-node-meta">
            {node.duration != null && node.duration > 0 && (
              <span className="chain-node-duration">
                <svg width="10" height="10" viewBox="0 0 12 12" fill="none" style={{ marginRight: '2px', opacity: 0.4 }}>
                  <circle cx="6" cy="6" r="5" stroke="currentColor" strokeWidth="1"/>
                  <path d="M6 3V6L8 7.5" stroke="currentColor" strokeWidth="1" strokeLinecap="round"/>
                </svg>
                {formatDuration(node.duration)}
              </span>
            )}
            {hasDetails && (
              <span className={`chain-node-expand-icon ${expanded ? 'expanded' : ''}`}>
                <svg width="8" height="8" viewBox="0 0 10 10" fill="none">
                  <path d={expanded ? 'M2 7L5 4L8 7' : 'M2 3.5L5 6.5L8 3.5'} stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              </span>
            )}
          </div>
        </div>
        {outputSummary && (
          <div className="chain-node-summary">{outputSummary}</div>
        )}
        {expanded && hasDetails && (
          <div className="chain-node-details">
            {node.error && (
              <div className="chain-node-error-section">
                <span className="chain-node-detail-label">错误信息</span>
                <div className="chain-node-error-text">{node.error}</div>
              </div>
            )}
            {node.input && formatOutput(node.input) && (
              <div className="chain-node-detail-section">
                <span className="chain-node-detail-label">输入参数</span>
                <div className="chain-node-detail-body">{formatOutput(node.input)}</div>
              </div>
            )}
            {node.output && formatOutput(node.output) && (
              <div className="chain-node-detail-section">
                <span className="chain-node-detail-label">输出结果</span>
                <div className="chain-node-detail-body">{formatOutput(node.output)}</div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default ChainNodeItem;
