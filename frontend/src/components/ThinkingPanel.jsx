import { useEffect, useMemo, useRef, useState } from 'react';
import ChainNodeItem from './ChainNodeItem';
import '../styles/global.css';

const KIND_LABELS = {
  SUPERVISOR: '调度',
  WORKER: '智能体',
  LLM: '模型',
  TOOL: '工具',
  RAG: '检索',
  MEMORY: '记忆',
  STATUS: '状态',
  ERROR: '错误',
};

const KIND_STYLES = {
  SUPERVISOR: { color: '#4b5563', bg: '#f3f4f6', border: '#e5e7eb' },
  WORKER: { color: '#2563eb', bg: '#eff6ff', border: '#bfdbfe' },
  LLM: { color: '#7c3aed', bg: '#f5f3ff', border: '#ddd6fe' },
  TOOL: { color: '#0f766e', bg: '#f0fdfa', border: '#99f6e4' },
  RAG: { color: '#047857', bg: '#ecfdf5', border: '#a7f3d0' },
  MEMORY: { color: '#b45309', bg: '#fffbeb', border: '#fde68a' },
  STATUS: { color: '#6b7280', bg: '#f9fafb', border: '#e5e7eb' },
  ERROR: { color: '#dc2626', bg: '#fef2f2', border: '#fecaca' },
};

const PHASE_TO_STATUS = {
  STARTED: 'STARTED',
  RUNNING: 'RUNNING',
  COMPLETED: 'COMPLETED',
  ERROR: 'ERROR',
};

const formatDuration = (ms) => {
  if (!ms) return '';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
};

const formatValue = (value) => {
  if (value == null || value === '') return null;
  if (Array.isArray(value)) {
    if (value.length === 0) return null;
    return value.map((item, index) => (
      <div key={index} className="trace-list-item">
        {formatValue(item)}
      </div>
    ));
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value).filter(([, v]) => v != null && v !== '');
    if (entries.length === 0) return null;
    return entries.map(([k, v]) => (
      <div key={k} className="trace-detail-kv">
        <span className="trace-detail-key">{k}</span>
        <span className="trace-detail-value">
          {typeof v === 'object' ? formatValue(v) : String(v)}
        </span>
      </div>
    ));
  }
  return String(value);
};

const mergeTraceEvents = (events = []) => {
  const bySpan = new Map();
  events.forEach((event, index) => {
    const spanId = event.spanId || event.eventId || `trace-${index}`;
    const existing = bySpan.get(spanId) || {
      spanId,
      children: [],
      firstIndex: index,
    };
    bySpan.set(spanId, {
      ...existing,
      ...event,
      spanId,
      parentSpanId: event.parentSpanId ?? existing.parentSpanId,
      input: event.input ?? existing.input,
      output: event.output ?? existing.output,
      metadata: event.metadata ?? existing.metadata,
      error: event.error ?? existing.error,
      startedAt: event.startedAt ?? existing.startedAt,
      endedAt: event.endedAt ?? existing.endedAt,
      durationMs: event.durationMs ?? existing.durationMs,
      latestIndex: index,
    });
  });

  const nodes = Array.from(bySpan.values()).sort((a, b) => (a.firstIndex ?? 0) - (b.firstIndex ?? 0));
  const nodeMap = new Map(nodes.map((node) => [node.spanId, { ...node, children: [] }]));
  const roots = [];

  nodeMap.forEach((node) => {
    if (node.parentSpanId && nodeMap.has(node.parentSpanId)) {
      nodeMap.get(node.parentSpanId).children.push(node);
    } else {
      roots.push(node);
    }
  });

  const sortTree = (items) => {
    items.sort((a, b) => (a.firstIndex ?? 0) - (b.firstIndex ?? 0));
    items.forEach((item) => sortTree(item.children));
  };
  sortTree(roots);
  return roots;
};

const flattenTrace = (nodes) => {
  const rows = [];
  const walk = (items) => {
    items.forEach((item) => {
      rows.push(item);
      walk(item.children || []);
    });
  };
  walk(nodes);
  return rows;
};

