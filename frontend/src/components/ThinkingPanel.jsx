import { useState, useEffect, useRef, useMemo } from 'react';
import ChainNodeItem from './ChainNodeItem';
import '../styles/global.css';

const ThinkingPanel = ({ chainNodes, isStreaming }) => {
  const [isExpanded, setIsExpanded] = useState(true);
  const [isCollapsed, setIsCollapsed] = useState(false);
  const contentRef = useRef(null);

  const hasNodes = chainNodes && chainNodes.length > 0;
  const completedCount = useMemo(() =>
    chainNodes ? chainNodes.filter((n) => n.status === 'COMPLETED').length : 0,
    [chainNodes]
  );
  const errorCount = useMemo(() =>
    chainNodes ? chainNodes.filter((n) => n.status === 'ERROR').length : 0,
    [chainNodes]
  );
  const runningCount = useMemo(() =>
    chainNodes ? chainNodes.filter((n) => n.status === 'STARTED' || n.status === 'RUNNING').length : 0,
    [chainNodes]
  );

  useEffect(() => {
    if (contentRef.current && isExpanded && !isCollapsed) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight;
    }
  }, [chainNodes, isExpanded, isCollapsed]);

  if (!hasNodes) return null;

  if (isStreaming && hasNodes && isCollapsed) {
    setIsCollapsed(false);
  }

  const latestNode = chainNodes[chainNodes.length - 1];
  const latestStatusText = latestNode
    ? `${latestNode.nodeName}${latestNode.status === 'COMPLETED' ? ' ✓' : latestNode.status === 'ERROR' ? ' ✗' : '...'}`
    : '';

  return (
    <div className={`thinking-panel ${isCollapsed ? 'thinking-panel-collapsed' : ''}`}>
      <div className="thinking-panel-header" onClick={() => setIsCollapsed(!isCollapsed)}>
        <div className="thinking-panel-header-left">
          <span className="thinking-panel-arrow">{isCollapsed ? '▶' : '▼'}</span>
          <span className="thinking-panel-title">思维链</span>
          {isStreaming && runningCount > 0 && (
            <span className="thinking-panel-live-badge">
              <span className="thinking-panel-live-dot" />
              运行中
            </span>
          )}
          {!isStreaming && completedCount > 0 && (
            <span className="thinking-panel-stats">
              {completedCount} 步完成
              {errorCount > 0 && ` · ${errorCount} 错误`}
            </span>
          )}
        </div>
        {isCollapsed && (
          <span className="thinking-panel-collapsed-summary">{latestStatusText}</span>
        )}
        {!isCollapsed && chainNodes.length > 2 && (
          <button
            className="thinking-panel-toggle-btn"
            onClick={(e) => {
              e.stopPropagation();
              setIsExpanded(!isExpanded);
            }}
          >
            {isExpanded ? '收起详情' : '展开详情'}
          </button>
        )}
      </div>
      {!isCollapsed && (
        <div
          className={`thinking-panel-content ${isExpanded ? '' : 'thinking-panel-content-compact'}`}
          ref={contentRef}
        >
          {chainNodes.map((node, index) => (
            <ChainNodeItem
              key={node.nodeId || index}
              node={node}
              isLast={index === chainNodes.length - 1}
            />
          ))}
          {isStreaming && runningCount > 0 && (
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
