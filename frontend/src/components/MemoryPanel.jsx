import { useState, useEffect, useCallback, useRef } from 'react';
import * as memoriesApi from '../api/memories';
import '../styles/global.css';

const MEMORY_TYPE_MAP = {
  WRITING_PREFERENCE: '写作偏好',
  CORRECTION_PATTERN: '纠错模式',
  DOMAIN_KNOWLEDGE: '领域知识',
  WORLD_SETTING: '世界观设定',
  CHARACTER_PROFILE: '人物设定',
  FORESHADOWING: '伏笔',
  WRITING_TECHNIQUE: '写作技巧',
};

const SCOPE_MAP = {
  GLOBAL: '全局',
  PROJECT: '项目',
};

const IMPORTANCE_COLOR = {
  HIGH: '#dc2626',
  MEDIUM: '#d97706',
  LOW: '#16a34a',
};

const IMPORTANCE_LABEL = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
};

const MemoryPanel = ({ projectId, onBack }) => {
  const [memories, setMemories] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size] = useState(20);

  const [keyword, setKeyword] = useState('');
  const [filterType, setFilterType] = useState('');
  const [filterScope, setFilterScope] = useState('');
  const [filterImportance, setFilterImportance] = useState('');
  const searchDebounceRef = useRef(null);

  const [showModal, setShowModal] = useState(false);
  const [editingMemory, setEditingMemory] = useState(null);
  const [formData, setFormData] = useState({
    content: '',
    memoryType: 'WRITING_PREFERENCE',
    scope: 'GLOBAL',
    projectId: projectId || '',
    importance: 'MEDIUM',
  });
  const [formError, setFormError] = useState(null);
  const [saving, setSaving] = useState(false);

  const [selectedIds, setSelectedIds] = useState(new Set());
  const [deletingId, setDeletingId] = useState(null);

  const fetchMemories = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {
        page,
        size,
        ...(filterType ? { type: filterType } : {}),
        ...(filterScope ? { scope: filterScope } : {}),
        ...(filterImportance ? { importance: filterImportance } : {}),
        ...(keyword ? { keyword } : {}),
        ...(projectId ? { projectId } : {}),
      };
      const result = await memoriesApi.getMemories(params);
      setMemories(result.items || []);
      setTotal(result.total || 0);
      setSelectedIds(new Set());
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [page, size, filterType, filterScope, filterImportance, keyword, projectId]);

  useEffect(() => {
    if (searchDebounceRef.current) {
      clearTimeout(searchDebounceRef.current);
    }
    searchDebounceRef.current = setTimeout(() => {
      setPage(1);
      fetchMemories();
    }, 300);
    return () => {
      if (searchDebounceRef.current) {
        clearTimeout(searchDebounceRef.current);
      }
    };
  }, [keyword, filterType, filterScope, filterImportance, projectId]);

  useEffect(() => {
    fetchMemories();
  }, [page, fetchMemories]);

  const handleOpenCreate = () => {
    setEditingMemory(null);
    setFormData({
      content: '',
      memoryType: 'WRITING_PREFERENCE',
      scope: projectId ? 'PROJECT' : 'GLOBAL',
      projectId: projectId || '',
      importance: 'MEDIUM',
    });
    setFormError(null);
    setShowModal(true);
  };

  const handleOpenEdit = (memory) => {
    setEditingMemory(memory);
    setFormData({
      content: memory.content || '',
      memoryType: memory.memoryType || 'WRITING_PREFERENCE',
      scope: memory.scope || 'GLOBAL',
      projectId: memory.projectId || projectId || '',
      importance: memory.importance || 'MEDIUM',
    });
    setFormError(null);
    setShowModal(true);
  };

  const handleCloseModal = () => {
    setShowModal(false);
    setEditingMemory(null);
    setFormError(null);
  };

  const validateForm = () => {
    if (!formData.content || !formData.content.trim()) {
      return '记忆内容不能为空';
    }
    if (formData.scope === 'PROJECT' && !formData.projectId) {
      return '项目级记忆需要指定项目';
    }
    return null;
  };

  const handleSave = async () => {
    const err = validateForm();
    if (err) {
      setFormError(err);
      return;
    }
    setSaving(true);
    setFormError(null);
    try {
      const payload = {
        content: formData.content.trim(),
        memoryType: formData.memoryType,
        scope: formData.scope,
        ...(formData.projectId ? { projectId: formData.projectId } : {}),
        importance: formData.importance,
      };
      if (editingMemory) {
        await memoriesApi.updateMemory(editingMemory.id, payload);
      } else {
        await memoriesApi.createMemory(payload);
      }
      setShowModal(false);
      fetchMemories();
    } catch (e) {
      setFormError(e.message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id) => {
    if (!window.confirm('确定要删除这条记忆吗？')) return;
    setDeletingId(id);
    try {
      await memoriesApi.deleteMemory(id);
      fetchMemories();
    } catch (e) {
      setError(e.message);
    } finally {
      setDeletingId(null);
    }
  };

  const handleBatchDelete = async () => {
    if (selectedIds.size === 0) return;
    if (!window.confirm(`确定要删除选中的 ${selectedIds.size} 条记忆吗？`)) return;
    try {
      await memoriesApi.batchDeleteMemories(Array.from(selectedIds));
      fetchMemories();
    } catch (e) {
      setError(e.message);
    }
  };

  const toggleSelect = (id) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const toggleSelectAll = () => {
    if (selectedIds.size === memories.length && memories.length > 0) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(memories.map((m) => m.id)));
    }
  };

  const totalPages = Math.ceil(total / size) || 1;

  return (
    <div className="memory-panel">
      <div className="memory-panel-header">
        <div className="memory-panel-header-left">
          {onBack && (
            <button className="memory-back-btn" onClick={onBack} title="返回">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="15 18 9 12 15 6"></polyline>
              </svg>
            </button>
          )}
          <h2 className="memory-panel-title">长期记忆</h2>
          <span className="memory-panel-count">共 {total} 条</span>
        </div>
        <button className="memory-new-btn" onClick={handleOpenCreate}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="12" y1="5" x2="12" y2="19"></line>
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
          新建记忆
        </button>
      </div>

      <div className="memory-toolbar">
        <div className="memory-search">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="search-icon">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          <input
            type="text"
            placeholder="搜索记忆内容..."
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            className="memory-search-input"
          />
        </div>
        <select value={filterType} onChange={(e) => setFilterType(e.target.value)} className="memory-filter-select">
          <option value="">全部类型</option>
          {Object.entries(MEMORY_TYPE_MAP).map(([k, v]) => (
            <option key={k} value={k}>{v}</option>
          ))}
        </select>
        <select value={filterScope} onChange={(e) => setFilterScope(e.target.value)} className="memory-filter-select">
          <option value="">全部作用域</option>
          {Object.entries(SCOPE_MAP).map(([k, v]) => (
            <option key={k} value={k}>{v}</option>
          ))}
        </select>
        <select value={filterImportance} onChange={(e) => setFilterImportance(e.target.value)} className="memory-filter-select">
          <option value="">全部重要性</option>
          <option value="HIGH">高</option>
          <option value="MEDIUM">中</option>
          <option value="LOW">低</option>
        </select>
        {selectedIds.size > 0 && (
          <button className="memory-batch-delete-btn" onClick={handleBatchDelete}>
            删除选中 ({selectedIds.size})
          </button>
        )}
      </div>

      {error && (
        <div className="memory-error" onClick={() => setError(null)}>
          {error}
        </div>
      )}

      {loading && memories.length === 0 ? (
        <div className="memory-loading">
          <div className="loading-spinner"></div>
          <p>加载中...</p>
        </div>
      ) : memories.length === 0 ? (
        <div className="memory-empty">
          <p>暂无记忆</p>
          <span>系统会在对话中自动提取记忆，您也可以手动添加</span>
        </div>
      ) : (
        <>
          <div className="memory-table-header">
            <label className="memory-checkbox-wrap">
              <input
                type="checkbox"
                checked={memories.length > 0 && selectedIds.size === memories.length}
                onChange={toggleSelectAll}
              />
            </label>
            <span className="memory-col-content">内容</span>
            <span className="memory-col-type">类型</span>
            <span className="memory-col-scope">作用域</span>
            <span className="memory-col-importance">重要性</span>
            <span className="memory-col-updated">更新时间</span>
            <span className="memory-col-actions">操作</span>
          </div>
          <div className="memory-list">
            {memories.map((m) => (
              <div key={m.id} className={`memory-card ${selectedIds.has(m.id) ? 'selected' : ''}`}>
                <label className="memory-checkbox-wrap">
                  <input
                    type="checkbox"
                    checked={selectedIds.has(m.id)}
                    onChange={() => toggleSelect(m.id)}
                  />
                </label>
                <div className="memory-card-content" title={m.content}>
                  {m.content}
                </div>
                <div className="memory-card-type">
                  <span className={`memory-badge memory-badge-type-${m.memoryType}`}>
                    {MEMORY_TYPE_MAP[m.memoryType] || m.memoryType}
                  </span>
                </div>
                <div className="memory-card-scope">
                  {SCOPE_MAP[m.scope] || m.scope}
                </div>
                <div className="memory-card-importance">
                  <span
                    className="memory-importance-dot"
                    style={{ background: IMPORTANCE_COLOR[m.importance] || '#999' }}
                  />
                  {IMPORTANCE_LABEL[m.importance] || m.importance}
                </div>
                <div className="memory-card-updated">
                  {m.updatedAt ? new Date(m.updatedAt).toLocaleString() : '-'}
                </div>
                <div className="memory-card-actions">
                  <button className="memory-action-btn" onClick={() => handleOpenEdit(m)} title="编辑">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                    </svg>
                  </button>
                  <button
                    className="memory-action-btn memory-action-delete"
                    onClick={() => handleDelete(m.id)}
                    disabled={deletingId === m.id}
                    title="删除"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <polyline points="3 6 5 6 21 6"></polyline>
                      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    </svg>
                  </button>
                </div>
              </div>
            ))}
          </div>
          <div className="memory-pagination">
            <button
              className="memory-page-btn"
              disabled={page <= 1}
              onClick={() => setPage(page - 1)}
            >
              上一页
            </button>
            <span className="memory-page-info">
              第 {page} / {totalPages} 页
            </span>
            <button
              className="memory-page-btn"
              disabled={page >= totalPages}
              onClick={() => setPage(page + 1)}
            >
              下一页
            </button>
          </div>
        </>
      )}

      {showModal && (
        <div className="memory-modal-overlay" onClick={handleCloseModal}>
          <div className="memory-modal" onClick={(e) => e.stopPropagation()}>
            <div className="memory-modal-header">
              <h3>{editingMemory ? '编辑记忆' : '新建记忆'}</h3>
              <button className="memory-modal-close" onClick={handleCloseModal}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
              </button>
            </div>
            <div className="memory-modal-body">
              {formError && <div className="memory-form-error">{formError}</div>}
              <div className="memory-form-group">
                <label>记忆内容 <span className="required">*</span></label>
                <textarea
                  rows={4}
                  value={formData.content}
                  onChange={(e) => setFormData({ ...formData, content: e.target.value })}
                  placeholder="输入记忆内容..."
                />
              </div>
              <div className="memory-form-row">
                <div className="memory-form-group">
                  <label>类型</label>
                  <select
                    value={formData.memoryType}
                    onChange={(e) => setFormData({ ...formData, memoryType: e.target.value })}
                  >
                    {Object.entries(MEMORY_TYPE_MAP).map(([k, v]) => (
                      <option key={k} value={k}>{v}</option>
                    ))}
                  </select>
                </div>
                <div className="memory-form-group">
                  <label>作用域</label>
                  <select
                    value={formData.scope}
                    onChange={(e) => setFormData({ ...formData, scope: e.target.value })}
                  >
                    {Object.entries(SCOPE_MAP).map(([k, v]) => (
                      <option key={k} value={k}>{v}</option>
                    ))}
                  </select>
                </div>
                <div className="memory-form-group">
                  <label>重要性</label>
                  <select
                    value={formData.importance}
                    onChange={(e) => setFormData({ ...formData, importance: e.target.value })}
                  >
                    <option value="HIGH">高</option>
                    <option value="MEDIUM">中</option>
                    <option value="LOW">低</option>
                  </select>
                </div>
              </div>
              {formData.scope === 'PROJECT' && (
                <div className="memory-form-group">
                  <label>项目 ID</label>
                  <input
                    type="text"
                    value={formData.projectId}
                    onChange={(e) => setFormData({ ...formData, projectId: e.target.value })}
                    placeholder="输入项目 ID"
                  />
                </div>
              )}
            </div>
            <div className="memory-modal-footer">
              <button className="memory-btn-secondary" onClick={handleCloseModal}>
                取消
              </button>
              <button className="memory-btn-primary" onClick={handleSave} disabled={saving}>
                {saving ? '保存中...' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default MemoryPanel;
