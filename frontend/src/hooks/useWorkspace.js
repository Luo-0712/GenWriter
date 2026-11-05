import { useCallback, useEffect, useRef, useState } from 'react';
import * as api from '../api/workspace';

const preferencesKey = (id) => `genwriter-workspace:${id}`;
const defaults = () => ({ mode: 'studio', agentView: 'chat', studioView: 'document', assistantCollapsed: false, widths: { studio: 360, agent: 360 } });
const readPreferences = (id) => {
  try {
    const saved = JSON.parse(localStorage.getItem(preferencesKey(id)) || '{}');
    return { ...defaults(), ...saved, mode: saved.mode === 'agent' ? 'agent' : 'studio' };
  } catch { return defaults(); }
};
const initialState = (id) => ({ ...readPreferences(id), projectId: id, nodes: [], rootId: '', activeNodeId: '', candidates: [],
  drafts: {}, versions: {}, selectedVersions: {}, expanded: {}, loading: false, error: '', busy: false, revision: 0 });

/** Project, node and conversation selections are independent; layout never owns a draft. */
export default function useWorkspace(projectId, refreshToken) {
  const [projects, setProjects] = useState({});
  const statesRef = useRef({});
  const loadsRef = useRef({});
  const state = projects[projectId] || initialState(projectId);

  const patch = useCallback((change) => {
    if (!projectId) return;
    const previous = statesRef.current;
    const current = previous[projectId] || initialState(projectId);
    const next = { ...current, ...(typeof change === 'function' ? change(current) : change) };
    const updated = { ...previous, [projectId]: next };
    statesRef.current = updated;
    setProjects(updated);
  }, [projectId]);

  const preferences = useCallback((change) => patch((current) => {
    const next = { ...current, ...change };
    try {
      const { mode, agentView, studioView, assistantCollapsed, widths } = next;
      localStorage.setItem(preferencesKey(projectId), JSON.stringify({ mode, agentView, studioView, assistantCollapsed, widths }));
    } catch { /* The work remains usable if browser storage is unavailable. */ }
    return change;
  }), [patch, projectId]);

  const refresh = useCallback(async () => {
    if (!projectId) return;
    try {
      const [work, candidates] = await Promise.all([api.getWorkspace(projectId), api.getCandidates(projectId)]);
      patch((current) => {
        const activeIds = new Set(work.nodes.map((node) => node.id));
        const retain = (entries) => Object.fromEntries(Object.entries(entries).filter(([id]) => activeIds.has(id)));
        return { ...work, candidates, loading: false, error: '', revision: current.revision + 1,
          drafts: retain(current.drafts), versions: retain(current.versions), selectedVersions: retain(current.selectedVersions),
          activeNodeId: activeIds.has(current.activeNodeId) ? current.activeNodeId : work.rootId };
      });
    } catch (error) { patch({ loading: false, error: error.message }); }
  }, [projectId, patch]);

  useEffect(() => { if (projectId) { patch({ loading: true }); refresh(); } }, [projectId, refresh, patch]);
  useEffect(() => { if (refreshToken) refresh(); }, [refreshToken, refresh]);

  const loadVersions = useCallback(async (nodeId) => {
    if (!projectId || !nodeId) return [];
    const key = `${projectId}:${nodeId}`;
    const request = (loadsRef.current[key] || 0) + 1;
    loadsRef.current[key] = request;
    const expectedHead = statesRef.current[projectId]?.nodes.find((node) => node.id === nodeId)?.currentDocumentId;
    const versions = await api.getVersions(projectId, nodeId);
    if (loadsRef.current[key] !== request || expectedHead !== statesRef.current[projectId]?.nodes.find((node) => node.id === nodeId)?.currentDocumentId) return versions;
    patch((current) => {
      const head = versions[0];
      const existing = current.drafts[nodeId];
      const node = current.nodes.find((item) => item.id === nodeId);
      const changedHead = (head?.id || null) !== (node?.currentDocumentId || null);
      const title = changedHead ? head?.title || node?.title : node?.title || head?.title;
      const draft = existing?.dirty ? existing : { title: title || '正文', content: head?.content || '', baseDocumentId: head?.id || null, dirty: false };
      return { versions: { ...current.versions, [nodeId]: versions }, drafts: { ...current.drafts, [nodeId]: draft },
        ...(changedHead ? { nodes: current.nodes.map((entry) => entry.id === nodeId ? { ...entry, currentDocumentId: head?.id || null, version: head?.version || 0, title: title || entry.title } : entry) } : {}) };
    });
    return versions;
  }, [projectId, patch]);

  const activeNode = state.nodes.find((node) => node.id === state.activeNodeId);
  useEffect(() => {
    if (activeNode?.id) loadVersions(activeNode.id).catch((error) => patch({ error: error.message }));
  }, [activeNode?.id, activeNode?.currentDocumentId, activeNode?.title, loadVersions, patch]);

  const selectNode = useCallback((nodeId) => patch((current) => ({ activeNodeId: nodeId,
    ...(current.mode === 'agent' ? { agentView: 'document' } : { studioView: 'document' }) })), [patch]);

  const updateDraft = useCallback((nodeId, change) => patch((current) => ({ drafts: { ...current.drafts,
    [nodeId]: { ...current.drafts[nodeId], ...change, dirty: true } } })), [patch]);

  const applySnapshot = useCallback((nodeId, document) => patch((current) => ({
    nodes: current.nodes.map((node) => node.id === nodeId ? { ...node, currentDocumentId: document.id, version: document.version, title: document.title } : node),
    versions: { ...current.versions, [nodeId]: [document, ...(current.versions[nodeId] || []).filter((item) => item.id !== document.id)] },
    selectedVersions: { ...current.selectedVersions, [nodeId]: null },
    drafts: { ...current.drafts, [nodeId]: { title: document.title, content: document.content || '', baseDocumentId: document.id, dirty: false } },
  })), [patch]);

  const save = useCallback(async (nodeId, sourceSessionId, override) => {
    const current = statesRef.current[projectId];
    const draft = override || current?.drafts[nodeId];
    if (!draft) throw new Error('正文尚在加载，请稍后再保存');
    const document = await api.saveSnapshot(projectId, nodeId, { title: draft.title || '正文', content: draft.content || '',
      baseDocumentId: draft.baseDocumentId || null, sourceSessionId: sourceSessionId || null });
    // A save may finish after the user has typed again. Preserve those newer keystrokes.
    const newest = statesRef.current[projectId]?.drafts[nodeId];
    applySnapshot(nodeId, document);
    if (!override && newest && newest !== draft && (newest.content !== draft.content || newest.title !== draft.title)) {
      patch((latest) => ({ drafts: { ...latest.drafts, [nodeId]: { ...newest, baseDocumentId: document.id, dirty: true } } }));
    }
    return document;
  }, [projectId, applySnapshot, patch]);

  const prepareTarget = useCallback(async (operation, sourceSessionId, requestedNodeId) => {
    const captured = statesRef.current[projectId];
    const nodeId = requestedNodeId || captured?.activeNodeId;
    const node = captured?.nodes.find((entry) => entry.id === nodeId);
    if (!node) throw new Error('请先选择正文节点');
    let baseDocumentId = node.currentDocumentId;
    const draft = captured.drafts[nodeId];
    if (operation !== 'DISCUSS' && draft?.dirty) baseDocumentId = (await save(nodeId, sourceSessionId)).id;
    const target = { projectId, nodeId, operation, baseDocumentId: baseDocumentId || null,
      ...(operation === 'CREATE_NODE' ? { parentId: node.root ? node.id : node.parentId, afterNodeId: node.root ? null : node.id } : {}) };
    patch({ taskTarget: target });
    return target;
  }, [projectId, save, patch]);

  const adopt = useCallback(async (candidate) => {
    if (statesRef.current[projectId]?.drafts[candidate.targetNodeId]?.dirty) throw new Error('目标正文有未保存修改，请先保存，再基于最新正文重新生成候选');
    const result = await api.adoptCandidate(projectId, candidate.id);
    applySnapshot(result.node.id, result.document);
    await refresh();
    return result;
  }, [projectId, applySnapshot, refresh]);

  const mutate = useCallback(async (action) => {
    patch({ busy: true, error: '' });
    try { const result = await action(); await refresh(); return result; }
    catch (error) { patch({ error: error.message }); throw error; }
    finally { patch({ busy: false }); }
  }, [patch, refresh]);

  useEffect(() => {
    const beforeUnload = (event) => {
      if (Object.values(statesRef.current).some((work) => Object.values(work.drafts).some((draft) => draft.dirty))) { event.preventDefault(); event.returnValue = ''; }
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, []);

  return { ...state, activeNode, patch, preferences, refresh, selectNode, loadVersions, updateDraft, applySnapshot, save, prepareTarget, adopt, mutate };
}
