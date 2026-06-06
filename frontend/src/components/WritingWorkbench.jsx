/* eslint-disable react-hooks/set-state-in-effect */
import { useCallback, useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  createDocument,
  createDocumentVersion,
  getDocumentsBySessionId,
  updateDocument,
} from '../api/documents';
import '../styles/global.css';

const ITERATION_MODES = [
  { value: 'CONTINUE', label: '续写', fallback: '请基于当前版本继续写作，保持既有风格和结构。' },
  { value: 'POLISH', label: '润色', fallback: '请润色当前版本，提升表达质量，并保持原意不变。' },
  { value: 'CREATE', label: '重写', fallback: '请基于当前版本和我的要求重新生成一个更好的版本。' },
];

const emptyTitle = '未命名文稿';

const parseMetadata = (metadata) => {
  if (!metadata) return {};
  if (typeof metadata === 'object') return metadata;
  try {
    return JSON.parse(metadata);
  } catch {
    return {};
  }
};

const formatTime = (value) => {
  if (!value) return '';
  try {
    return new Date(value).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return '';
  }
};

const sourceLabel = (document) => {
  const metadata = parseMetadata(document?.metadata);
  if (metadata.derivedFromVersion) return `源自 V${metadata.derivedFromVersion}`;
  if (metadata.generatedBy === 'agent') return 'Agent';
  if (metadata.createdBy === 'user') return '手动';
  return document?.type || 'draft';
};

