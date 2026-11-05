import { useCallback, useEffect, useRef, useState } from 'react';
import { DOMParser, DOMSerializer } from '@tiptap/pm/model';
import { Save, Sparkles, PanelRightClose, PanelRightOpen } from 'lucide-react';
import RichMarkdownEditor from './RichMarkdownEditor';
import VersionNavigator from './VersionNavigator';
import { editSuggestion } from '../api/documents';
import { proposeSnapshot } from '../api/workspace';
import { createSelectionFingerprint, htmlToMarkdown, markdownToHtml } from '../utils/markdownEditor';

const operations = [
  { value: 'REPLACE', label: '生成 / 重写本节', type: 'CREATE', fallback: '请根据作品目标和我的要求生成本节完整正文；已有正文时，在其基础上改写。' },
  { value: 'POLISH', label: '润色本节', type: 'POLISH', operation: 'REPLACE', fallback: '请润色当前正文，保持原意和篇章结构。' },
  { value: 'APPEND', label: '续写本节', type: 'CONTINUE', fallback: '请接着当前正文续写，只输出新增段落，不重复原文。' },
  { value: 'CREATE_NODE', label: '新增章节', type: 'CONTINUE', fallback: '请承接当前章节，撰写下一章完整正文。' },
];

export default function WorkEditor({ workspace, onSend, isLoading, sessionId, selectedKbId, onEnsureSession, onCandidate, onConversationRefresh }) {
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [selectionLoading, setSelectionLoading] = useState(false);
  const [operation, setOperation] = useState('REPLACE');
  const [instruction, setInstruction] = useState('');
  const selectionRef = useRef(null);
  const editorRef = useRef(null);
  const activeNodeRef = useRef(workspace.activeNodeId);
  useEffect(() => { activeNodeRef.current = workspace.activeNodeId; }, [workspace.activeNodeId]);
  const node = workspace.activeNode;
  const draft = workspace.drafts[node?.id];
  const versions = workspace.versions[node?.id] || [];
  const selectedId = workspace.selectedVersions[node?.id] || node?.currentDocumentId || '';
  const selected = versions.find((document) => document.id === selectedId);
  const historical = Boolean(selectedId && selectedId !== node?.currentDocumentId);
  const content = historical ? selected?.content || '' : draft?.content || '';
  const title = historical ? selected?.title || node?.title || '' : draft?.title || node?.title || '';
  const ready = Boolean(node && draft);
  const onEditorReady = useCallback((editor) => { editorRef.current = editor; }, []);
  const selectionChanged = useCallback((selection) => { selectionRef.current = selection; }, []);

  const save = async () => {
    if (!ready || saving) return;
    setSaving(true); setError('');
    try {
      const result = await workspace.save(node.id, sessionId, historical
        ? { title: node.title, content, baseDocumentId: node.currentDocumentId } : undefined);
      setNotice(`已保存为 V${result.version} 快照`);
    } catch (failure) { setError(failure.message); }
    finally { setSaving(false); }
  };

  const iterate = async () => {
    if (!ready || isLoading || saving || historical) return;
    setError('');
    try {
      const source = await onEnsureSession();
      const mode = operations.find((item) => item.value === operation);
      const target = await workspace.prepareTarget(mode.operation || mode.value, source.id, node.id);
      await onSend(instruction.trim() || mode.fallback, mode.type, true, selectedKbId, [], target.baseDocumentId || '', target);
      setInstruction(''); setNotice('结果将保留为候选，预览采纳后写入正文');
    } catch (failure) { setError(failure.message); }
  };

  const selectionSuggestion = async (mode) => {
    const frozen = selectionRef.current;
    const editor = editorRef.current;
    if (!frozen?.selectedText || !editor || historical || !node) { setError('请先选中需要修改的正文'); return; }
    const sourceNodeId = node.id;
    const frozenText = editor.state.doc.textContent;
    setSelectionLoading(true); setError('');
    try {
      const source = await onEnsureSession();
      const document = draft.dirty || !selected ? await workspace.save(node.id, source.id) : selected;
      const result = await editSuggestion(document.id, { mode, instruction: instruction.trim() || '保持上下文连贯，按选区操作修改', title,
        selectedText: frozen.selectedText, selectedMarkdown: frozen.selectedMarkdown, beforeText: frozen.beforeText, afterText: frozen.afterText,
        selectionFingerprint: createSelectionFingerprint({ ...frozen, documentVersion: document.version }), clientDocumentVersion: document.version });
      if (activeNodeRef.current !== sourceNodeId || editor.state.doc.textContent !== frozenText) throw new Error('正文或选区已变化，请重新选择后生成建议');
      const fragment = window.document.createElement('div');
      fragment.innerHTML = markdownToHtml(result.replacementMarkdown);
      const slice = DOMParser.fromSchema(editor.schema).parseSlice(fragment);
      const transaction = mode === 'CONTINUE_AFTER_SELECTION' ? editor.state.tr.insert(frozen.to, slice.content)
        : editor.state.tr.replaceRange(frozen.from, frozen.to, slice);
      const container = window.document.createElement('div');
      container.appendChild(DOMSerializer.fromSchema(editor.schema).serializeFragment(transaction.doc.content));
      const candidate = await proposeSnapshot(workspace.projectId, { nodeId: sourceNodeId, baseDocumentId: document.id, title,
        content: htmlToMarkdown(container.innerHTML), instruction: `${mode}: ${instruction.trim() || '选区修改'}`, sourceSessionId: source.id });
      await workspace.refresh();
      onConversationRefresh?.();
      onCandidate(candidate);
    } catch (failure) { setError(failure.message); }
    finally { setSelectionLoading(false); }
  };

  return <section className="work-editor" data-node-id={node?.id} aria-busy={!ready} aria-label="作品正文编辑器">
    <header className="work-editor-heading"><div><span className="workspace-kicker">作品正文</span><h2>{node?.title || '正文'}</h2></div>
      <span className="workspace-save-state">{historical ? '历史快照 · 只读' : draft?.dirty ? '有未保存修改' : node?.currentDocumentId ? '已保存' : '尚未保存'}</span>
      {workspace.mode === 'studio' && <button className="workspace-icon-button" type="button" aria-label={workspace.assistantCollapsed ? '展开助手' : '收起助手'}
        onClick={() => workspace.preferences({ assistantCollapsed: !workspace.assistantCollapsed })}>
        {workspace.assistantCollapsed ? <PanelRightOpen size={17} /> : <PanelRightClose size={17} />}</button>}
    </header>
    <VersionNavigator documents={versions} selectedId={selectedId} dirty={Boolean(draft?.dirty)} loading={!ready && workspace.loading}
      onSelect={(document) => workspace.patch((current) => ({ selectedVersions: { ...current.selectedVersions, [node.id]: document.id === node.currentDocumentId ? null : document.id } }))} />
    <div className="work-editor-scroll">
      <input className="workbench-title-input" aria-label="正文标题" value={title} placeholder="正文标题" disabled={!ready || historical}
        onChange={(event) => workspace.updateDraft(node.id, { title: event.target.value })} />
      <div className="work-editor-toolbar"><div className="selection-tools">
        <button type="button" disabled={!ready || historical || isLoading || selectionLoading} onClick={() => selectionSuggestion('POLISH_SELECTION')}>润色选区</button>
        <button type="button" disabled={!ready || historical || isLoading || selectionLoading} onClick={() => selectionSuggestion('REWRITE_SELECTION')}>重写选区</button>
        <button type="button" disabled={!ready || historical || isLoading || selectionLoading} onClick={() => selectionSuggestion('CONTINUE_AFTER_SELECTION')}>选区后续写</button>
      </div><button className="workspace-action" type="button" disabled={!ready || saving || selectionLoading} onClick={save}><Save size={14} />
        {saving ? '保存中…' : historical ? '以此保存新版本' : '保存快照'}</button></div>
      {historical && draft?.dirty && <p className="workspace-notice">当前正文的未保存修改已保留，切回当前版本可继续编辑。</p>}
      <RichMarkdownEditor documentKey={node ? `${node.id}:${historical ? selectedId : 'current'}` : undefined} value={content}
        onChange={(value) => { if (node && !historical) workspace.updateDraft(node.id, { content: value }); }}
        disabled={!ready || historical || saving || selectionLoading} placeholder="从这里开始写作。正文独立于会话保存。"
        onEditorReady={onEditorReady} onSelectionSnapshot={selectionChanged} />
      {selectionLoading && <p className="workspace-notice">正在生成选区候选，正文将在采纳后更新…</p>}
      {notice && <p className="workspace-notice" role="status">{notice}</p>}
      {error && <p className="workspace-error" role="alert">{error}</p>}
      <div className="work-iteration"><div className="work-operation-pills">{operations.map((item) => <button key={item.value} type="button" className={operation === item.value ? 'active' : ''}
        onClick={() => setOperation(item.value)}>{item.label}</button>)}</div>
        <textarea aria-label="正文写作要求" placeholder="补充写作要求…" value={instruction} onChange={(event) => setInstruction(event.target.value)} />
        <button className="workspace-primary" type="button" disabled={!ready || historical || isLoading || saving || selectionLoading} onClick={iterate}><Sparkles size={14} />生成候选</button>
      </div>
    </div>
    <footer className="work-editor-footer"><span>{content.replace(/\s/g, '').length} 字</span><span>正文与版本跟随作品保存</span></footer>
  </section>;
}
