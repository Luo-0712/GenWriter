/* eslint-disable react-hooks/set-state-in-effect */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import { DOMSerializer } from '@tiptap/pm/model';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import Link from '@tiptap/extension-link';
import { Table } from '@tiptap/extension-table';
import TableRow from '@tiptap/extension-table-row';
import TableCell from '@tiptap/extension-table-cell';
import TableHeader from '@tiptap/extension-table-header';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import {
  Bold,
  Code,
  Eye,
  EyeOff,
  Heading1,
  Heading2,
  Italic,
  List,
  ListOrdered,
  Quote,
  Redo2,
  Table2,
  Undo2,
} from 'lucide-react';
import { htmlToMarkdown, markdownToHtml } from '../utils/markdownEditor';

const contextWindow = 1500;

const ToolbarButton = ({ active = false, disabled = false, title, onClick, children }) => (
  <button
    type="button"
    className={active ? 'editor-toolbar-button active' : 'editor-toolbar-button'}
    title={title}
    aria-label={title}
    disabled={disabled}
    onMouseDown={(event) => event.preventDefault()}
    onClick={onClick}
  >
    {children}
  </button>
);

const getSelectionMarkdown = (editor) => {
  const { from, to } = editor.state.selection;
  const slice = editor.state.doc.slice(from, to);
  const container = document.createElement('div');
  const fragment = DOMSerializer.fromSchema(editor.schema).serializeFragment(slice.content);
  container.appendChild(fragment);
  return htmlToMarkdown(container.innerHTML);
};

const buildSelectionSnapshot = (editor) => {
  const { from, to, empty } = editor.state.selection;
  if (empty) return null;

  const selectedText = editor.state.doc.textBetween(from, to, '\n').trim();
  if (!selectedText) return null;

  const fullText = editor.state.doc.textBetween(0, editor.state.doc.content.size, '\n');
  const beforeText = editor.state.doc.textBetween(Math.max(0, from - contextWindow), from, '\n');
  const afterText = editor.state.doc.textBetween(to, Math.min(editor.state.doc.content.size, to + contextWindow), '\n');

  return {
    from,
    to,
    selectedText,
    selectedMarkdown: getSelectionMarkdown(editor) || selectedText,
    beforeText,
    afterText,
    fullTextLength: fullText.length,
  };
};

