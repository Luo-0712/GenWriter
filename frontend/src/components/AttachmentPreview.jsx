import '../styles/global.css';

const formatSize = (bytes) => {
  if (!bytes) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
};

const AttachmentPreview = ({ attachments = [], onRemove, onRetry }) => {
  if (!attachments.length) return null;

  return (
    <div className="attachment-preview-area">
      {attachments.map((att) => {
        const key = att.id || att.tempId;
        const removeId = att.id || att.tempId;
        const progress = att.uploadProgress ?? 0;
        const isUploading = !att.id && !att.error;

        return (
          <div
            key={key}
            className={`attachment-preview-item ${att.error ? 'has-error' : ''}`}
            title={att.originalFilename}
          >
            {att.attachmentType === 'IMAGE' ? (
              <div className="attachment-preview-image">
                {att.thumbnailUrl ? (
                  <img src={att.thumbnailUrl} alt={att.originalFilename || '附件图片'} />
                ) : (
                  <div className="attachment-loading">
                    <div className="attachment-spinner" />
                    <span>上传中</span>
                  </div>
                )}
              </div>
            ) : (
              <div className="attachment-preview-doc">
                <span className="attachment-doc-icon">DOC</span>
                <span className="attachment-doc-info">
                  <span className="attachment-doc-name">{att.originalFilename || '文档'}</span>
                  <span className="attachment-doc-size">{formatSize(att.fileSize)}</span>
                </span>
              </div>
            )}

            <button
              className="attachment-preview-remove"
              onClick={() => onRemove?.(removeId)}
              type="button"
              title="移除附件"
            >
              ×
            </button>

            {isUploading && (
              <div className="attachment-progress">
                <div className="attachment-progress-bar" style={{ width: `${progress}%` }} />
              </div>
            )}
            {isUploading && <span className="attachment-progress-text">{progress}%</span>}

            {att.error && (
              <div className="attachment-error">
                <span>{att.error}</span>
                {att.rawFile && (
                  <button className="attachment-retry-btn" onClick={() => onRetry?.(att)} type="button">
                    重试
                  </button>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default AttachmentPreview;
