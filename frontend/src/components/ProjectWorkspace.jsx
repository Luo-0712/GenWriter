/* eslint-disable react-hooks/set-state-in-effect */
import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { BookOpen, Bot, Download, LayoutPanelLeft, Plus, X, PanelRightOpen } from 'lucide-react';
import WorkEditor from './WorkEditor';
import MessageBubble from './MessageBubble';
import InputBox from './InputBox';
import SelectField from './SelectField';
import { CandidateDialog, LegacyDialog, WorkExportDialog, WorkspaceModal } from './WorkspaceDialogs';
import { getReading } from '../api/workspace';

const operations = [ ['DISCUSS', '自由讨论，不写入正文'], ['REPLACE', '修改当前节点'], ['APPEND', '续写本节'], ['CREATE_NODE', '新增章节'] ];

export default function ProjectWorkspace({ project, workspace, sessions, activeSession, messages, onSend, onEnsureSession,
  onSelectSession, onNewChat, onNewProject, onExport, onConversationRefresh, isLoading, hasContentStarted, isSessionLoading, selectedKbId }) {
  const [operation, setOperation] = useState('DISCUSS');
  const [candidate, setCandidate] = useState(null);
  const [candidateList, setCandidateList] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [reading, setReading] = useState({ title: '', content: '' });
  const [readingLoading, setReadingLoading] = useState(false);
  const [localError, setLocalError] = useState('');
  const [mobileAssistant, setMobileAssistant] = useState(false);
  const containerRef = useRef(null);
  const messagesEndRef = useRef(null);
  const resizeCleanup = useRef(null);
  const view = workspace.mode === 'agent' ? workspace.agentView : workspace.studioView;
  const pending = workspace.candidates.filter((item) => item.status === 'PENDING');
  const sessionCandidates = workspace.candidates.filter((item) => (item.sourceSessionId || item.sessionId) === activeSession?.id);
  const visibleOperation = isLoading && workspace.taskTarget ? workspace.taskTarget.operation : operation;
  const visibleTarget = isLoading && workspace.taskTarget ? workspace.nodes.find((node) => node.id === workspace.taskTarget.nodeId) : workspace.activeNode;
  const setView = (next) => workspace.preferences(workspace.mode === 'agent' ? { agentView: next } : { studioView: next });

  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }, [messages]);
  useEffect(() => {
    if (view !== 'reading' || !project?.id) return undefined;
    let cancelled = false;
    setReadingLoading(true);
    getReading(project.id).then((result) => { if (!cancelled) setReading(result); })
      .catch((failure) => { if (!cancelled) setLocalError(failure.message); }).finally(() => { if (!cancelled) setReadingLoading(false); });
    return () => { cancelled = true; };
  }, [view, project?.id, workspace.revision]);
  useEffect(() => () => resizeCleanup.current?.(), []);
  useEffect(() => { setCandidate(null); setCandidateList(false); setExportOpen(false); setLocalError(''); setMobileAssistant(false); }, [project?.id]);

  const send = async (content, type, webSearch, kbId, attachments) => {
    try {
      setLocalError('');
      if (!project) return await onSend(content, type, webSearch, kbId, attachments);
      const session = await onEnsureSession();
      const target = await workspace.prepareTarget(operation, session.id);
      return await onSend(content, type, webSearch, kbId, attachments, target.baseDocumentId || '', target);
    } catch (failure) { setLocalError(failure.message); return false; }
  };

  const resize = (event) => {
    event.preventDefault();
    const previousCursor = document.body.style.cursor, previousSelect = document.body.style.userSelect;
    document.body.style.cursor = 'col-resize'; document.body.style.userSelect = 'none';
    const move = (pointer) => {
      const width = Math.min(600, Math.max(280, containerRef.current.getBoundingClientRect().right - pointer.clientX));
      workspace.preferences({ widths: { ...workspace.widths, [workspace.mode]: width } });
    };
    const stop = () => {
      document.body.style.cursor = previousCursor; document.body.style.userSelect = previousSelect;
      window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', stop); window.removeEventListener('pointercancel', stop);
      resizeCleanup.current = null;
    };
    resizeCleanup.current?.(); resizeCleanup.current = stop;
    window.addEventListener('pointermove', move); window.addEventListener('pointerup', stop); window.addEventListener('pointercancel', stop);
  };

  if (!project) return <div className="work-empty"><BookOpen size={34} /><h1>从一部作品开始</h1><p>建立正文目录，和智能体一起研究、写作与修改。</p><button className="workspace-primary" type="button" onClick={onNewProject}><Plus size={16} />新建作品</button></div>;

  return <section className={`project-workspace mode-${workspace.mode} view-${view}${workspace.assistantCollapsed ? ' assistant-collapsed' : ''}${mobileAssistant ? ' mobile-assistant' : ''}`}
    style={{ '--assistant-width': `${workspace.widths?.[workspace.mode] || 360}px` }} ref={containerRef}>
    <header className="workspace-header"><div className="workspace-project-title"><BookOpen size={17} /><h1 title={project.name}>{project.name}</h1></div>
      <nav className="workspace-mode-switch" aria-label="工作区模式"><button type="button" aria-pressed={workspace.mode === 'agent'} onClick={() => workspace.preferences({ mode: 'agent' })}><Bot size={15} />智能体</button>
        <button type="button" aria-pressed={workspace.mode === 'studio'} onClick={() => workspace.preferences({ mode: 'studio' })}><LayoutPanelLeft size={15} />创作台</button></nav>
      {workspace.mode === 'agent' && <nav className="workspace-view-switch" aria-label="智能体主区">{[['chat', '会话'], ['document', '正文'], ['reading', '全文']].map(([value, label]) => <button type="button" key={value} aria-pressed={view === value} onClick={() => setView(value)}>{label}</button>)}</nav>}
      <div className="workspace-header-actions">{isLoading && <span className="workspace-running" role="status">正在生成</span>}
        <button className="workspace-ghost" type="button" onClick={async () => { await workspace.refresh(); setCandidateList(true); }}>候选 {pending.length > 0 && <span className="workspace-count">{pending.length}</span>}</button>
        {workspace.mode === 'studio' && <><button className="workspace-ghost" type="button" onClick={() => setView(view === 'reading' ? 'document' : 'reading')}>{view === 'reading' ? '返回正文' : '全文阅读'}</button>
          <button className="workspace-action workspace-mobile-toggle" type="button" onClick={() => setMobileAssistant(!mobileAssistant)}><Bot size={16} />助手</button></>}
        <button className="workspace-action" type="button" onClick={() => setExportOpen(true)}><Download size={15} />导出作品</button>
      </div>
    </header>
    {(workspace.error || localError) && <div className="workspace-error-banner" role="alert">{workspace.error || localError}<button type="button" aria-label="关闭错误提示" onClick={() => { workspace.patch({ error: '' }); setLocalError(''); }}><X size={15} /></button></div>}
    <div className="workspace-panels">
      <div className="workspace-editor-pane"><WorkEditor workspace={workspace} onSend={onSend} isLoading={isLoading} sessionId={activeSession?.id} selectedKbId={selectedKbId}
        onEnsureSession={onEnsureSession} onCandidate={setCandidate} onConversationRefresh={onConversationRefresh} /></div>
      <section className="workspace-reading-pane" aria-label="全文阅读"><header><span className="workspace-kicker">全文阅读 · 已保存正文</span><h2>{reading.title || project.name}</h2></header>
        {Object.values(workspace.drafts).some((draft) => draft.dirty) && <p className="workspace-notice">部分修改尚未保存，当前全文按已保存快照展示。</p>}
        {readingLoading ? <p className="workspace-muted">加载全文…</p> : <article className="workspace-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{reading.content || '保存正文后，即可在这里连续阅读全文。'}</ReactMarkdown></article>}
      </section>
      <div className="workspace-resizer" role="separator" aria-orientation="vertical" aria-label="调整助手宽度" aria-valuenow={workspace.widths?.[workspace.mode] || 360} tabIndex={0}
        onPointerDown={resize} onKeyDown={(event) => {
          if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
          event.preventDefault(); const width = Math.min(600, Math.max(280, (workspace.widths?.[workspace.mode] || 360) + (event.key === 'ArrowLeft' ? 20 : -20)));
          workspace.preferences({ widths: { ...workspace.widths, [workspace.mode]: width } });
        }} />
      <aside className="workspace-chat-pane" aria-label="创作助手"><header className="workspace-chat-header"><div className="workspace-chat-session"><span className="workspace-kicker">创作会话</span>
        <SelectField aria-label="选择创作会话" value={activeSession?.id || ''} onChange={(value) => { const session = sessions.find((item) => item.id === value); if (session) onSelectSession(session); }}
          options={[...(!activeSession ? [{ value: '', label: '新会话' }] : []), ...sessions.map((session) => ({ value: session.id, label: session.title }))]} /></div>
        <button className="workspace-icon-button" type="button" aria-label="新建创作会话" onClick={onNewChat}><Plus size={17} /></button>
        <button className="workspace-icon-button workspace-mobile-toggle" type="button" aria-label="关闭助手" onClick={() => setMobileAssistant(false)}><X size={17} /></button>
      </header>
        <div className="workspace-message-scroll">{isSessionLoading && <p className="workspace-muted">加载会话…</p>}
          {!messages.length && !isSessionLoading && <div className="workspace-chat-welcome"><Bot size={24} /><h2>一起推进这部作品</h2><p>可以自由讨论、整理资料，或选择写入目标生成正文候选。</p></div>}
          {messages.map((message) => <MessageBubble key={message.id} message={message} onExport={onExport} />)}
          {isLoading && !hasContentStarted && <p className="workspace-muted" role="status">正在处理写作任务…</p>}
          {sessionCandidates.map((item) => <div className="workspace-candidate-card" data-candidate-id={item.id} key={item.id}>
            <div><strong>{item.operation === 'CREATE_NODE' ? item.title : workspace.nodes.find((node) => node.id === item.targetNodeId)?.title || item.title}</strong><span>{item.status === 'ADOPTED' ? '已采纳' : '待采纳'}</span></div>
            <p>{item.content.slice(0, 150)}{item.content.length > 150 ? '…' : ''}</p><button className="workspace-action" type="button" onClick={() => setCandidate(item)}>{item.status === 'ADOPTED' ? '查看修改' : '预览修改'}</button>
          </div>)}<div ref={messagesEndRef} />
        </div>
        <div className="workspace-task-target"><label htmlFor="workspace-task-target">本次写入目标</label><SelectField id="workspace-task-target" aria-label="本次写入目标" value={visibleOperation} disabled={isLoading} onChange={setOperation}
          options={operations.map(([value, label]) => ({ value, label }))} />
          {visibleOperation !== 'DISCUSS' && <span title={visibleTarget?.title}>{visibleTarget?.title || '请先选择正文节点'} · {visibleOperation === 'CREATE_NODE' ? '采纳时新建章节' : '采纳后更新正文'}</span>}</div>
        <InputBox onSend={send} disabled={isSessionLoading || workspace.loading} isLoading={isLoading} sessionId={activeSession?.id || ''} kbId={selectedKbId} />
      </aside>
      {workspace.mode === 'studio' && workspace.assistantCollapsed && <button className="workspace-assistant-rail" type="button" aria-label="展开创作助手" onClick={() => workspace.preferences({ assistantCollapsed: false })}><PanelRightOpen size={18} /><span>创作助手</span>{pending.length > 0 && <span>{pending.length}</span>}</button>}
    </div>
    {candidate && <CandidateDialog key={candidate.id} candidate={candidate} workspace={workspace} onClose={() => setCandidate(null)} />}
    {candidateList && <WorkspaceModal title="作品候选" wide onClose={() => setCandidateList(false)}>
      {!workspace.candidates.length && <p className="workspace-muted">生成正文后，候选会出现在这里。</p>}
      <div className="workspace-candidate-list">{workspace.candidates.map((item) => <button type="button" data-candidate-id={item.id} key={item.id} onClick={() => { setCandidateList(false); setCandidate(item); }}>
        <strong>{item.title}</strong><span>{item.status === 'ADOPTED' ? '已采纳' : '待采纳'} · {sessions.find((session) => session.id === item.sourceSessionId)?.title || '历史来源会话'}</span>
      </button>)}</div>
    </WorkspaceModal>}
    {workspace.legacyOpen && <LegacyDialog workspace={workspace} onClose={() => workspace.patch({ legacyOpen: false })} />}
    {exportOpen && <WorkExportDialog project={project} workspace={workspace} sessionId={activeSession?.id} onClose={() => setExportOpen(false)} />}
  </section>;
}
