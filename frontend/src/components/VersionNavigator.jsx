import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronLeft, ChevronRight, History, X } from 'lucide-react';
import { formatVersionTime, getDocumentSourceLabel } from '../utils/version';

const sortVersions = (documents) => [...documents].sort((left, right) => {
  const versionDifference = (right.version || 0) - (left.version || 0);
  if (versionDifference !== 0) return versionDifference;
  return new Date(right.updatedAt || 0).getTime() - new Date(left.updatedAt || 0).getTime();
});

const visibleVersionsFor = (documents, selectedIndex) => {
  if (documents.length <= 4) return documents;

  const indices = new Set([0, Math.max(selectedIndex, 0)]);
  let distance = 1;
  while (indices.size < 4 && distance < documents.length) {
    const newerIndex = selectedIndex - distance;
    const olderIndex = selectedIndex + distance;
    if (newerIndex >= 0) indices.add(newerIndex);
    if (indices.size < 4 && olderIndex < documents.length) indices.add(olderIndex);
    distance += 1;
  }

  for (let index = 0; indices.size < 4 && index < documents.length; index += 1) {
    indices.add(index);
  }

  return [...indices]
    .sort((left, right) => left - right)
    .map((index) => documents[index]);
};

const VersionBadge = ({ tone = '', children }) => (
  <span className={`version-badge${tone ? ` ${tone}` : ''}`}>{children}</span>
);

