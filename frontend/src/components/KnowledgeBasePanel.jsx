import { useState, useEffect, useCallback, useRef } from 'react';
import * as knowledgeBasesApi from '../api/knowledgeBases';
import KnowledgeBaseDetail from './KnowledgeBaseDetail';
import '../styles/global.css';

const KB_TYPE_MAP = {
  reference: '参考资料',
  template: '模板',
  style: '风格指南',
};

const KnowledgeBasePanel = ({ onBack }) => {
  const [knowledgeBases, setKnowledgeBases] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const [keyword, setKeyword] = useState('');
  const [filterType, setFilterType] = useState('');
  const searchDebounceRef = useRef(null);

  const [showModal, setShowModal] = useState(false);
  const [editingKB, setEditingKB] = useState(null);
  const [formData, setFormData] = useState({
    name: '',
    description: '',
    type: 'reference',
  });
  const [formError, setFormError] = useState(null);
  const [saving, setSaving] = useState(false);

  const [detailKbId, setDetailKbId] = useState(null);

  const fetchKnowledgeBases = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      let result;
      if (keyword.trim()) {
        result = await knowledgeBasesApi.searchKnowledgeBases(keyword.trim());
      } else {
        result = await knowledgeBasesApi.getAllKnowledgeBases();
      }
      let list = Array.isArray(result) ? result : result?.items || [];
      if (filterType) {
        list = list.filter((kb) => kb.type === filterType);
      }
      setKnowledgeBases(list);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [keyword, filterType]);

  useEffect(() => {
    if (searchDebounceRef.current) {
      clearTimeout(searchDebounceRef.current);
    }
    searchDebounceRef.current = setTimeout(() => {
      fetchKnowledgeBases();
    }, 300);
    return () => {
      if (searchDebounceRef.current) {
        clearTimeout(searchDebounceRef.current);
      }
    };
  }, [fetchKnowledgeBases]);

  const handleOpenCreate = () => {
    setEditingKB(null);
    setFormData({ name: '', description: '', type: 'reference' });
    setFormError(null);
    setShowModal(true);
  };

  const handleOpenEdit = (kb) => {
    setEditingKB(kb);
    setFormData({
      name: kb.name || '',
      description: kb.description || '',
      type: kb.type || 'reference',
    });
    setFormError(null);
    setShowModal(true);
  };

  const handleCloseModal = () => {
    setShowModal(false);
    setEditingKB(null);
    setFormError(null);
  };

  const validateForm = () => {
    if (!formData.name || !formData.name.trim()) {
      return '知识库名称不能为空';
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
        name: formData.name.trim(),
        description: formData.description.trim(),
        type: formData.type,
      };
      if (editingKB) {
        await knowledgeBasesApi.updateKnowledgeBase(editingKB.id, payload);
      } else {
        await knowledgeBasesApi.createKnowledgeBase(payload);
      }
      setShowModal(false);
      fetchKnowledgeBases();
    } catch (e) {
      setFormError(e.message);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (kb) => {
    if (!window.confirm(`确定要删除知识库"${kb.name}"吗？`)) return;
    try {
      await knowledgeBasesApi.deleteKnowledgeBase(kb.id);
      fetchKnowledgeBases();
    } catch (e) {
      setError(e.message);
    }
  };

  if (detailKbId) {
    return (
      <KnowledgeBaseDetail
        kbId={detailKbId}
        onBack={() => setDetailKbId(null)}
      />
    );
  }

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
          <h2 className="memory-panel-title">知识库</h2>
          <span className="memory-panel-count">共 {knowledgeBases.length} 个</span>
        </div>
        <button className="memory-new-btn" onClick={handleOpenCreate}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="12" y1="5" x2="12" y2="19"></line>
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
          新建知识库
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
            placeholder="搜索知识库名称..."
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            className="memory-search-input"
          />
        </div>
        <select
          value={filterType}
          onChange={(e) => setFilterType(e.target.value)}
          className="memory-filter-select"
        >
          <option value="">全部类型</option>
          {Object.entries(KB_TYPE_MAP).map(([k, v]) => (
            <option key={k} value={k}>{v}</option>
          ))}
        </select>
      </div>

      {error && (
        <div className="memory-error" onClick={() => setError(null)}>
          {error}
        </div>
      )}

      {loading && knowledgeBases.length === 0 ? (
        <div className="memory-loading">
          <div className="loading-spinner"></div>
          <p>加载中...</p>
        </div>
      ) : knowledgeBases.length === 0 ? (
        <div className="memory-empty">
          <p>暂无知识库</p>
          <span>创建知识库后，您可以上传文档进行RAG检索</span>
        </div>
      ) : (
        <>
          <div
            className="memory-table-header"
            style={{ gridTemplateColumns: '1fr 100px 140px 100px', paddingLeft: 'var(--panel-padding-x)' }}
          >
            <span>名称 / 描述</span>
            <span>类型</span>
            <span>更新时间</span>
            <span>操作</span>
          </div>
          <div className="memory-list">
            {knowledgeBases.map((kb) => (
              <div
                key={kb.id}
                className="memory-card"
                style={{ gridTemplateColumns: '1fr 100px 140px 100px', paddingLeft: 'var(--panel-padding-x)', paddingRight: 'var(--panel-padding-x)' }}
              >
                <div className="memory-card-content">
                  <div style={{ fontWeight: 500, marginBottom: 2 }}>{kb.name}</div>
                  {kb.description && (
                    <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{kb.description}</div>
                  )}
                </div>
                <div className="memory-card-type">
                  <span className="memory-badge" style={{ background: '#f0f9ff', color: '#0ea5e9' }}>
                    {KB_TYPE_MAP[kb.type] || kb.type}
                  </span>
                </div>
                <div className="memory-card-updated">
                  {kb.updatedAt ? new Date(kb.updatedAt).toLocaleString() : '-'}
                </div>
                <div className="memory-card-actions">
                  <button
                    className="memory-action-btn"
                    onClick={() => setDetailKbId(kb.id)}
                    title="进入详情"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                      <polyline points="14 2 14 8 20 8"></polyline>
                      <line x1="16" y1="13" x2="8" y2="13"></line>
                      <line x1="16" y1="17" x2="8" y2="17"></line>
                    </svg>
                  </button>
                  <button
                    className="memory-action-btn"
                    onClick={() => handleOpenEdit(kb)}
                    title="编辑"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                    </svg>
                  </button>
                  <button
                    className="memory-action-btn memory-action-delete"
                    onClick={() => handleDelete(kb)}
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
        </>
      )}

      {showModal && (
        <div className="memory-modal-overlay" onClick={handleCloseModal}>
          <div className="memory-modal" onClick={(e) => e.stopPropagation()}>
            <div className="memory-modal-header">
              <h3>{editingKB ? '编辑知识库' : '新建知识库'}</h3>
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
                <label>名称 <span className="required">*</span></label>
                <input
                  type="text"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  placeholder="输入知识库名称..."
                />
              </div>
              <div className="memory-form-group">
                <label>描述</label>
                <textarea
                  rows={3}
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  placeholder="输入知识库描述..."
                />
              </div>
              <div className="memory-form-group">
                <label>类型</label>
                <select
                  value={formData.type}
                  onChange={(e) => setFormData({ ...formData, type: e.target.value })}
                >
                  {Object.entries(KB_TYPE_MAP).map(([k, v]) => (
                    <option key={k} value={k}>{v}</option>
                  ))}
                </select>
              </div>
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

export default KnowledgeBasePanel;
