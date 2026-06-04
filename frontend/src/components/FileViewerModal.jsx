import { useState, useEffect, useCallback, useRef } from 'react';

const FileViewerModal = ({ imageUrl, onClose }) => {
  const [scale, setScale] = useState(1);
  const [position, setPosition] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const dragStart = useRef({ x: 0, y: 0 });
  const posStart = useRef({ x: 0, y: 0 });

  const handleKeyDown = useCallback((e) => {
    if (e.key === 'Escape') onClose();
  }, [onClose]);

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);

  const handleWheel = (e) => {
    e.preventDefault();
    const delta = e.deltaY > 0 ? -0.2 : 0.2;
    setScale((prev) => Math.min(Math.max(0.5, prev + delta), 5));
  };

  const handleMouseDown = (e) => {
    if (e.target.classList.contains('file-viewer-overlay')) {
      onClose();
      return;
    }
    setIsDragging(true);
    dragStart.current = { x: e.clientX, y: e.clientY };
    posStart.current = { ...position };
  };

  const handleMouseMove = (e) => {
    if (!isDragging) return;
    setPosition({
      x: posStart.current.x + (e.clientX - dragStart.current.x),
      y: posStart.current.y + (e.clientY - dragStart.current.y),
    });
  };

  const handleMouseUp = () => {
    setIsDragging(false);
  };

  const zoomIn = () => setScale((prev) => Math.min(prev + 0.5, 5));
  const zoomOut = () => setScale((prev) => Math.max(prev - 0.5, 0.5));
  const resetZoom = () => { setScale(1); setPosition({ x: 0, y: 0 }); };

  if (!imageUrl) return null;

  return (
    <div className="file-viewer-modal" onMouseDown={handleMouseDown} onMouseMove={handleMouseMove} onMouseUp={handleMouseUp}>
      <div className="file-viewer-overlay" />
      <div className="file-viewer-toolbar">
        <button onClick={zoomOut} title="缩小">−</button>
        <span>{Math.round(scale * 100)}%</span>
        <button onClick={zoomIn} title="放大">+</button>
        <button onClick={resetZoom} title="重置">↺</button>
        <button onClick={onClose} title="关闭">✕</button>
      </div>
      <img
        className="file-viewer-image"
        src={imageUrl}
        alt="Preview"
        style={{ transform: `translate(${position.x}px, ${position.y}px) scale(${scale})` }}
        onWheel={handleWheel}
        draggable={false}
      />
    </div>
  );
};

export default FileViewerModal;