const WritingWorkbench = ({ sessionId, isLoading, selectedKbId, documentRefresh, onIterate, collapsed = false, onCollapse, onExpand }) => {
  const [documents, setDocuments] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [mode, setMode] = useState('edit');
  const [iterationMode, setIterationMode] = useState('CONTINUE');
  const [iterationText, setIterationText] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const dirtyRef = useRef(false);

  const selectedDocument = documents.find((doc) => doc.id === selectedId) || null;

  useEffect(() => {
    dirtyRef.current = dirty;
  }, [dirty]);

  const selectDocument = useCallback((document) => {
    setSelectedId(document?.id || '');
    setTitle(document?.title || '');
    setContent(document?.content || '');
    setDirty(false);
    setError('');
  }, []);

  const loadDocuments = useCallback(async (preferredId = '', forceSelect = false) => {
    if (!sessionId) {
      setDocuments([]);
      selectDocument(null);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const list = await getDocumentsBySessionId(sessionId);
      const nextDocuments = list || [];
      setDocuments(nextDocuments);
      if (forceSelect || !dirtyRef.current) {
        const preferred = nextDocuments.find((doc) => doc.id === preferredId);
        selectDocument(preferred || nextDocuments[0] || null);
      }
    } catch (e) {
      setError(e.message || '加载文稿失败');
    } finally {
      setLoading(false);
    }
  }, [sessionId, selectDocument]);

  useEffect(() => {
    setNotice('');
    setIterationText('');
    dirtyRef.current = false;
    setDirty(false);
    loadDocuments('', true);
  }, [sessionId, loadDocuments]);

  useEffect(() => {
    if (!documentRefresh?.token) return;
    loadDocuments(documentRefresh.documentId);
  }, [documentRefresh?.token, documentRefresh?.documentId, loadDocuments]);

  const markChanged = (setter) => (value) => {
    setter(value);
    setDirty(true);
    setNotice('');
  };

  const currentPayload = () => ({
    sessionId,
    title: title.trim() || emptyTitle,
    content,
    type: 'draft',
    format: 'markdown',
    status: 'editing',
  });

  const handleSaveAsVersion = async () => {
    if (!sessionId || saving) return null;
    setSaving(true);
    setError('');
    try {
      const metadata = {
        createdBy: 'user',
        derivedFromDocumentId: selectedDocument?.id || undefined,
        derivedFromVersion: selectedDocument?.version || undefined,
      };
      const payload = {
        ...currentPayload(),
        metadata: JSON.stringify(metadata),
      };
      const created = documents.length > 0
        ? await createDocumentVersion(sessionId, payload)
        : await createDocument(payload);
      await loadDocuments(created.id, true);
      setNotice(`已保存为 V${created.version || 1}`);
      return created;
    } catch (e) {
      setError(e.message || '保存新版本失败');
      return null;
    } finally {
      setSaving(false);
    }
  };

  const handleSaveCurrent = async () => {
    if (!sessionId || saving) return null;
    if (!selectedDocument) return handleSaveAsVersion();
    setSaving(true);
    setError('');
    try {
      const payload = currentPayload();
      delete payload.sessionId;
      const updated = await updateDocument(selectedDocument.id, payload);
      setDocuments((prev) => prev.map((doc) => (doc.id === updated.id ? updated : doc)));
      selectDocument(updated);
      setNotice(`V${updated.version} 已保存`);
      return updated;
    } catch (e) {
      setError(e.message || '保存失败');
      return null;
    } finally {
      setSaving(false);
    }
  };

  const handleSelectVersion = (document) => {
    if (dirty && !window.confirm('当前版本有未保存修改，切换后会丢失这些修改。继续切换吗？')) {
      return;
    }
    selectDocument(document);
  };

  const handleIterate = async () => {
    if (!sessionId || isLoading || saving) return;
    let activeDocument = selectedDocument;
    if (dirty || !activeDocument) {
      activeDocument = await handleSaveCurrent();
    }
    if (!activeDocument?.id) return;

    const selectedMode = ITERATION_MODES.find((item) => item.value === iterationMode) || ITERATION_MODES[0];
    const instruction = iterationText.trim() || selectedMode.fallback;
    await onIterate?.(instruction, selectedMode.value, true, selectedKbId, [], activeDocument.id);
    setIterationText('');
    setNotice('已提交迭代');
  };

  const canSave = !!sessionId && !saving && (dirty || !selectedDocument);
  const canIterate = !!sessionId && !isLoading && !saving && (selectedDocument || content.trim());

  if (collapsed) {
    return (
      <aside className="writing-workbench collapsed">
        <button className="workbench-rail-button" type="button" onClick={onExpand} title="展开版本工作台">
          <span className="workbench-rail-icon">⟨</span>
          <span className="workbench-rail-text">版本工作台</span>
          {dirty && <span className="workbench-rail-dot" title="有未保存修改" />}
        </button>
      </aside>
    );
  }

  return (
    <aside className="writing-workbench">
      <div className="workbench-header">
        <div>
          <h2>版本工作台</h2>
          <span className={dirty ? 'workbench-state dirty' : 'workbench-state'}>
            {dirty ? '未保存' : '已同步'}
          </span>
        </div>
        <div className="workbench-header-actions">
          <button className="workbench-icon-btn" type="button" onClick={() => loadDocuments(selectedId)} title="刷新版本">
            ↻
          </button>
          <button className="workbench-icon-btn" type="button" onClick={onCollapse} title="收起工作台">
            ›
          </button>
        </div>
      </div>

      <div className="version-strip">
        {loading ? (
          <span className="workbench-muted">加载版本中...</span>
        ) : documents.length === 0 ? (
          <span className="workbench-muted">暂无版本</span>
        ) : (
          documents.map((document) => (
            <button
              key={document.id}
              type="button"
              className={`version-chip${document.id === selectedId ? ' active' : ''}`}
              onClick={() => handleSelectVersion(document)}
              title={document.title}
            >
              <span>V{document.version}</span>
              <small>{sourceLabel(document)}</small>
            </button>
          ))
        )}
      </div>

      <div className="workbench-editor">
        <input
          className="workbench-title-input"
          value={title}
          onChange={(event) => markChanged(setTitle)(event.target.value)}
          placeholder="文稿标题"
          disabled={!sessionId}
        />

        <div className="workbench-toolbar">
          <div className="workbench-tabs">
            <button type="button" className={mode === 'edit' ? 'active' : ''} onClick={() => setMode('edit')}>编辑</button>
            <button type="button" className={mode === 'preview' ? 'active' : ''} onClick={() => setMode('preview')}>预览</button>
          </div>
          <div className="workbench-actions">
            <button type="button" onClick={handleSaveCurrent} disabled={!canSave}>保存</button>
            <button type="button" onClick={handleSaveAsVersion} disabled={!sessionId || saving}>另存</button>
          </div>
        </div>

        {mode === 'edit' ? (
          <textarea
            className="workbench-textarea"
            value={content}
            onChange={(event) => markChanged(setContent)(event.target.value)}
            placeholder="在这里编辑当前文稿..."
            disabled={!sessionId}
          />
        ) : (
          <div className="workbench-preview">
            {content.trim() ? (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
            ) : (
              <span className="workbench-muted">没有可预览的内容</span>
            )}
          </div>
        )}
      </div>

      <div className="iteration-panel">
        <div className="iteration-modes">
          {ITERATION_MODES.map((item) => (
            <button
              key={item.value}
              type="button"
              className={iterationMode === item.value ? 'active' : ''}
              onClick={() => setIterationMode(item.value)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <textarea
          className="iteration-input"
          value={iterationText}
          onChange={(event) => setIterationText(event.target.value)}
          placeholder="给 agent 的本轮迭代指令..."
          disabled={!sessionId || isLoading}
        />
        <button className="iteration-submit" type="button" onClick={handleIterate} disabled={!canIterate}>
          {isLoading ? '生成中...' : '基于此版本迭代'}
        </button>
      </div>

      {(notice || error || selectedDocument) && (
        <div className={`workbench-footer-note${error ? ' error' : ''}`}>
          {error || notice || `${selectedDocument?.title || emptyTitle} · ${formatTime(selectedDocument?.updatedAt)}`}
        </div>
      )}
    </aside>
  );
};

export default WritingWorkbench;