const TraceNodeItem = ({ node, depth = 0 }) => {
  const [expanded, setExpanded] = useState(depth < 1);
  const style = KIND_STYLES[node.kind] || KIND_STYLES.STATUS;
  const status = PHASE_TO_STATUS[node.phase] || 'RUNNING';
  const hasChildren = node.children && node.children.length > 0;
  const hasDetails = node.input || node.output || node.metadata || node.error;
  const isRunning = status === 'STARTED' || status === 'RUNNING';

  return (
    <div className={`trace-node trace-node-depth-${Math.min(depth, 4)} ${isRunning ? 'trace-node-running' : ''}`}>
      <div className="trace-node-main" style={{ paddingLeft: `${depth * 18}px` }}>
        <button
          className="trace-node-expander"
          onClick={() => setExpanded(!expanded)}
          disabled={!hasChildren && !hasDetails}
          title={expanded ? '收起' : '展开'}
        >
          {(hasChildren || hasDetails) ? (expanded ? '▾' : '▸') : ''}
        </button>
        <span
          className={`trace-node-dot trace-node-dot-${status.toLowerCase()}`}
          style={{ borderColor: status === 'ERROR' ? '#dc2626' : style.color, backgroundColor: style.bg }}
        />
        <div className="trace-node-body">
          <div className="trace-node-title-row">
            <span className="trace-node-title">{node.name || KIND_LABELS[node.kind] || '执行步骤'}</span>
            <span
              className="trace-node-kind"
              style={{ color: style.color, backgroundColor: style.bg, borderColor: style.border }}
            >
              {KIND_LABELS[node.kind] || node.kind || '事件'}
            </span>
            {node.toolName && <span className="trace-node-chip">{node.toolName}</span>}
            {node.durationMs != null && node.durationMs > 0 && (
              <span className="trace-node-duration">{formatDuration(node.durationMs)}</span>
            )}
          </div>
          {node.summary && <div className="trace-node-summary">{node.summary}</div>}
        </div>
      </div>

      {expanded && hasDetails && (
        <div className="trace-node-details" style={{ marginLeft: `${depth * 18 + 34}px` }}>
          {node.error && (
            <div className="trace-detail-section trace-detail-error">
              <span className="trace-detail-label">错误</span>
              <div className="trace-detail-body">{node.error}</div>
            </div>
          )}
          {node.input && formatValue(node.input) && (
            <div className="trace-detail-section">
              <span className="trace-detail-label">输入摘要</span>
              <div className="trace-detail-body">{formatValue(node.input)}</div>
            </div>
          )}
          {node.output && formatValue(node.output) && (
            <div className="trace-detail-section">
              <span className="trace-detail-label">输出摘要</span>
              <div className="trace-detail-body">{formatValue(node.output)}</div>
            </div>
          )}
          {node.metadata && formatValue(node.metadata) && (
            <div className="trace-detail-section">
              <span className="trace-detail-label">元数据</span>
              <div className="trace-detail-body">{formatValue(node.metadata)}</div>
            </div>
          )}
        </div>
      )}

      {expanded && hasChildren && (
        <div className="trace-node-children">
          {node.children.map((child) => (
            <TraceNodeItem key={child.spanId || child.eventId} node={child} depth={depth + 1} />
          ))}
        </div>
      )}
    </div>
  );
};

const LegacyChainPanel = ({ chainNodes, isStreaming }) => {
  const completedCount = chainNodes.filter((n) => n.status === 'COMPLETED').length;
  const errorCount = chainNodes.filter((n) => n.status === 'ERROR').length;
  const runningCount = chainNodes.filter((n) => n.status === 'STARTED' || n.status === 'RUNNING').length;

  return (
    <>
      <div className="thinking-panel-stats">
        {isStreaming && runningCount > 0 ? '运行中' : `${completedCount} 步完成`}
        {errorCount > 0 && ` · ${errorCount} 错误`}
      </div>
      {chainNodes.map((node, index) => (
        <ChainNodeItem
          key={node.nodeId || index}
          node={node}
          isLast={index === chainNodes.length - 1}
        />
      ))}
    </>
  );
};