const VersionNavigator = ({ documents, selectedId, dirty, loading, onSelect }) => {
  const [historyOpen, setHistoryOpen] = useState(false);
  const drawerRef = useRef(null);
  const closeButtonRef = useRef(null);
  const previousFocusRef = useRef(null);
  const sortedDocuments = useMemo(() => sortVersions(documents), [documents]);
  const selectedIndex = sortedDocuments.findIndex((document) => document.id === selectedId);
  const selectedDocument = selectedIndex >= 0 ? sortedDocuments[selectedIndex] : null;
  const latestDocument = sortedDocuments[0] || null;
  const visibleDocuments = visibleVersionsFor(sortedDocuments, selectedIndex);
  const newerDocument = selectedIndex > 0 ? sortedDocuments[selectedIndex - 1] : null;
  const olderDocument = selectedIndex >= 0 && selectedIndex < sortedDocuments.length - 1
    ? sortedDocuments[selectedIndex + 1]
    : null;

  useEffect(() => {
    if (!historyOpen) return undefined;

    previousFocusRef.current = document.activeElement;
    const focusTimer = window.requestAnimationFrame(() => closeButtonRef.current?.focus());

    return () => {
      window.cancelAnimationFrame(focusTimer);
      previousFocusRef.current?.focus?.();
    };
  }, [historyOpen]);

  const closeHistory = () => setHistoryOpen(false);

  const handleDrawerKeyDown = (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeHistory();
      return;
    }
    if (event.key !== 'Tab') return;

    const focusable = drawerRef.current?.querySelectorAll('button:not(:disabled)');
    if (!focusable?.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  const selectFromHistory = (document) => {
    closeHistory();
    onSelect(document);
  };

  if (loading) {
    return (
      <section className="version-navigator" aria-label="版本导航">
        <span className="workbench-muted">加载版本中...</span>
      </section>
    );
  }

  if (!selectedDocument) {
    return (
      <section className="version-navigator empty" aria-label="版本导航">
        <div>
          <span className="version-kicker">当前版本</span>
          <strong>尚未创建版本</strong>
        </div>
        <span className="workbench-muted">保存文稿后即可开始迭代</span>
      </section>
    );
  }

  return (
    <section className="version-navigator" aria-label="版本导航">
      <div className="version-summary">
        <div className="version-summary-main">
          <span className="version-kicker">当前版本</span>
          <div className="version-summary-heading">
            <strong>V{selectedDocument.version}</strong>
            <VersionBadge tone="current">当前</VersionBadge>
            {selectedDocument.id === latestDocument?.id && <VersionBadge tone="latest">最新</VersionBadge>}
            {dirty && <VersionBadge tone="dirty">未保存</VersionBadge>}
          </div>
          <div className="version-summary-meta" title={`${getDocumentSourceLabel(selectedDocument)} · ${formatVersionTime(selectedDocument.updatedAt)}`}>
            {getDocumentSourceLabel(selectedDocument)} · {formatVersionTime(selectedDocument.updatedAt)}
          </div>
        </div>

        <div className="version-nav-actions">
          <button
            className="version-icon-action"
            type="button"
            disabled={!newerDocument}
            onClick={() => onSelect(newerDocument)}
            title={newerDocument ? `查看更新版本 V${newerDocument.version}` : '已经是最新版本'}
            aria-label={newerDocument ? `查看更新版本 V${newerDocument.version}` : '已经是最新版本'}
          >
            <ChevronLeft size={17} />
          </button>
          <button
            className="version-icon-action"
            type="button"
            disabled={!olderDocument}
            onClick={() => onSelect(olderDocument)}
            title={olderDocument ? `查看更早版本 V${olderDocument.version}` : '已经是最早版本'}
            aria-label={olderDocument ? `查看更早版本 V${olderDocument.version}` : '已经是最早版本'}
          >
            <ChevronRight size={17} />
          </button>
          <button
            className="version-history-button"
            type="button"
            onClick={() => setHistoryOpen(true)}
            title={`查看全部 ${sortedDocuments.length} 个版本`}
            aria-haspopup="dialog"
          >
            <History size={16} />
            <span>历史 {sortedDocuments.length}</span>
          </button>
        </div>
      </div>

      <div className="version-neighbors" aria-label="相邻版本">
        {visibleDocuments.map((document) => {
          const isCurrent = document.id === selectedId;
          const isLatest = document.id === latestDocument?.id;
          return (
            <button
              key={document.id}
              type="button"
              className={`version-chip${isCurrent ? ' active' : ''}`}
              onClick={() => onSelect(document)}
              title={`${document.title || '未命名文稿'} · ${getDocumentSourceLabel(document)}`}
              aria-current={isCurrent ? 'page' : undefined}
            >
              <span>V{document.version}</span>
              <small>{isCurrent ? '当前' : isLatest ? '最新' : getDocumentSourceLabel(document)}</small>
            </button>
          );
        })}
      </div>

      {historyOpen && (
        <div
          className="version-drawer-layer"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeHistory();
          }}
        >
          <div
            ref={drawerRef}
            className="version-history-drawer"
            role="dialog"
            aria-modal="true"
            aria-labelledby="version-history-title"
            onKeyDown={handleDrawerKeyDown}
          >
            <div className="version-history-header">
              <div>
                <span className="version-kicker">版本记录</span>
                <h3 id="version-history-title">全部版本</h3>
              </div>
              <button
                ref={closeButtonRef}
                className="version-icon-action"
                type="button"
                onClick={closeHistory}
                title="关闭版本历史"
                aria-label="关闭版本历史"
              >
                <X size={18} />
              </button>
            </div>

            <div className="version-history-list">
              {sortedDocuments.map((document) => {
                const isCurrent = document.id === selectedId;
                const isLatest = document.id === latestDocument?.id;
                return (
                  <button
                    key={document.id}
                    type="button"
                    className={`version-history-item${isCurrent ? ' active' : ''}`}
                    onClick={() => selectFromHistory(document)}
                    aria-current={isCurrent ? 'page' : undefined}
                  >
                    <span className="version-history-number">V{document.version}</span>
                    <span className="version-history-copy">
                      <span className="version-history-title" title={document.title || '未命名文稿'}>
                        {document.title || '未命名文稿'}
                      </span>
                      <small>{getDocumentSourceLabel(document)} · {formatVersionTime(document.updatedAt)}</small>
                    </span>
                    <span className="version-history-status">
                      {isCurrent && <VersionBadge tone="current">当前</VersionBadge>}
                      {isLatest && <VersionBadge tone="latest">最新</VersionBadge>}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </section>
  );
};

export default VersionNavigator;
