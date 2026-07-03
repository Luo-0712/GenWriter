import MarkdownIt from 'markdown-it';
import taskLists from 'markdown-it-task-lists';
import TurndownService from 'turndown';
import { gfm } from 'turndown-plugin-gfm';

const markdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
}).use(taskLists, { enabled: true, label: true });

const turndown = new TurndownService({
  headingStyle: 'atx',
  codeBlockStyle: 'fenced',
  bulletListMarker: '-',
  emDelimiter: '*',
});

turndown.use(gfm);

turndown.addRule('taskListItems', {
  filter: (node) => node.nodeName === 'LI' && node.classList?.contains('task-list-item'),
  replacement: (content, node) => {
    const checkbox = node.querySelector('input[type="checkbox"]');
    const checked = checkbox?.checked || checkbox?.hasAttribute('checked');
    const text = content.replace(/^\s+|\s+$/g, '').replace(/^\[[ xX]\]\s*/, '');
    return `- [${checked ? 'x' : ' '}] ${text}\n`;
  },
});

export const markdownToHtml = (markdown = '') => {
  const source = typeof markdown === 'string' ? markdown : '';
  return source.trim() ? markdownIt.render(source) : '<p></p>';
};

export const htmlToMarkdown = (html = '') => {
  const source = typeof html === 'string' ? html : '';
  return turndown.turndown(source).replace(/\n{3,}/g, '\n\n').trim();
};

export const normalizeTextForFingerprint = (text = '') =>
  String(text)
    .replace(/\r\n?/g, '\n')
    .replace(new RegExp(String.fromCharCode(160), 'g'), ' ')
    .replace(/[ \t\f\v]+/g, ' ')
    .replace(/\n{2,}/g, '\n')
    .trim();

const stableHash = (value) => {
  let hash = 2166136261;
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(36);
};

export const createSelectionFingerprint = ({
  beforeText = '',
  selectedText = '',
  afterText = '',
  documentVersion = '',
  fullTextLength = 0,
}) => {
  const parts = [
    normalizeTextForFingerprint(beforeText).slice(-300),
    normalizeTextForFingerprint(selectedText),
    normalizeTextForFingerprint(afterText).slice(0, 300),
    String(documentVersion ?? ''),
    String(fullTextLength ?? 0),
  ];
  return stableHash(parts.join('\n---selection-boundary---\n'));
};
