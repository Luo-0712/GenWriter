import { useState } from 'react';
import '../styles/global.css';

const NODE_TYPE_CONFIG = {
  PLANNING: { icon: '◈', color: '#7c3aed', bg: '#ede9fe', label: '规划' },
  THINKING: { icon: '◉', color: '#2563eb', bg: '#dbeafe', label: '思考' },
  TOOL_CALL: { icon: '⟐', color: '#0891b2', bg: '#cffafe', label: '工具调用' },
  EXECUTION: { icon: '▶', color: '#ea580c', bg: '#fff7ed', label: '执行' },
  RESULT: { icon: '✓', color: '#16a34a', bg: '#dcfce7', label: '结果' },
  ERROR: { icon: '✗', color: '#dc2626', bg: '#fee2e2', label: '错误' },
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

  return (
    <div className={`chain-node-item ${isRunning ? 'chain-node-running' : ''}`}>
      <div className="chain-node-timeline">
        <div
          className={`chain-node-dot ${statusConfig.dotClass}`}
          style={{ borderColor: typeConfig.color }}
        >
          {node.status === 'COMPLETED' ? (
            <span style={{ color: typeConfig.color }}>✓</span>
          ) : node.status === 'ERROR' ? (
            <span style={{ color: '#dc2626' }}>✗</span>
          ) : (
            <span style={{ color: typeConfig.color }}>{typeConfig.icon}</span>
          )}
        </div>
        {!isLast && <div className="chain-node-line" />}
      </div>
      <div className="chain-node-content">
        <div className="chain-node-header" onClick={() => hasDetails && setExpanded(!expanded)}>
          <div className="chain-node-title-row">
            <span className="chain-node-name">{node.nodeName}</span>
            <span
              className="chain-node-type-badge"
              style={{ color: typeConfig.color, backgroundColor: typeConfig.bg }}
            >
              {typeConfig.label}
            </span>
            {isRunning && (
              <span className="chain-node-running-indicator">
                <span className="chain-pulse" />
              </span>
            )}
          </div>
          <div className="chain-node-meta">
            {node.duration != null && node.duration > 0 && (
              <span className="chain-node-duration">{formatDuration(node.duration)}</span>
            )}
            {hasDetails && (
              <span className="chain-node-expand-icon">{expanded ? '▲' : '▼'}</span>
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