const ThinkingPanel = ({ traceEvents = [], chainNodes = [], isStreaming }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [isCollapsed, setIsCollapsed] = useState(true);
  const [filter, setFilter] = useState('ALL');
  const contentRef = useRef(null);

  const traceTree = useMemo(() => mergeTraceEvents(traceEvents), [traceEvents]);
  const flatTrace = useMemo(() => flattenTrace(traceTree), [traceTree]);
  const hasTrace = flatTrace.length > 0;
  const hasLegacy = chainNodes.length > 0;
  const hasNodes = hasTrace || hasLegacy;

  const filteredTree = useMemo(() => {
    if (filter === 'ALL') return traceTree;
    const keepTree = (nodes) => nodes
      .map((node) => {
        const children = keepTree(node.children || []);
        const keepSelf = filter === 'ERROR' ? node.phase === 'ERROR' || node.kind === 'ERROR' : node.kind === filter;
        if (keepSelf || children.length > 0) return { ...node, children };
        return null;
      })
      .filter(Boolean);
    return keepTree(traceTree);
  }, [filter, traceTree]);

  const stats = useMemo(() => {
    const completed = flatTrace.filter((n) => n.phase === 'COMPLETED').length;
    const errors = flatTrace.filter((n) => n.phase === 'ERROR' || n.kind === 'ERROR').length;
    const running = flatTrace.filter((n) => n.phase === 'STARTED' || n.phase === 'RUNNING').length;
    const tools = flatTrace.filter((n) => n.kind === 'TOOL').length;
    return { completed, errors, running, tools };
  }, [flatTrace]);

  useEffect(() => {
    if (contentRef.current && isExpanded && !isCollapsed) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight;
    }
  }, [traceEvents, chainNodes, isExpanded, isCollapsed]);

  if (!hasNodes) return null;

  const latest = hasTrace ? flatTrace[flatTrace.length - 1] : chainNodes[chainNodes.length - 1];
  const latestStatusText = hasTrace
    ? `${latest?.name || '执行中'}${latest?.phase === 'COMPLETED' ? ' 已完成' : latest?.phase === 'ERROR' ? ' 失败' : '...'}`
    : `${latest?.nodeName || ''}${latest?.status === 'COMPLETED' ? ' 已完成' : latest?.status === 'ERROR' ? ' 失败' : '...'}`;

  return (
    <div className={`thinking-panel trace-panel ${isCollapsed ? 'thinking-panel-collapsed' : ''}`}>
      <div className="thinking-panel-header" onClick={() => setIsCollapsed(!isCollapsed)}>
        <div className="thinking-panel-header-left">
          <span className="thinking-panel-arrow">{isCollapsed ? '▶' : '▼'}</span>
          <span className="thinking-panel-title">执行轨迹</span>
          {isCollapsed && (
            <span className="thinking-panel-collapsed-summary">{latestStatusText}</span>
          )}
          {!isCollapsed && isStreaming && stats.running > 0 && (
            <span className="thinking-panel-live-badge">
              <span className="thinking-panel-live-dot" />
              运行中
            </span>
          )}
          {!isCollapsed && hasTrace && (
            <span className="thinking-panel-stats">
              {stats.completed} 步完成
              {stats.tools > 0 && ` · ${stats.tools} 次工具`}
              {stats.errors > 0 && ` · ${stats.errors} 错误`}
            </span>
          )}
        </div>
        {!isCollapsed && hasTrace && flatTrace.length > 2 && (
          <button
            className="thinking-panel-toggle-btn"
            onClick={(e) => {
              e.stopPropagation();
              setIsExpanded(!isExpanded);
            }}
          >
            {isExpanded ? '紧凑显示' : '展开详情'}
          </button>
        )}
      </div>

      {!isCollapsed && hasTrace && (
        <div className="trace-filter-row" onClick={(e) => e.stopPropagation()}>
          {[
            ['ALL', '全部'],
            ['WORKER', '智能体'],
            ['LLM', '模型'],
            ['TOOL', '工具'],
            ['ERROR', '错误'],
          ].map(([value, label]) => (
            <button
              key={value}
              className={`trace-filter-btn ${filter === value ? 'active' : ''}`}
              onClick={() => setFilter(value)}
            >
              {label}
            </button>
          ))}
        </div>
      )}

      {!isCollapsed && (
        <div
          className={`thinking-panel-content ${isExpanded ? '' : 'thinking-panel-content-compact'}`}
          ref={contentRef}
        >
          {hasTrace ? (
            filteredTree.length > 0 ? (
              filteredTree.map((node) => (
                <TraceNodeItem key={node.spanId || node.eventId} node={node} />
              ))
            ) : (
              <div className="trace-empty">当前筛选下暂无轨迹事件</div>
            )
          ) : (
            <LegacyChainPanel chainNodes={chainNodes} isStreaming={isStreaming} />
          )}
          {isStreaming && ((hasTrace && stats.running > 0) || (!hasTrace && hasLegacy)) && (
            <div className="thinking-panel-loading">
              <div className="thinking-panel-loading-dots">
                <span />
                <span />
                <span />
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default ThinkingPanel;
