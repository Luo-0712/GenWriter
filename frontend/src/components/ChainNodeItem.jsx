import { useState } from 'react';
import '../styles/global.css';

const NODE_TYPE_CONFIG = {
  PLANNING:  { icon: '◈', color: '#7c3aed', bg: '#f5f3ff', border: '#ddd6fe', label: '规划' },
  THINKING:  { icon: '◉', color: '#4f46e5', bg: '#eef2ff', border: '#c7d2fe', label: '思考' },
  TOOL_CALL: { icon: '⟐', color: '#0ea5e9', bg: '#f0f9ff', border: '#bae6fd', label: '工具' },
  EXECUTION: { icon: '▶', color: '#475569', bg: '#f8fafc', border: '#e2e8f0', label: '执行' },
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

const formatOutput = (output) => {
  if (!output) return null;
  if (typeof output === 'string') return output;
  if (typeof output === 'object') {
    return Object.entries(output).map(([k, v]) => (
      <div key={k} className="chain-node-detail-kv">
        <span className="chain-node-detail-key">{k}</span>
        <span className="chain-node-detail-value">
          {typeof v === 'object' ? JSON.stringify(v) : String(v)}
        </span>
      </div>
    ));
  }
  return String(output);
};

const ChainNodeItem = ({ node, isLast }) => {
  const [expanded, setExpanded] = useState(false);
  const typeConfig = NODE_TYPE_CONFIG[node.nodeType] || NODE_TYPE_CONFIG.EXECUTION;
  const statusConfig = STATUS_CONFIG[node.status] || STATUS_CONFIG.STARTED;
  const hasDetails = node.input || node.output || node.error;
  const isRunning = node.status === 'STARTED' || node.status === 'RUNNING';

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
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
              <path d="M2.5 6.5L4.5 8.5L9.5 3.5" stroke={typeConfig.color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          ) : node.status === 'ERROR' ? (
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
              <path d="M2 2L8 8M8 2L2 8" stroke="#ef4444" strokeWidth="1.5" strokeLinecap="round"/>
            </svg>
          ) : (
            <span style={{ color: typeConfig.color, fontSize: '10px' }}>{typeConfig.icon}</span>
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
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none" style={{ marginRight: '3px', opacity: 0.5 }}>
                  <circle cx="6" cy="6" r="5" stroke="currentColor" strokeWidth="1"/>
                  <path d="M6 3V6L8 7.5" stroke="currentColor" strokeWidth="1" strokeLinecap="round"/>
                </svg>
                {formatDuration(node.duration)}
              </span>
            )}
            {hasDetails && (
              <span className={`chain-node-expand-icon ${expanded ? 'expanded' : ''}`}>
                <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                  <path d={expanded ? 'M2 7L5 4L8 7' : 'M2 3.5L5 6.5L8 3.5'} stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              </span>
            )}
          </div>
        </div>
        {expanded && hasDetails && (
          <div className="chain-node-details">
            {node.error && (
              <div className="chain-node-error-section">
                <span className="chain-node-detail-label">错误信息</span>
                <div className="chain-node-error-text">{node.error}</div>
              </div>
            )}
            {node.input && (
              <div className="chain-node-detail-section">
                <span className="chain-node-detail-label">输入参数</span>
                <div className="chain-node-detail-body">{formatOutput(node.input)}</div>
              </div>
            )}
            {node.output && (
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
