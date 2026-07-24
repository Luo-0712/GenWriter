/* eslint-disable react-hooks/set-state-in-effect */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { PanelRightClose, PanelRightOpen, RefreshCw } from 'lucide-react';
import {
  createDocument,
  createDocumentVersion,
  editSuggestion,
  getDocumentsBySessionId,
  updateDocument,
} from '../api/documents';
import RichMarkdownEditor from './RichMarkdownEditor';
import VersionNavigator from './VersionNavigator';
import { formatVersionTime } from '../utils/version';
import { createSelectionFingerprint, markdownToHtml, normalizeTextForFingerprint } from '../utils/markdownEditor';
import '../styles/global.css';

const ITERATION_MODES = [
  { value: 'CONTINUE', label: '续写', fallback: '请基于当前版本继续写作，保持既有风格和结构。' },
  { value: 'POLISH', label: '润色', fallback: '请润色当前版本，提升表达质量，并保持原意不变。' },
  { value: 'CREATE', label: '重写', fallback: '请基于当前版本和我的要求重新生成一个更好的版本。' },
];

const SUGGESTION_MODES = [
  { value: 'POLISH_SELECTION', label: '润色选区', fallback: '请润色这段文字，保持原意不变。' },
  { value: 'REWRITE_SELECTION', label: '重写选区', fallback: '请基于上下文重写这段文字。' },
  { value: 'CONTINUE_AFTER_SELECTION', label: '在此后续写', fallback: '请从选区之后自然续写。' },
];

const emptyTitle = '未命名文稿';

const buildFrozenSelection = (snapshot, document) => {
  if (!snapshot || !document) return null;
  const fingerprint = createSelectionFingerprint({
    beforeText: snapshot.beforeText,
    selectedText: snapshot.selectedText,
    afterText: snapshot.afterText,
    documentVersion: document.version,
    fullTextLength: snapshot.fullTextLength,
  });
  return {
    ...snapshot,
    fingerprint,
    documentId: document.id,
    documentVersion: document.version,
  };
};

