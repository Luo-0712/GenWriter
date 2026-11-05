import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { X } from 'lucide-react';
import { getDocument } from '../api/documents';
import * as api from '../api/workspace';
import SelectField from './SelectField';

export function WorkspaceModal({ title, onClose, children, footer, wide = false }) {
  const dialogRef = useRef(null);
  const closeRef = useRef(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);
  useEffect(() => {
    const previous = document.activeElement;
    closeRef.current?.focus();
    return () => previous?.focus?.();
  }, []);
  const keyDown = (event) => {
    if (event.key === 'Escape') { event.stopPropagation(); onCloseRef.current(); }
    if (event.key !== 'Tab') return;
    const items = [...dialogRef.current.querySelectorAll('button:not(:disabled),select,input,textarea,a[href]')].filter((item) => item.getClientRects().length);
    const first = items[0], last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  };
  return <div className="workspace-modal-overlay" onKeyDown={keyDown}>
    <section className={`workspace-dialog${wide ? ' wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={dialogRef}>
      <header className="workspace-dialog-heading"><h2>{title}</h2><button type="button" ref={closeRef} className="workspace-icon-button" onClick={onClose} aria-label="关闭对话框"><X size={18} /></button></header>
      <div className="workspace-dialog-body">{children}</div>
      {footer && <footer className="workspace-dialog-actions">{footer}</footer>}
    </section>
  </div>;
}

export function CandidateDialog({ candidate, workspace, onClose }) {
  const [base, setBase] = useState(null);
  const [loading, setLoading] = useState(Boolean(candidate.baseDocumentId));
  const [error, setError] = useState('');
  const [adopting, setAdopting] = useState(false);
  const [adopted, setAdopted] = useState(candidate.status === 'ADOPTED');
  const target = workspace.nodes.find((node) => node.id === candidate.targetNodeId);
  const stale = !target || (target.currentDocumentId || null) !== (candidate.baseDocumentId || null);
  const dirty = workspace.drafts[candidate.targetNodeId]?.dirty;
  useEffect(() => {
    let cancelled = false;
    if (!candidate.baseDocumentId) return undefined;
    getDocument(candidate.baseDocumentId).then((document) => { if (!cancelled) setBase(document); })
      .catch((failure) => { if (!cancelled) setError(failure.message); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [candidate.baseDocumentId]);
  const adopt = async () => {
    setAdopting(true); setError('');
    try { await workspace.adopt(candidate); setAdopted(true); }
    catch (failure) { setError(failure.message); await workspace.refresh(); }
    finally { setAdopting(false); }
  };
  const proposed = candidate.operation === 'APPEND' ? `${base?.content || ''}\n\n${candidate.content}` : candidate.content;
  return <WorkspaceModal title={adopted ? '已采纳的修改' : '预览候选修改'} wide onClose={() => { if (!adopting) onClose(); }} footer={<>
    <span className="workspace-muted">{adopted ? '已生成快照，可在节点版本中回看' : '采纳后保存新快照，历史版本保留'}</span>
    <button type="button" onClick={onClose} disabled={adopting}>{adopted ? '关闭' : '保留候选'}</button>
    {!adopted && <button className="workspace-primary" type="button" onClick={adopt} disabled={adopting || loading || stale || dirty}>{adopting ? '采纳中…' : '采纳为新快照'}</button>}
  </>}>
    <p className="workspace-muted">{candidate.operation === 'CREATE_NODE' ? '新增章节' : '目标正文'}：{candidate.operation === 'CREATE_NODE' ? candidate.title : target?.title || '已删除节点'} · {candidate.baseDocumentId ? `基于 V${base?.version || '…'}` : '基于空白正文'}</p>
    {!adopted && stale && <p className="workspace-error" role="alert">基准正文或目录已变化，请基于当前正文重新生成候选。</p>}
    {!adopted && dirty && <p className="workspace-error" role="alert">目标节点有未保存修改，请先保存并重新生成候选。</p>}
    {error && <p className="workspace-error" role="alert">{error}</p>}
    {loading ? <p>加载基准快照…</p> : <div className="candidate-comparison">
      <section><h3>{candidate.operation === 'CREATE_NODE' ? '原章节（保留）' : '基准正文'}</h3><div className="workspace-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{base?.content || '此节点暂无正文。'}</ReactMarkdown></div></section>
      <section><h3>{candidate.operation === 'CREATE_NODE' ? '新章节候选' : '采纳后的正文'}</h3><div className="workspace-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{proposed}</ReactMarkdown></div></section>
    </div>}
  </WorkspaceModal>;
}

export function LegacyDialog({ workspace, onClose }) {
  const [documents, setDocuments] = useState([]);
  const [selected, setSelected] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [parentId, setParentId] = useState(workspace.activeNodeId || workspace.rootId);
  useEffect(() => {
    let cancelled = false;
    api.getLegacy(workspace.projectId).then((list) => { if (!cancelled) { setDocuments(list); setSelected(list[0] || null); } })
      .catch((failure) => { if (!cancelled) setError(failure.message); }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [workspace.projectId]);
  const importDocument = async () => {
    setBusy(true); setError('');
    try {
      const result = await api.importLegacy(workspace.projectId, { documentId: selected.id, parentId });
      await workspace.refresh();
      workspace.applySnapshot(result.node.id, result.document);
      workspace.selectNode(result.node.id);
      onClose();
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  };
  return <WorkspaceModal title="历史文稿" wide onClose={() => { if (!busy) onClose(); }} footer={<>
    <span className="workspace-muted">原文与原版本保留，导入会建立新的正文节点。</span>
    <button type="button" disabled={busy} onClick={onClose}>关闭</button><button className="workspace-primary" type="button" disabled={!selected || busy} onClick={importDocument}>{busy ? '导入中…' : '归入正文目录'}</button>
  </>}>
    {error && <p className="workspace-error" role="alert">{error}</p>}
    {loading ? <p>加载历史文稿…</p> : !documents.length ? <p className="workspace-muted">该作品没有待整理的历史文稿。</p> : <>
      <label htmlFor="legacy-parent">导入到</label><SelectField id="legacy-parent" aria-label="导入到节点" value={parentId} onChange={setParentId} options={workspace.nodes.map((node) => ({ value: node.id, label: node.title }))} />
      <div className="legacy-browser"><nav aria-label="历史文稿列表">{documents.map((document) => <button type="button" key={document.id} className={selected?.id === document.id ? 'selected' : ''} onClick={() => setSelected(document)}>
        <span>{document.title}</span><small>原版本 V{document.version} · {new Date(document.updatedAt).toLocaleDateString()}</small>
      </button>)}</nav><article className="workspace-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{selected?.content || ''}</ReactMarkdown></article></div>
    </>}
  </WorkspaceModal>;
}

export function WorkExportDialog({ project, workspace, sessionId, onClose }) {
  const [format, setFormat] = useState('markdown');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const dirty = Object.entries(workspace.drafts).filter(([, draft]) => draft.dirty);
  const download = async (saveFirst) => {
    setBusy(true); setError('');
    try {
      if (saveFirst) for (const [nodeId] of dirty) await workspace.save(nodeId, sessionId);
      const blob = await api.exportWork(project.id, format);
      const url = URL.createObjectURL(blob), anchor = document.createElement('a');
      anchor.href = url; anchor.download = `${project.name}.${format === 'docx' ? 'docx' : 'md'}`; anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      onClose();
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  };
  return <WorkspaceModal title="导出整部作品" onClose={() => { if (!busy) onClose(); }} footer={<>
    <button type="button" onClick={onClose} disabled={busy}>取消</button>
    {dirty.length > 0 && <button type="button" disabled={busy} onClick={() => download(false)}>按已保存版本导出</button>}
    <button className="workspace-primary" type="button" disabled={busy} onClick={() => download(true)}>{busy ? '导出中…' : dirty.length ? '保存修改并导出' : '导出'}</button>
  </>}>
    <p className="workspace-muted">按正文目录顺序导出各节点的当前已保存正文，候选修改在采纳后纳入全文。</p>
    {dirty.length > 0 && <p className="workspace-notice">有 {dirty.length} 个节点包含未保存修改。</p>}
    <label htmlFor="work-export-format">格式</label><SelectField id="work-export-format" aria-label="导出格式" value={format} onChange={setFormat} options={[{ value: 'markdown', label: 'Markdown (.md)' }, { value: 'docx', label: 'Word (.docx)' }]} />
    {error && <p className="workspace-error" role="alert">{error}</p>}
  </WorkspaceModal>;
}
