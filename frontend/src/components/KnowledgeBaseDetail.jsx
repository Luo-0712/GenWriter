import { useState, useEffect, useCallback } from 'react';
import * as knowledgeBasesApi from '../api/knowledgeBases';
import * as knowledgeChunksApi from '../api/knowledgeChunks';
import * as ragApi from '../api/rag';
import '../styles/global.css';

const STRATEGY_MAP = {
  recursive: '递归分块',
  fixed: '固定长度',
  semantic: '语义分块',
};

const KnowledgeBaseDetail = ({ kbId, onBack }) => {
  const [knowledgeBase, setKnowledgeBase] = useState(null);
  const [chunks, setChunks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const [selectedFile, setSelectedFile] = useState(null);
  const [strategy, setStrategy] = useState('recursive');
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState(null);
  const [uploadSuccess, setUploadSuccess] = useState(false);

  const fetchChunks = useCallback(async () => {
    setLoading(true);
    try {
      const result = await knowledgeChunksApi.getChunksByKnowledgeBase(kbId);
      const list = Array.isArray(result) ? result : result?.items || [];
      setChunks(list);
    } catch (e) {
      setError('加载知识片段失败: ' + e.message);
    } finally {
      setLoading(false);
    }
  }, [kbId]);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const kb = await knowledgeBasesApi.getKnowledgeBaseById(kbId);
        if (!cancelled) setKnowledgeBase(kb);
      } catch (e) {
        if (!cancelled) setError('加载知识库失败: ' + e.message);
      }
      try {
        const result = await knowledgeChunksApi.getChunksByKnowledgeBase(kbId);
        if (!cancelled) {
          const list = Array.isArray(result) ? result : result?.items || [];
          setChunks(list);
        }
      } catch (e) {
        if (!cancelled) setError('加载知识片段失败: ' + e.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    load();
    return () => {
      cancelled = true;
    };
  }, [kbId]);

  const handleFileChange = (e) => {
    const file = e.target.files?.[0];
    if (file) {
      setSelectedFile(file);
      setUploadError(null);
      setUploadSuccess(false);
    }
  };

  const handleUpload = async () => {
    if (!selectedFile) {
      setUploadError('请先选择文件');
      return;
    }
    setUploading(true);
    setUploadError(null);
    setUploadSuccess(false);
    try {
      await ragApi.uploadDocument(selectedFile, kbId, strategy);
      setUploadSuccess(true);
      setSelectedFile(null);
      // 重置文件输入
      const fileInput = document.getElementById('kb-upload-input');
      if (fileInput) fileInput.value = '';
      fetchChunks();
    } catch (e) {
      setUploadError(e.message || '上传失败');
    } finally {
      setUploading(false);
    }
  };

  const handleDeleteChunk = async (chunkId) => {
    if (!window.confirm('确定要删除这个知识片段吗？')) return;
    try {
      await knowledgeChunksApi.deleteChunk(chunkId);
      fetchChunks();
    } catch (e) {
      setError(e.message);
    }
  };

  const handleClearAll = async () => {
    if (!window.confirm(`确定要清空"${knowledgeBase?.name}"的所有知识片段吗？此操作不可恢复。`)) return;
    try {
      await knowledgeChunksApi.deleteChunksByKnowledgeBase(kbId);
      fetchChunks();
    } catch (e) {
      setError(e.message);
    }
  };

  return (
    <div className="memory-panel">
      <div className="memory-panel-header">
        <div className="memory-panel-header-left">
          <button className="memory-back-btn" onClick={onBack} title="返回">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="15 18 9 12 15 6"></polyline>
            </svg>
          </button>
          <h2 className="memory-panel-title">
            {knowledgeBase?.name || '知识库详情'}
          </h2>
          <span className="memory-panel-count">共 {chunks.length} 个片段</span>
        </div>
        {chunks.length > 0 && (
          <button className="memory-new-btn" onClick={handleClearAll} style={{ background: '#fef2f2', color: '#dc2626', borderColor: '#fecaca' }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="3 6 5 6 21 6"></polyline>
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
            </svg>
            清空片段
          </button>
        )}
      </div>

      {/* 上传区域 */}
      <div className="panel-section" style={{ padding: '14px var(--panel-padding-x)', borderBottom: '1px solid var(--border-light)', background: '#fafafa' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <input
            id="kb-upload-input"
            type="file"
            onChange={handleFileChange}
            style={{ display: 'none' }}
            accept=".txt,.md,.pdf,.doc,.docx"
          />
          <label
            htmlFor="kb-upload-input"
            style={{
              padding: '8px 16px',
              border: '1px dashed var(--border-color)',
              borderRadius: 'var(--radius-sm)',
              cursor: 'pointer',
              fontSize: 13,
              color: 'var(--text-secondary)',
              background: 'var(--bg-primary)',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
              <polyline points="17 8 12 3 7 8"></polyline>
              <line x1="12" y1="3" x2="12" y2="15"></line>
            </svg>
            {selectedFile ? selectedFile.name : '选择文件'}
          </label>

          <select
            value={strategy}
            onChange={(e) => setStrategy(e.target.value)}
            className="memory-filter-select"
            style={{ margin: 0 }}
          >
            {Object.entries(STRATEGY_MAP).map(([k, v]) => (
              <option key={k} value={k}>{v}</option>
            ))}
          </select>

          <button
            className="memory-new-btn"
            onClick={handleUpload}
            disabled={uploading || !selectedFile}
            style={{ margin: 0 }}
          >
            {uploading ? (
              <>
                <div className="loading-spinner" style={{ width: 14, height: 14, borderWidth: 2 }}></div>
                上传中...
              </>
            ) : (
              <>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                  <polyline points="17 8 12 3 7 8"></polyline>
                  <line x1="12" y1="3" x2="12" y2="15"></line>
                </svg>
                上传文档
              </>
            )}
          </button>
        </div>

        {uploadError && (
          <div style={{ marginTop: 8, fontSize: 13, color: '#dc2626' }}>{uploadError}</div>
        )}
        {uploadSuccess && (
          <div style={{ marginTop: 8, fontSize: 13, color: '#16a34a' }}>上传成功，正在处理中...</div>
        )}
      </div>

      {error && (
        <div className="memory-error" onClick={() => setError(null)}>
          {error}
        </div>
      )}

      {loading && chunks.length === 0 ? (
        <div className="memory-loading">
          <div className="loading-spinner"></div>
          <p>加载中...</p>
        </div>
      ) : chunks.length === 0 ? (
        <div className="memory-empty">
          <p>暂无知识片段</p>
          <span>上传文档后，系统会自动解析并生成分块</span>
        </div>
      ) : (
        <>
          <div className="memory-table-header" style={{ gridTemplateColumns: '1fr 140px 80px', paddingLeft: 'var(--panel-padding-x)' }}>
            <span>内容预览</span>
            <span>创建时间</span>
            <span>操作</span>
          </div>
          <div className="memory-list">
            {chunks.map((chunk) => (
              <div
                key={chunk.id}
                className="memory-card"
                style={{ gridTemplateColumns: '1fr 140px 80px', paddingLeft: 'var(--panel-padding-x)', paddingRight: 'var(--panel-padding-x)' }}
              >
                <div className="memory-card-content" title={chunk.content}>
                  {chunk.content?.substring(0, 120)}
                  {chunk.content?.length > 120 ? '...' : ''}
                </div>
                <div className="memory-card-updated">
                  {chunk.createdAt ? new Date(chunk.createdAt).toLocaleString() : '-'}
                </div>
                <div className="memory-card-actions">
                  <button
                    className="memory-action-btn memory-action-delete"
                    onClick={() => handleDeleteChunk(chunk.id)}
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
    </div>
  );
};

export default KnowledgeBaseDetail;