const RichMarkdownEditor = ({
  value,
  disabled = false,
  placeholder = '在这里编辑当前文稿...',
  onChange,
  onSelectionSnapshot,
  onEditorReady,
}) => {
  const [sourceMode, setSourceMode] = useState(false);
  const [sourceValue, setSourceValue] = useState(value || '');
  const appliedValueRef = useRef(value || '');
  const onChangeRef = useRef(onChange);
  const onSelectionSnapshotRef = useRef(onSelectionSnapshot);

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    onSelectionSnapshotRef.current = onSelectionSnapshot;
  }, [onSelectionSnapshot]);

  const extensions = useMemo(() => [
    StarterKit.configure({
      heading: { levels: [1, 2, 3] },
      link: false,
    }),
    Link.configure({
      autolink: true,
      openOnClick: false,
      linkOnPaste: true,
      defaultProtocol: 'https',
    }),
    Placeholder.configure({ placeholder }),
    Table.configure({ resizable: true }),
    TableRow,
    TableHeader,
    TableCell,
    TaskList,
    TaskItem.configure({ nested: true }),
  ], [placeholder]);

  const editor = useEditor({
    extensions,
    content: markdownToHtml(value || ''),
    editable: !disabled,
    editorProps: {
      attributes: {
        class: 'editor-prose',
      },
    },
    onUpdate: ({ editor: currentEditor }) => {
      const nextMarkdown = htmlToMarkdown(currentEditor.getHTML());
      appliedValueRef.current = nextMarkdown;
      setSourceValue(nextMarkdown);
      onChangeRef.current?.(nextMarkdown);
    },
    onSelectionUpdate: ({ editor: currentEditor }) => {
      onSelectionSnapshotRef.current?.(buildSelectionSnapshot(currentEditor));
    },
  }, [extensions]);

  useEffect(() => {
    if (!editor) return;
    editor.setEditable(!disabled);
  }, [disabled, editor]);

  useEffect(() => {
    if (!editor) return;
    onEditorReady?.(editor);
  }, [editor, onEditorReady]);

  useEffect(() => {
    const nextValue = value || '';
    setSourceValue(nextValue);
    if (!editor || nextValue === appliedValueRef.current) return;
    appliedValueRef.current = nextValue;
    editor.commands.setContent(markdownToHtml(nextValue), { emitUpdate: false });
    onSelectionSnapshotRef.current?.(null);
  }, [editor, value]);

  const runCommand = useCallback((callback) => {
    if (!editor || disabled) return;
    callback(editor.chain().focus()).run();
  }, [disabled, editor]);

  const setHeading = useCallback((level) => {
    runCommand((chain) => chain.toggleHeading({ level }));
  }, [runCommand]);

  const setParagraph = useCallback(() => {
    runCommand((chain) => chain.setParagraph());
  }, [runCommand]);

  const setLink = useCallback(() => {
    if (!editor || disabled) return;
    const previousUrl = editor.getAttributes('link').href;
    const url = window.prompt('输入链接地址', previousUrl || 'https://');
    if (url === null) return;
    if (!url.trim()) {
      editor.chain().focus().extendMarkRange('link').unsetLink().run();
      return;
    }
    editor.chain().focus().extendMarkRange('link').setLink({ href: url.trim() }).run();
  }, [disabled, editor]);

  const insertTable = useCallback(() => {
    runCommand((chain) => chain.insertTable({ rows: 3, cols: 3, withHeaderRow: true }));
  }, [runCommand]);

  const handleSourceToggle = () => {
    if (!editor) {
      setSourceMode((current) => !current);
      return;
    }
    if (sourceMode) {
      appliedValueRef.current = sourceValue;
      editor.commands.setContent(markdownToHtml(sourceValue), { emitUpdate: false });
      onChangeRef.current?.(sourceValue);
    } else {
      setSourceValue(htmlToMarkdown(editor.getHTML()));
    }
    setSourceMode((current) => !current);
  };

  const handleSourceChange = (event) => {
    const nextValue = event.target.value;
    setSourceValue(nextValue);
    appliedValueRef.current = nextValue;
    onChangeRef.current?.(nextValue);
  };

  return (
    <div className={disabled ? 'rich-markdown-editor disabled' : 'rich-markdown-editor'}>
      <div className="editor-toolbar" aria-label="富文本工具栏">
        <div className="editor-toolbar-group">
          <ToolbarButton title="撤销" disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.undo())}>
            <Undo2 size={15} />
          </ToolbarButton>
          <ToolbarButton title="重做" disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.redo())}>
            <Redo2 size={15} />
          </ToolbarButton>
        </div>

        <div className="editor-toolbar-group editor-format-group">
          <button type="button" disabled={!editor || disabled} onMouseDown={(event) => event.preventDefault()} onClick={setParagraph}>正文</button>
          <ToolbarButton title="一级标题" active={editor?.isActive('heading', { level: 1 })} disabled={!editor || disabled} onClick={() => setHeading(1)}>
            <Heading1 size={15} />
          </ToolbarButton>
          <ToolbarButton title="二级标题" active={editor?.isActive('heading', { level: 2 })} disabled={!editor || disabled} onClick={() => setHeading(2)}>
            <Heading2 size={15} />
          </ToolbarButton>
        </div>

        <div className="editor-toolbar-group">
          <ToolbarButton title="加粗" active={editor?.isActive('bold')} disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.toggleBold())}>
            <Bold size={15} />
          </ToolbarButton>
          <ToolbarButton title="斜体" active={editor?.isActive('italic')} disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.toggleItalic())}>
            <Italic size={15} />
          </ToolbarButton>
          <ToolbarButton title="行内代码" active={editor?.isActive('code')} disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.toggleCode())}>
            <Code size={15} />
          </ToolbarButton>
        </div>

        <div className="editor-toolbar-group">
          <ToolbarButton title="无序列表" active={editor?.isActive('bulletList')} disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.toggleBulletList())}>
            <List size={15} />
          </ToolbarButton>
          <ToolbarButton title="有序列表" active={editor?.isActive('orderedList')} disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.toggleOrderedList())}>
            <ListOrdered size={15} />
          </ToolbarButton>
          <ToolbarButton title="引用" active={editor?.isActive('blockquote')} disabled={!editor || disabled} onClick={() => runCommand((chain) => chain.toggleBlockquote())}>
            <Quote size={15} />
          </ToolbarButton>
          <ToolbarButton title="链接" active={editor?.isActive('link')} disabled={!editor || disabled} onClick={setLink}>
            🔗
          </ToolbarButton>
          <ToolbarButton title="表格" disabled={!editor || disabled} onClick={insertTable}>
            <Table2 size={15} />
          </ToolbarButton>
        </div>

        <button type="button" className="editor-source-toggle" onClick={handleSourceToggle} disabled={disabled && !sourceMode}>
          {sourceMode ? <Eye size={15} /> : <EyeOff size={15} />}
          {sourceMode ? '富文本' : '源码'}
        </button>
      </div>

      {sourceMode ? (
        <textarea
          className="editor-source-area"
          value={sourceValue}
          onChange={handleSourceChange}
          placeholder="Markdown 源码"
          disabled={disabled}
        />
      ) : (
        <EditorContent editor={editor} className="editor-content-shell" />
      )}
    </div>
  );
};

export default RichMarkdownEditor;