const WritingWorkbench = ({ sessionId, isLoading, selectedKbId, documentRefresh, onIterate, collapsed = false, onCollapse, onExpand }) => {
  const [documents, setDocuments] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [iterationMode, setIterationMode] = useState('CONTINUE');
  const [iterationText, setIterationText] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [selectionSnapshot, setSelectionSnapshot] = useState(null);
  const [frozenSelection, setFrozenSelection] = useState(null);
  const [suggestionMode, setSuggestionMode] = useState('POLISH_SELECTION');
  const [suggestionInstruction, setSuggestionInstruction] = useState('');
  const [suggestionLoading, setSuggestionLoading] = useState(false);
  const [suggestionResult, setSuggestionResult] = useState(null);
  const [suggestionError, setSuggestionError] = useState('');
  const [pendingDocument, setPendingDocument] = useState(null);
  const editorRef = useRef(null);
  const dirtyRef = useRef(false);
  const suggestionPanelRef = useRef(null);
  const confirmDialogRef = useRef(null);
  const confirmPrimaryRef = useRef(null);

  const selectedDocument = documents.find((doc) => doc.id === selectedId) || null;
  const selectedSuggestionMode = useMemo(
    () => SUGGESTION_MODES.find((item) => item.value === suggestionMode) || SUGGESTION_MODES[0],
    [suggestionMode]
  );

  useEffect(() => {
    dirtyRef.current = dirty;
  }, [dirty]);

  useEffect(() => {
    if (!(selectionSnapshot || suggestionResult || suggestionLoading || suggestionError)) return undefined;
    const scrollTimer = window.requestAnimationFrame(() => {
      suggestionPanelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
    return () => window.cancelAnimationFrame(scrollTimer);
  }, [selectionSnapshot, suggestionResult, suggestionLoading, suggestionError]);

  useEffect(() => {
    if (!pendingDocument) return undefined;
    const focusTimer = window.requestAnimationFrame(() => confirmPrimaryRef.current?.focus());
    return () => window.cancelAnimationFrame(focusTimer);
  }, [pendingDocument]);

  const clearSuggestion = useCallback(() => {
    setSelectionSnapshot(null);
    setFrozenSelection(null);
    setSuggestionResult(null);
    setSuggestionError('');
    setSuggestionInstruction('');
  }, []);

  const selectDocument = useCallback((document) => {
    setSelectedId(document?.id || '');
    setTitle(document?.title || '');
    setContent(document?.content || '');
    setDirty(false);
    setError('');
    setSelectionSnapshot(null);
    clearSuggestion();
  }, [clearSuggestion]);

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
    setPendingDocument(null);
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
    if (!document || document.id === selectedId) {
      return;
    }
    if (dirty) {
      setPendingDocument(document);
      return;
    }
    selectDocument(document);
  };

  const handleSaveAndSwitch = async () => {
    const targetDocument = pendingDocument;
    if (!targetDocument || saving) return;
    const savedDocument = await handleSaveCurrent();
    if (!savedDocument) return;
    setPendingDocument(null);
    selectDocument(targetDocument);
  };

  const handleDiscardAndSwitch = () => {
    const targetDocument = pendingDocument;
    if (!targetDocument) return;
    setPendingDocument(null);
    selectDocument(targetDocument);
  };

  const handleConfirmKeyDown = (event) => {
    if (event.key === 'Escape' && !saving) {
      event.preventDefault();
      setPendingDocument(null);
      return;
    }
    if (event.key !== 'Tab') return;

    const focusable = confirmDialogRef.current?.querySelectorAll('button:not(:disabled)');
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

  const handleSelectionChange = (snapshot) => {
    setSelectionSnapshot(snapshot);
    if (suggestionResult) {
      setSuggestionResult(null);
      setFrozenSelection(null);
    }
  };

  const requestSuggestion = async (mode = suggestionMode) => {
    if (!sessionId) return;
    if (!selectedDocument?.id) {
      setSuggestionError('请先保存文稿，再使用选区建议。');
      return;
    }
    if (!selectionSnapshot?.selectedText) {
      setSuggestionError('请先选中一段文字。');
      return;
    }

    const frozen = buildFrozenSelection(selectionSnapshot, selectedDocument);
    const selectedMode = SUGGESTION_MODES.find((item) => item.value === mode) || SUGGESTION_MODES[0];
    setFrozenSelection(frozen);
    setSuggestionMode(selectedMode.value);
    setSuggestionResult(null);
    setSuggestionError('');
    setSuggestionLoading(true);
    try {
      const result = await editSuggestion(selectedDocument.id, {
        mode: selectedMode.value,
        instruction: suggestionInstruction.trim() || selectedMode.fallback,
        title: title.trim() || emptyTitle,
        selectedText: frozen.selectedText,
        selectedMarkdown: frozen.selectedMarkdown,
        beforeText: frozen.beforeText,
        afterText: frozen.afterText,
        selectionFingerprint: frozen.fingerprint,
        clientDocumentVersion: frozen.documentVersion,
      });
      setSuggestionResult(result);
    } catch (e) {
      setSuggestionError(e.message || '生成选区建议失败');
    } finally {
      setSuggestionLoading(false);
    }
  };

  const validateFrozenSelection = () => {
    const editor = editorRef.current;
    if (!editor || !frozenSelection || !selectedDocument) return false;
    if (frozenSelection.documentId !== selectedDocument.id) return false;
    if (frozenSelection.documentVersion !== selectedDocument.version) return false;

    const { from, to } = frozenSelection;
    if (from < 0 || to > editor.state.doc.content.size || from >= to) return false;

    const selectedText = editor.state.doc.textBetween(from, to, '\n').trim();
    const beforeText = editor.state.doc.textBetween(Math.max(0, from - 1500), from, '\n');
    const afterText = editor.state.doc.textBetween(to, Math.min(editor.state.doc.content.size, to + 1500), '\n');
    const fullText = editor.state.doc.textBetween(0, editor.state.doc.content.size, '\n');
    const fingerprint = createSelectionFingerprint({
      beforeText,
      selectedText,
      afterText,
      documentVersion: selectedDocument.version,
      fullTextLength: fullText.length,
    });

    return normalizeTextForFingerprint(selectedText) === normalizeTextForFingerprint(frozenSelection.selectedText)
      && fingerprint === frozenSelection.fingerprint;
  };

  const handleAcceptSuggestion = () => {
    const editor = editorRef.current;
    const replacementMarkdown = suggestionResult?.replacementMarkdown || '';
    if (!editor || !replacementMarkdown.trim()) return;
    if (!validateFrozenSelection()) {
      setSuggestionError('文稿已变化，请重新选择文本生成建议。');
      return;
    }

    const html = markdownToHtml(replacementMarkdown);
    if (suggestionResult.mode === 'CONTINUE_AFTER_SELECTION') {
      editor.chain().focus().setTextSelection(frozenSelection.to).insertContent(html).run();
    } else {
      editor.chain().focus().setTextSelection({ from: frozenSelection.from, to: frozenSelection.to }).insertContent(html).run();
    }
    clearSuggestion();
    setNotice('已应用选区建议，记得保存');
  };

  const canSave = !!sessionId && !saving && (dirty || !selectedDocument);
  const canIterate = !!sessionId && !isLoading && !saving && (selectedDocument || content.trim());
  const canRequestSuggestion = !!sessionId && !!selectedDocument?.id && !!selectionSnapshot?.selectedText && !suggestionLoading;
  const canAcceptSuggestion = !!suggestionResult?.replacementMarkdown && !suggestionLoading;

  if (collapsed) {
    return (
      <aside className="writing-workbench collapsed">
        <button className="workbench-rail-button" type="button" onClick={onExpand} title="展开版本工作台" aria-label="展开版本工作台">
          <PanelRightOpen className="workbench-rail-icon" size={18} />
          <span className="workbench-rail-text">版本工作台</span>
          {dirty && <span className="workbench-rail-dot" title="有未保存修改" />}
        </button>
      </aside>
    );
  }

  return (
    <aside className="writing-workbench">
      <div className="workbench-header">
        <h2>版本工作台</h2>
        <div className="workbench-header-actions">
          <button
            className="workbench-icon-btn"
            type="button"
            onClick={() => loadDocuments(selectedId)}
            title="刷新版本"
            aria-label="刷新版本"
            disabled={loading}
          >
            <RefreshCw size={17} />
          </button>
          <button className="workbench-icon-btn" type="button" onClick={onCollapse} title="收起工作台" aria-label="收起工作台">
            <PanelRightClose size={17} />
          </button>
        </div>
      </div>

      <VersionNavigator
        documents={documents}
        selectedId={selectedId}
        dirty={dirty}
        loading={loading}
        onSelect={handleSelectVersion}
      />

      <div className="workbench-content">
        <div className="workbench-editor">
          <input
            className="workbench-title-input"
            value={title}
            onChange={(event) => markChanged(setTitle)(event.target.value)}
            placeholder="文稿标题"
            disabled={!sessionId}
          />

          <div className="workbench-toolbar">
            <div className="selection-tools" aria-label="选区 AI 建议">
              {SUGGESTION_MODES.map((item) => (
                <button
                  key={item.value}
                  type="button"
                  className={suggestionMode === item.value ? 'active' : ''}
                  disabled={!canRequestSuggestion}
                  onClick={() => requestSuggestion(item.value)}
                  title={!selectedDocument?.id ? '请先保存文稿' : '选择文本后生成建议'}
                >
                  {item.label}
                </button>
              ))}
            </div>
            <div className="workbench-actions">
              <button type="button" onClick={handleSaveCurrent} disabled={!canSave}>保存</button>
              <button type="button" onClick={handleSaveAsVersion} disabled={!sessionId || saving}>另存</button>
            </div>
          </div>

          <RichMarkdownEditor
            value={content}
            disabled={!sessionId}
            onChange={markChanged(setContent)}
            onSelectionSnapshot={handleSelectionChange}
            onEditorReady={(editor) => {
              editorRef.current = editor;
            }}
          />

          {(selectionSnapshot || suggestionResult || suggestionLoading || suggestionError) && (
            <div ref={suggestionPanelRef} className="selection-suggestion-panel">
              <div className="suggestion-panel-header">
                <div>
                  <strong>选区 AI 建议</strong>
                  <span>{selectionSnapshot?.selectedText ? `已选 ${selectionSnapshot.selectedText.length} 字` : '先选中文本'}</span>
                </div>
                <button type="button" onClick={clearSuggestion} disabled={suggestionLoading}>关闭</button>
              </div>

              <textarea
                className="suggestion-instruction"
                value={suggestionInstruction}
                onChange={(event) => setSuggestionInstruction(event.target.value)}
                placeholder={`${selectedSuggestionMode.label}的补充要求，可留空`}
                disabled={suggestionLoading}
              />

              <div className="suggestion-actions">
                <button type="button" onClick={() => requestSuggestion(suggestionMode)} disabled={!canRequestSuggestion}>
                  {suggestionLoading ? '生成中...' : `生成${selectedSuggestionMode.label}`}
                </button>
                <button type="button" onClick={handleAcceptSuggestion} disabled={!canAcceptSuggestion}>接受</button>
                <button type="button" onClick={clearSuggestion} disabled={suggestionLoading}>拒绝</button>
              </div>

              {frozenSelection?.selectedText && (
                <div className="suggestion-original">
                  <small>原文</small>
                  <p>{frozenSelection.selectedText}</p>
                </div>
              )}

              {suggestionResult?.replacementMarkdown && (
                <div className="suggestion-replacement">
                  <small>建议</small>
                  <pre>{suggestionResult.replacementMarkdown}</pre>
                </div>
              )}

              {suggestionError && <div className="suggestion-error" role="alert">{suggestionError}</div>}
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
            {isLoading ? '生成中...' : selectedDocument ? `基于 V${selectedDocument.version} 迭代` : '保存并开始迭代'}
          </button>
        </div>

        {(notice || error || selectedDocument) && (
          <div
            className={`workbench-footer-note${error ? ' error' : ''}`}
            role={error ? 'alert' : 'status'}
            aria-live={error ? 'assertive' : 'polite'}
          >
            {error || notice || `${selectedDocument?.title || emptyTitle} · ${formatVersionTime(selectedDocument?.updatedAt)}`}
          </div>
        )}
      </div>

      {pendingDocument && (
        <div className="workbench-confirm-layer">
          <div
            ref={confirmDialogRef}
            className="workbench-confirm-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="version-switch-title"
            aria-describedby="version-switch-description"
            onKeyDown={handleConfirmKeyDown}
          >
            <span className="version-kicker">切换版本</span>
            <h3 id="version-switch-title">保存当前修改吗？</h3>
            <p id="version-switch-description">
              当前版本有未保存修改。切换到 V{pendingDocument.version} 前，请选择如何处理这些内容。
            </p>
            <div className="workbench-confirm-actions">
              <button type="button" onClick={() => setPendingDocument(null)} disabled={saving}>取消</button>
              <button type="button" className="danger" onClick={handleDiscardAndSwitch} disabled={saving}>放弃修改</button>
              <button ref={confirmPrimaryRef} type="button" className="primary" onClick={handleSaveAndSwitch} disabled={saving}>
                {saving ? '保存中...' : '保存并切换'}
              </button>
            </div>
          </div>
        </div>
      )}
    </aside>
  );
};

export default WritingWorkbench;
