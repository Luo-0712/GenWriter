import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, ChevronRight, FileText, MoreHorizontal, Plus, Archive } from 'lucide-react';
import * as api from '../api/workspace';

export default function ContentTree({ workspace, onSelect, onLegacy }) {
  const [dialog, setDialog] = useState(null);
  const [title, setTitle] = useState('');
  const [parentId, setParentId] = useState('');
  const treeRef = useRef(null);
  const closeMenus = useCallback(() => { treeRef.current?.querySelectorAll('details[open]').forEach((menu) => { menu.open = false; }); }, []);
  useEffect(() => {
    const outside = (event) => { if (!(event.target instanceof Element) || !event.target.closest('.tree-menu')) closeMenus(); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [closeMenus]);
  const byId = useMemo(() => new Map(workspace.nodes.map((node) => [node.id, node])), [workspace.nodes]);
  const depthOf = (node) => { let depth = 0; for (let parent = byId.get(node.parentId); parent; parent = byId.get(parent.parentId)) depth++; return depth; };
  const visible = (node) => { for (let parent = byId.get(node.parentId); parent; parent = byId.get(parent.parentId)) if (workspace.expanded[parent.id] === false) return false; return true; };
  const isDescendant = (id, ancestor) => { for (let node = byId.get(id); node; node = byId.get(node.parentId)) if (node.id === ancestor) return true; return false; };
  const openDialog = (type, node) => {
    closeMenus();
    setTitle(type === 'rename' ? node.title : '');
    setParentId(type === 'move' ? node.parentId : node?.id || workspace.rootId);
    setDialog({ type, node });
  };
  const submit = async (event) => {
    event.preventDefault();
    try {
      await workspace.mutate(async () => {
        if (dialog.type === 'create') {
          const created = await api.createNode(workspace.projectId, { title, parentId });
          workspace.selectNode(created.id);
          workspace.patch((current) => ({ expanded: { ...current.expanded, [parentId]: true } }));
        } else if (dialog.type === 'rename') await api.renameNode(workspace.projectId, dialog.node.id, title);
        else if (dialog.type === 'move') await api.moveNode(workspace.projectId, dialog.node.id, { parentId, position: workspace.nodes.filter((node) => node.parentId === parentId).length });
        else await api.deleteNode(workspace.projectId, dialog.node.id);
      });
      setDialog(null);
    } catch { /* The workspace displays the mutation error. */ }
  };
  const reorder = (node, offset) => {
    closeMenus();
    return workspace.mutate(() => api.moveNode(workspace.projectId, node.id,
      { parentId: node.parentId, position: Math.max(0, node.position + offset) })).catch(() => {});
  };
  const drop = (event, node) => {
    event.preventDefault();
    const id = event.dataTransfer.getData('application/genwriter-node');
    if (!id || id === node.id || !byId.has(id)) return;
    const before = event.shiftKey && !node.root;
    workspace.mutate(() => api.moveNode(workspace.projectId, id, { parentId: before ? node.parentId : node.id,
      position: before ? node.position : workspace.nodes.filter((entry) => entry.parentId === node.id).length })).catch(() => {});
  };

  return <section className="work-tree" aria-label="作品正文目录" ref={treeRef}>
    <div className="project-section-header"><span className="section-label">正文目录</span>
      <button className="section-action-btn" type="button" aria-label="新建章节" disabled={!workspace.rootId || workspace.busy}
        onClick={() => openDialog('create', workspace.activeNode || byId.get(workspace.rootId))}><Plus size={15} /></button>
    </div>
    {workspace.loading && !workspace.nodes.length && <p className="workspace-muted">加载作品目录…</p>}
    <div role="tree" aria-label="正文节点">
      {workspace.nodes.filter(visible).map((node) => {
        const hasChildren = workspace.nodes.some((child) => child.parentId === node.id);
        const expanded = workspace.expanded[node.id] !== false;
        return <div key={node.id} data-node-id={node.id} role="treeitem" aria-level={depthOf(node) + 1} aria-selected={workspace.activeNodeId === node.id}
          aria-expanded={hasChildren ? expanded : undefined} className={`work-tree-row${workspace.activeNodeId === node.id ? ' selected' : ''}`}
          style={{ '--tree-depth': depthOf(node) }} draggable={!node.root && !workspace.busy}
          onDragStart={(event) => { event.dataTransfer.setData('application/genwriter-node', node.id); event.dataTransfer.effectAllowed = 'move'; }}
          onDragOver={(event) => { if (event.dataTransfer.types.includes('application/genwriter-node')) event.preventDefault(); }} onDrop={(event) => drop(event, node)}>
          <button className="tree-expand" type="button" aria-label={expanded ? '收起子节点' : '展开子节点'} disabled={!hasChildren}
            onClick={() => workspace.patch((current) => ({ expanded: { ...current.expanded, [node.id]: !expanded } }))}>
            {hasChildren ? expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} /> : <span />}
          </button>
          <button className="tree-select" type="button" title={node.title} onClick={() => onSelect(node.id)}>
            <FileText size={14} /><span>{node.title}</span>{workspace.drafts[node.id]?.dirty && <i aria-label="有未保存修改">●</i>}
          </button>
          <details className="tree-menu" onToggle={(event) => {
            if (event.currentTarget.open) treeRef.current?.querySelectorAll('details[open]').forEach((menu) => { if (menu !== event.currentTarget) menu.open = false; });
          }}><summary aria-label={`${node.title}的操作`}><MoreHorizontal size={15} /></summary>
            <div className="tree-menu-items">
              <button type="button" onClick={() => openDialog('create', node)}>新建子节点</button>
              <button type="button" onClick={() => openDialog('rename', node)}>重命名</button>
              {!node.root && <><button type="button" onClick={() => reorder(node, -1)}>上移</button><button type="button" onClick={() => reorder(node, 1)}>下移</button>
                <button type="button" onClick={() => openDialog('move', node)}>移动到…</button><button type="button" className="danger" onClick={() => openDialog('delete', node)}>删除节点及子节点</button></>}
            </div>
          </details>
        </div>;
      })}
    </div>
    <button className="legacy-entry" type="button" onClick={onLegacy}><Archive size={14} />历史文稿</button>
    {dialog && <div className="workspace-modal-overlay" onKeyDown={(event) => { if (event.key === 'Escape') setDialog(null); }}>
      <form className="workspace-dialog" onSubmit={submit} role="dialog" aria-modal="true" aria-labelledby="node-dialog-title">
        <h2 id="node-dialog-title">{{ create: '新建正文节点', rename: '重命名', move: '移动正文节点', delete: '删除正文节点' }[dialog.type]}</h2>
        {['create', 'rename'].includes(dialog.type) && <label>标题<input autoFocus required maxLength={500} value={title} onChange={(event) => setTitle(event.target.value)} /></label>}
        {['create', 'move'].includes(dialog.type) && <label>放入节点<select value={parentId} onChange={(event) => setParentId(event.target.value)}>
          {workspace.nodes.filter((node) => dialog.type !== 'move' || !isDescendant(node.id, dialog.node.id)).map((node) => <option key={node.id} value={node.id}>{'　'.repeat(depthOf(node))}{node.title}</option>)}
        </select></label>}
        {dialog.type === 'delete' && <><p>将从正文目录移除“{dialog.node.title}”及其子节点，全文与导出也将排除这些内容。</p>
          {workspace.nodes.some((node) => isDescendant(node.id, dialog.node.id) && workspace.drafts[node.id]?.dirty) && <p className="workspace-notice">这些节点中有未保存修改，删除后将丢弃这些修改。</p>}</>}
        <div className="workspace-dialog-actions"><button type="button" onClick={() => setDialog(null)}>取消</button>
          <button className="workspace-primary" type="submit" disabled={workspace.busy}>{workspace.busy ? '处理中…' : '确定'}</button></div>
      </form>
    </div>}
  </section>;
}
