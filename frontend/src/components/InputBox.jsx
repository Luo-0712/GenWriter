import { useState, useRef, useEffect, useCallback } from 'react';
import AttachmentPreview from './AttachmentPreview';
import { uploadAttachment, deleteAttachment, getThumbnailUrl } from '../api/attachments';
import '../styles/global.css';

const MODES = [
  { value: 'AUTO', label: '自动识别', icon: '✦' },
  { value: 'CREATE', label: '新建文档', icon: '✎' },
  { value: 'CONTINUE', label: '续写', icon: '→' },
  { value: 'POLISH', label: '润色优化', icon: '✧' },
  { value: 'KNOWLEDGE_QA', label: '知识问答', icon: '?' },
];

const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'];
const ALLOWED_DOC_EXTENSIONS = ['pdf', 'doc', 'docx', 'txt', 'md', 'csv', 'json', 'xml'];
const MAX_IMAGE_SIZE = 20 * 1024 * 1024;
const MAX_DOC_SIZE = 50 * 1024 * 1024;
const MAX_ATTACHMENTS = 10;

const InputBox = ({ onSend, disabled, isLoading, kbId = '', sessionId = '' }) => {
  const [inputValue, setInputValue] = useState('');
  const [mode, setMode] = useState('AUTO');
  const [webSearch, setWebSearch] = useState(true);
  const [attachments, setAttachments] = useState([]);
  const [isDragOver, setIsDragOver] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const textareaRef = useRef(null);
  const fileInputRef = useRef(null);

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 200) + 'px';
    }
  }, [inputValue]);

  const validateFile = (file) => {
    const isImage = ALLOWED_IMAGE_TYPES.includes(file.type);
    const ext = file.name.split('.').pop()?.toLowerCase();
    const isDoc = ALLOWED_DOC_EXTENSIONS.includes(ext);

    if (!isImage && !isDoc) {
      return '不支持的文件类型';
    }
    if (isImage && file.size > MAX_IMAGE_SIZE) {
      return '图片大小不能超过 20MB';
    }
    if (isDoc && file.size > MAX_DOC_SIZE) {
      return '文档大小不能超过 50MB';
    }
    return null;
  };

  const handleFileUpload = async (files) => {
    if (!files || files.length === 0) return;
    if (attachments.length + files.length > MAX_ATTACHMENTS) {
      alert(`单次消息最多携带 ${MAX_ATTACHMENTS} 个附件`);
      return;
    }
    if (!sessionId) {
      alert('请先创建会话');
      return;
    }

    setIsUploading(true);
    const newAttachments = [];

    for (const file of files) {
      const error = validateFile(file);
      if (error) {
        alert(`${file.name}: ${error}`);
        continue;
      }

      const tempId = `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      const att = {
        id: null,
        tempId,
        originalFilename: file.name,
        fileSize: file.size,
        attachmentType: ALLOWED_IMAGE_TYPES.includes(file.type) ? 'IMAGE' : 'DOCUMENT',
        mimeType: file.type,
        thumbnailUrl: null,
        uploadProgress: 0,
        error: null,
        rawFile: file,
      };
      newAttachments.push(att);
      setAttachments((prev) => [...prev, att]);

      try {
        const result = await uploadAttachment(file, sessionId, (progress) => {
          setAttachments((prev) =>
            prev.map((a) =>
              a.tempId === tempId ? { ...a, uploadProgress: progress } : a
            )
          );
        });
        setAttachments((prev) =>
          prev.map((a) =>
            a.tempId === tempId
              ? {
                  ...a,
                  id: result.id,
                  uploadProgress: 100,
                  thumbnailUrl: a.attachmentType === 'IMAGE' ? getThumbnailUrl(result.id) : null,
                }
              : a
          )
        );
      } catch (e) {
        setAttachments((prev) =>
          prev.map((a) =>
            a.tempId === tempId
              ? { ...a, error: e.message }
              : a
          )
        );
      }
    }
    setIsUploading(false);
  };

  const handleRemoveAttachment = useCallback(async (attIdOrTempId) => {
    const att = attachments.find(
      (a) => a.id === attIdOrTempId || a.tempId === attIdOrTempId
    );
    if (att && att.id && !att.error) {
      try {
        await deleteAttachment(att.id, sessionId);
      } catch (e) {
        // ignore delete error on server side
      }
    }
    setAttachments((prev) =>
      prev.filter((a) => a.id !== attIdOrTempId && a.tempId !== attIdOrTempId)
    );
  }, [attachments, sessionId]);

  const handleRetryAttachment = useCallback(async (att) => {
    if (!att.rawFile || !sessionId) return;
    const tempId = att.tempId;
    // Reset the attachment state
    setAttachments((prev) =>
      prev.map((a) =>
        a.tempId === tempId
          ? { ...a, uploadProgress: 0, error: null }
          : a
      )
    );
    try {
      const result = await uploadAttachment(att.rawFile, sessionId, (progress) => {
        setAttachments((prev) =>
          prev.map((a) =>
            a.tempId === tempId ? { ...a, uploadProgress: progress } : a
          )
        );
      });
      setAttachments((prev) =>
        prev.map((a) =>
          a.tempId === tempId
            ? {
                ...a,
                id: result.id,
                uploadProgress: 100,
                thumbnailUrl: a.attachmentType === 'IMAGE' ? getThumbnailUrl(result.id) : null,
              }
            : a
        )
      );
    } catch (e) {
      setAttachments((prev) =>
        prev.map((a) =>
          a.tempId === tempId
            ? { ...a, error: e.message }
            : a
        )
      );
    }
  }, [sessionId]);

  const handleSubmit = () => {
    const trimmed = inputValue.trim();
    const hasContent = trimmed || attachments.some((a) => a.id && !a.error);
    if (!hasContent || isLoading || disabled || isUploading) return;

    const attachmentIds = attachments
      .filter((a) => a.id && !a.error)
      .map((a) => a.id);

    onSend(trimmed, mode, webSearch, kbId, attachmentIds);
    setInputValue('');
    setAttachments([]);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  };

  const handleFileSelect = (e) => {
    handleFileUpload(Array.from(e.target.files));
    e.target.value = '';
  };

  const handleDragOver = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(true);
  };

  const handleDragLeave = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
    const files = Array.from(e.dataTransfer.files);
    handleFileUpload(files);
  };

  const handlePaste = (e) => {
    const items = e.clipboardData?.items;
    if (!items) return;
    const imageFiles = [];
    for (const item of items) {
      if (item.type.startsWith('image/')) {
        const file = item.getAsFile();
        if (file) imageFiles.push(file);
      }
    }
    if (imageFiles.length > 0) {
      e.preventDefault();
      handleFileUpload(imageFiles);
    }
  };

  const canSend = (inputValue.trim() || attachments.some((a) => a.id && !a.error)) && !isLoading && !disabled && !isUploading;

  return (
    <div className="input-container">
      <div
        className={`input-wrapper${isDragOver ? ' drag-over' : ''}`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <button
          className="attachment-btn"
          onClick={() => fileInputRef.current?.click()}
          disabled={disabled || isUploading}
          title="添加附件"
          type="button"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" />
          </svg>
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/jpeg,image/png,image/gif,image/webp,.pdf,.doc,.docx,.txt,.md,.csv,.json,.xml"
          multiple
          onChange={handleFileSelect}
          style={{ display: 'none' }}
        />
        <textarea
          ref={textareaRef}
          className="input-textarea"
          placeholder="输入消息... (可拖拽或粘贴图片)"
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
          disabled={disabled || isLoading}
          rows={1}
        />
        <button
          className="send-btn"
          onClick={handleSubmit}
          disabled={!canSend}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <line x1="22" y1="2" x2="11" y2="13"></line>
            <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
          </svg>
        </button>
      </div>
      <AttachmentPreview attachments={attachments} onRemove={handleRemoveAttachment} onRetry={handleRetryAttachment} />
      <div className="mode-selector">
        <div className="mode-pills">
          {MODES.map((m) => (
            <button
              key={m.value}
              className={`mode-pill${mode === m.value ? ' active' : ''}`}
              onClick={() => setMode(m.value)}
              type="button"
            >
              <span className="mode-pill-icon">{m.icon}</span>
              {m.label}
            </button>
          ))}
        </div>
        <button
          className={`websearch-toggle${webSearch ? ' active' : ''}`}
          onClick={() => setWebSearch(!webSearch)}
          type="button"
          title={webSearch ? '联网搜索已开启' : '联网搜索已关闭'}
        >
          <span className="websearch-toggle-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="8"></circle>
              <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
          </span>
          <span className="websearch-toggle-label">联网搜索</span>
        </button>
      </div>
      <p className="input-hint">按 Enter 发送，Shift + Enter 换行 | 支持拖拽/粘贴图片</p>
    </div>
  );
};

export default InputBox;
