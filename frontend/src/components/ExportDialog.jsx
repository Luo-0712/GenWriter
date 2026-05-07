import { useState, useEffect, useRef } from 'react';
import { exportDocument } from '../api/documents';
import '../styles/global.css';

const ExportDialog = ({ message, sessionId, onClose }) => {
  const defaultTitle = (message?.content || '').substring(0, 50).replace(/\n/g, ' ').trim();
  const [title, setTitle] = useState(defaultTitle);
  const [format, setFormat] = useState('markdown');
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState(null);
  const inputRef = useRef(null);

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
  }, []);

  const handleExport = async () => {
    if (!title.trim()) return;
    setExporting(true);
    setError(null);
    try {
      const blob = await exportDocument({
        sessionId,
        title: title.trim(),
        content: message.content,
        format,
      });
      const ext = format === 'docx' ? 'docx' : 'md';
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${title.trim()}.${ext}`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      onClose();
    } catch (e) {
      setError('导出失败: ' + (e.message || '请重试'));
    } finally {
      setExporting(false);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !exporting) handleExport();
    if (e.key === 'Escape') onClose();
  };

  return (
    <div className="export-dialog-overlay" onClick={onClose}>
      <div className="export-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="export-dialog-header">导出文档</div>
        <div className="export-dialog-body">
          <label className="export-dialog-label">文档标题</label>
          <input
            ref={inputRef}
            className="export-dialog-input"
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={handleKeyDown}
            maxLength={500}
            placeholder="输入文档标题"
          />
          <label className="export-dialog-label">导出格式</label>
          <div className="export-format-options">
            <button
              className={`export-format-btn ${format === 'markdown' ? 'active' : ''}`}
              onClick={() => setFormat('markdown')}
            >
              <span className="export-format-icon">.md</span>
              <span>Markdown</span>
            </button>
            <button
              className={`export-format-btn ${format === 'docx' ? 'active' : ''}`}
              onClick={() => setFormat('docx')}
            >
              <span className="export-format-icon">.docx</span>
              <span>Word</span>
            </button>
          </div>
          {error && <div className="export-dialog-error">{error}</div>}
        </div>
        <div className="export-dialog-footer">
          <button className="export-cancel-btn" onClick={onClose} disabled={exporting}>取消</button>
          <button className="export-confirm-btn" onClick={handleExport} disabled={exporting || !title.trim()}>
            {exporting ? '导出中...' : '导出'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ExportDialog;
