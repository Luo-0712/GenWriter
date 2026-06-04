import { useState } from 'react';
import { getThumbnailUrl } from '../api/attachments';

const FILE_ICONS = {
  pdf: '📄',
  doc: '📝', docx: '📝',
  txt: '📃', md: '📋',
  csv: '📊', json: '🔧', xml: '🔧',
  default: '📎',
};

const formatFileSize = (bytes) => {
  if (!bytes) return '';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

const getFileIcon = (filename) => {
  const ext = filename?.split('.').pop()?.toLowerCase();
  return FILE_ICONS[ext] || FILE_ICONS.default;
};

const AttachmentPreview = ({ attachments = [], onRemove, onRetry }) => {
  if (attachments.length === 0) return null;

  return (
    <div className="attachment-preview-area">
      {attachments.map((att) => (
        <div key={att.id || att.tempId} className={`attachment-preview-item ${att.error ? 'has-error' : ''}`}>
          {att.attachmentType === 'IMAGE' ? (
            <div className="attachment-preview-image">
              {att.uploadProgress !== undefined && att.uploadProgress < 100 && !att.error ? (
                <div className="attachment-loading">
                  <div className="attachment-progress">
                    <div className="attachment-progress-bar" style={{ width: `${att.uploadProgress}%` }} />
                  </div>
                  <span className="attachment-progress-text">{att.uploadProgress}%</span>
                </div>
              ) : att.thumbnailUrl ? (
                <img src={att.thumbnailUrl} alt={att.originalFilename} />
              ) : (
                <div className="attachment-loading">
                  <div className="attachment-spinner" />
                  <span>缩略图生成中...</span>
                </div>
              )}
            </div>
          ) : (
            <div className="attachment-preview-doc">
              <span className="attachment-doc-icon">{getFileIcon(att.originalFilename)}</span>
              <div className="attachment-doc-info">
                <span className="attachment-doc-name" title={att.originalFilename}>
                  {att.originalFilename}
                </span>
                <span className="attachment-doc-size">{formatFileSize(att.fileSize)}</span>
              </div>
            </div>
          )}
          {att.error ? (
            <div className="attachment-error">
              <span>上传失败</span>
              <button className="attachment-retry-btn" onClick={() => onRetry?.(att)}>重试</button>
            </div>
          ) : null}
          <button className="attachment-preview-remove" onClick={() => onRemove(att.id || att.tempId)} title="移除">
            ×
          </button>
        </div>
      ))}
    </div>
  );
};

export default AttachmentPreview;
