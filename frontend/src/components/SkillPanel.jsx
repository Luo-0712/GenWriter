import { useState, useEffect, useCallback, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import * as skillsApi from '../api/skills';
import '../styles/global.css';

const CATEGORY_MAP = {
  writing: '写作',
  research: '调研',
  workflow: '流程',
  style: '风格',
};

const EMPTY_FORM = {
  name: '',
  displayName: '',
  description: '',
  category: 'writing',
  tags: '',
  version: '1.0.0',
  disableModelInvocation: false,
  userInvocable: true,
  allowedTools: '',
  argumentHint: '',
  descriptionSection: '',
  workflowSection: '',
};

const WORKFLOW_HEADING_PATTERNS = [
  /^## (?:推荐工作流|工作流|工作流程|执行步骤|Workflow|Steps|流程|执行|实施|操作步骤)\s*$/m,
];

const splitContentIntoSections = (body) => {
  if (!body) return { descriptionSection: '', workflowSection: '' };
  for (const pattern of WORKFLOW_HEADING_PATTERNS) {
    const match = body.match(pattern);
    if (match) {
      const idx = match.index;
      return {
        descriptionSection: body.substring(0, idx).trim(),
        workflowSection: body.substring(idx).trim(),
      };
    }
  }
  return { descriptionSection: body, workflowSection: '' };
};

const assembleContent = (descriptionSection, workflowSection) => {
  return [descriptionSection.trim(), workflowSection.trim()].filter(Boolean).join('\n\n');
};

const buildFrontmatterPreview = (formData, tags) => {
  const lines = ['---'];
  lines.push(`name: ${formData.name || '...'}`);
  lines.push(`displayName: ${formData.displayName || formData.name || '...'}`);
  lines.push(`description: ${formData.description || '...'}`);
  lines.push(`category: ${formData.category || 'writing'}`);
  lines.push(`tags: [${tags.join(', ')}]`);
  lines.push(`version: "${formData.version || '1.0.0'}"`);
  if (formData.disableModelInvocation) lines.push(`disable-model-invocation: true`);
  if (!formData.userInvocable) lines.push(`user-invocable: false`);
  if (formData.allowedTools.trim()) lines.push(`allowed-tools: [${formData.allowedTools}]`);
  if (formData.argumentHint.trim()) lines.push(`argument-hint: ${formData.argumentHint}`);
  lines.push('---');
  return lines.join('\n');
};

const MarkdownPreview = ({ content }) => {
  if (!content || !content.trim()) {
    return <div className="skill-preview-empty">Nothing to preview</div>;
  }
  return (
    <div className="skill-md-preview">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  );
};

const GuidanceBar = ({ children }) => (
  <div className="skill-guidance-bar">
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10"></circle>
      <line x1="12" y1="16" x2="12" y2="12"></line>
      <line x1="12" y1="8" x2="12.01" y2="8"></line>
    </svg>
    <span>{children}</span>
  </div>
);

const SkillPanel = ({ onBack }) => {
  const [skills, setSkills] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [keyword, setKeyword] = useState('');
  const [filterCategory, setFilterCategory] = useState('');
  const [filterEnabled, setFilterEnabled] = useState('');
  const [categories, setCategories] = useState([]);
  const [expandedName, setExpandedName] = useState(null);
  const [reloading, setReloading] = useState(false);
  const searchDebounceRef = useRef(null);

  // Modal state
  const [showModal, setShowModal] = useState(false);
  const [editingSkill, setEditingSkill] = useState(null);
  const [formData, setFormData] = useState({ ...EMPTY_FORM });
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState('metadata');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [showFullPreview, setShowFullPreview] = useState(false);

  const fetchSkills = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = {};
      if (filterCategory) params.category = filterCategory;
      const result = await skillsApi.getAllSkills(params);
      let list = result || [];

      if (keyword) {
        const kw = keyword.toLowerCase();
        list = list.filter(
          (s) =>
            (s.name && s.name.toLowerCase().includes(kw)) ||
            (s.displayName && s.displayName.toLowerCase().includes(kw)) ||
            (s.description && s.description.toLowerCase().includes(kw))
        );
      }

      if (filterEnabled === 'true') {
        list = list.filter((s) => s.enabled);
      } else if (filterEnabled === 'false') {
        list = list.filter((s) => !s.enabled);
      }

      setSkills(list);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [keyword, filterCategory, filterEnabled]);

  const fetchCategories = useCallback(async () => {
    try {
      const result = await skillsApi.getCategories();
      setCategories(result || []);
    } catch (e) {
      // ignore
    }
  }, []);

  useEffect(() => {
    fetchCategories();
  }, [fetchCategories]);

  useEffect(() => {
    if (searchDebounceRef.current) {
      clearTimeout(searchDebounceRef.current);
    }
    searchDebounceRef.current = setTimeout(() => {
      fetchSkills();
    }, 300);
    return () => {
      if (searchDebounceRef.current) {
        clearTimeout(searchDebounceRef.current);
      }
    };
  }, [keyword, filterCategory, filterEnabled, fetchSkills]);

  const handleToggle = async (name, currentEnabled) => {
    try {
      await skillsApi.toggleSkill(name, !currentEnabled);
      setSkills((prev) =>
        prev.map((s) => (s.name === name ? { ...s, enabled: !currentEnabled } : s))
      );
    } catch (e) {
      setError(e.message);
    }
  };

  const handleReload = async () => {
    setReloading(true);
    setError(null);
    try {
      await skillsApi.reloadSkills();
      await fetchSkills();
      await fetchCategories();
    } catch (e) {
      setError(e.message);
    } finally {
      setReloading(false);
    }
  };

  const toggleExpand = (name) => {
    setExpandedName((prev) => (prev === name ? null : name));
  };

  // ---- Modal handlers ----

  const handleOpenCreate = () => {
    setEditingSkill(null);
    setFormData({ ...EMPTY_FORM });
    setFormError('');
    setActiveTab('metadata');
    setShowAdvanced(false);
    setShowFullPreview(false);
    setShowModal(true);
  };

  const handleOpenEdit = (skill) => {
    setEditingSkill(skill);
    const { descriptionSection, workflowSection } = splitContentIntoSections(skill.content || '');
    const hasAdvanced = skill.disableModelInvocation || !skill.userInvocable ||
      (skill.allowedTools && skill.allowedTools.length > 0) || skill.argumentHint;
    setFormData({
      name: skill.name,
      displayName: skill.displayName || '',
      description: skill.description || '',
      category: skill.category || 'writing',
      tags: (skill.tags || []).join(', '),
      version: skill.version || '1.0.0',
      disableModelInvocation: skill.disableModelInvocation || false,
      userInvocable: skill.userInvocable !== false,
      allowedTools: (skill.allowedTools || []).join(', '),
      argumentHint: skill.argumentHint || '',
      descriptionSection,
      workflowSection,
    });
    setFormError('');
    setActiveTab('metadata');
    setShowAdvanced(!!hasAdvanced);
    setShowFullPreview(false);
    setShowModal(true);
  };

  const handleCloseModal = () => {
    setShowModal(false);
    setEditingSkill(null);
    setFormError('');
    setShowFullPreview(false);
  };

  const validateForm = () => {
    if (!editingSkill) {
      if (!formData.name.trim()) return '技能名称不能为空';
      if (!/^[a-z][a-z0-9-]{1,98}[a-z0-9]$/.test(formData.name.trim())) {
        return '名称格式：小写字母、数字和连字符，3-100字符，以字母开头';
      }
    }
    if (!formData.description.trim() || formData.description.trim().length < 5) {
      return '描述至少 5 个字符';
    }
    if (formData.description.trim().length > 500) {
      return '描述最多 500 个字符';
    }
    const assembled = assembleContent(formData.descriptionSection, formData.workflowSection);
    if (!assembled.trim() || assembled.trim().length < 20) {
      return '内容至少 20 个字符（描述区 + 流程区）';
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
    setFormError('');
    try {
      const tags = formData.tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
      const allowedToolsList = formData.allowedTools
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
      const assembledContent = assembleContent(formData.descriptionSection, formData.workflowSection);

      const payload = {
        displayName: formData.displayName.trim() || formData.name.trim(),
        description: formData.description.trim(),
        category: formData.category,
        tags,
        version: formData.version.trim(),
        content: assembledContent,
      };
      if (formData.disableModelInvocation) payload.disableModelInvocation = true;
      if (!formData.userInvocable) payload.userInvocable = false;
      if (allowedToolsList.length > 0) payload.allowedTools = allowedToolsList;
      if (formData.argumentHint.trim()) payload.argumentHint = formData.argumentHint.trim();

      if (editingSkill) {
        await skillsApi.updateSkill(editingSkill.name, payload);
      } else {
        await skillsApi.createSkill({ name: formData.name.trim(), ...payload });
      }
      handleCloseModal();
      await fetchSkills();
      await fetchCategories();
    } catch (e) {
      setFormError(e.message || '操作失败');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (skill) => {
    if (!window.confirm(`确定要删除技能「${skill.displayName || skill.name}」吗？此操作不可撤销。`)) {
      return;
    }
    try {
      await skillsApi.deleteSkill(skill.name);
      await fetchSkills();
      await fetchCategories();
    } catch (e) {
      setError(e.message);
    }
  };

  const updateField = (field, value) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  const tags = formData.tags.split(',').map((t) => t.trim()).filter(Boolean);
  const assembledBody = assembleContent(formData.descriptionSection, formData.workflowSection);

  return (
    <div className="skill-panel">
      <div className="skill-panel-header">
        <div className="skill-panel-header-left">
          {onBack && (
            <button className="skill-back-btn" onClick={onBack} title="返回">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="15 18 9 12 15 6"></polyline>
              </svg>
            </button>
          )}
          <h2 className="skill-panel-title">技能管理</h2>
          <span className="skill-panel-count">共 {skills.length} 个</span>
        </div>
        <div className="skill-panel-header-right">
          <button className="skill-new-btn" onClick={handleOpenCreate} title="新建技能">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
            新建技能
          </button>
          <button className="skill-reload-btn" onClick={handleReload} disabled={reloading} title="重新加载">
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              className={reloading ? 'spinning' : ''}
            >
              <polyline points="23 4 23 10 17 10"></polyline>
              <polyline points="1 20 1 14 7 14"></polyline>
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path>
            </svg>
            {reloading ? '加载中...' : '重新加载'}
          </button>
        </div>
      </div>

      <div className="skill-toolbar">
        <div className="skill-search">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="search-icon">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          <input
            type="text"
            placeholder="搜索技能名称或描述..."
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            className="skill-search-input"
          />
        </div>
        <select
          value={filterCategory}
          onChange={(e) => setFilterCategory(e.target.value)}
          className="skill-filter-select"
        >
          <option value="">全部分类</option>
          {categories.map((cat) => (
            <option key={cat} value={cat}>
              {CATEGORY_MAP[cat] || cat}
            </option>
          ))}
        </select>
        <select
          value={filterEnabled}
          onChange={(e) => setFilterEnabled(e.target.value)}
          className="skill-filter-select"
        >
          <option value="">全部状态</option>
          <option value="true">已启用</option>
          <option value="false">已禁用</option>
        </select>
      </div>

      {error && (
        <div className="skill-error" onClick={() => setError(null)}>
          {error}
        </div>
      )}

      {loading && skills.length === 0 ? (
        <div className="skill-loading">
          <div className="loading-spinner"></div>
          <p>加载中...</p>
        </div>
      ) : skills.length === 0 ? (
        <div className="skill-empty">
          <p>暂无技能</p>
          <span>点击「新建技能」创建你的第一个技能，或将 .md 文件放入 skills 目录</span>
        </div>
      ) : (
        <div className="skill-list">
          {skills.map((skill) => (
            <div key={skill.name} className={`skill-card ${!skill.enabled ? 'skill-card-disabled' : ''}`}>
              <div className="skill-card-header">
                <div className="skill-card-info">
                  <div className="skill-card-name">
                    <span className="skill-card-display-name">{skill.displayName || skill.name}</span>
                    {skill.category && (
                      <span className={`skill-badge skill-badge-cat-${skill.category}`}>
                        {CATEGORY_MAP[skill.category] || skill.category}
                      </span>
                    )}
                  </div>
                  <p className="skill-card-desc">{skill.description}</p>
                  {skill.tags && skill.tags.length > 0 && (
                    <div className="skill-card-tags">
                      {skill.tags.map((tag) => (
                        <span key={tag} className="skill-tag">
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <div className="skill-card-actions">
                  <button
                    className={`skill-toggle-btn ${skill.enabled ? 'enabled' : 'disabled'}`}
                    onClick={() => handleToggle(skill.name, skill.enabled)}
                    title={skill.enabled ? '点击禁用' : '点击启用'}
                  >
                    {skill.enabled ? '已启用' : '已禁用'}
                  </button>
                  <button
                    className="skill-action-btn"
                    onClick={() => handleOpenEdit(skill)}
                    title="编辑"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
                    </svg>
                  </button>
                  <button
                    className="skill-action-btn skill-action-btn-danger"
                    onClick={() => handleDelete(skill)}
                    title="删除"
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                      <polyline points="3 6 5 6 21 6"></polyline>
                      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    </svg>
                  </button>
                  <button
                    className="skill-detail-btn"
                    onClick={() => toggleExpand(skill.name)}
                    title={expandedName === skill.name ? '收起详情' : '查看详情'}
                  >
                    <svg
                      width="14"
                      height="14"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      className={expandedName === skill.name ? 'rotated' : ''}
                    >
                      <polyline points="6 9 12 15 18 9"></polyline>
                    </svg>
                    {expandedName === skill.name ? '收起' : '详情'}
                  </button>
                </div>
              </div>
              {expandedName === skill.name && skill.content && (
                <div className="skill-card-detail">
                  <pre className="skill-content">{skill.content}</pre>
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Create / Edit Modal */}
      {showModal && (
        <div className="memory-modal-overlay" onClick={handleCloseModal}>
          <div className="memory-modal skill-modal" onClick={(e) => e.stopPropagation()}>
            <div className="memory-modal-header">
              <h3>{editingSkill ? '编辑技能' : '新建技能'}</h3>
              <button className="memory-modal-close" onClick={handleCloseModal}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
              </button>
            </div>

            {!showFullPreview ? (
              <>
                <div className="skill-form-tabs">
                  <button
                    className={`skill-form-tab ${activeTab === 'metadata' ? 'active' : ''}`}
                    onClick={() => setActiveTab('metadata')}
                  >
                    元数据
                  </button>
                  <button
                    className={`skill-form-tab ${activeTab === 'description' ? 'active' : ''}`}
                    onClick={() => setActiveTab('description')}
                  >
                    描述
                  </button>
                  <button
                    className={`skill-form-tab ${activeTab === 'workflow' ? 'active' : ''}`}
                    onClick={() => setActiveTab('workflow')}
                  >
                    流程
                  </button>
                </div>

                <div className="memory-modal-body skill-tab-content">
                  {formError && <div className="memory-form-error">{formError}</div>}

                  {/* Metadata Tab */}
                  {activeTab === 'metadata' && (
                    <>
                      <div className="memory-form-group">
                        <label>名称 <span className="required">*</span></label>
                        <input
                          type="text"
                          value={formData.name}
                          onChange={(e) => updateField('name', e.target.value)}
                          placeholder="e.g. blog-writing-guide"
                          disabled={!!editingSkill}
                        />
                        {!editingSkill && (
                          <span className="memory-form-hint">小写字母、数字和连字符，3-100 字符，以字母开头</span>
                        )}
                      </div>
                      <div className="memory-form-group">
                        <label>显示名称</label>
                        <input
                          type="text"
                          value={formData.displayName}
                          onChange={(e) => updateField('displayName', e.target.value)}
                          placeholder="e.g. 博客写作指南"
                        />
                      </div>
                      <div className="memory-form-group">
                        <label>描述 <span className="required">*</span></label>
                        <textarea
                          rows={3}
                          value={formData.description}
                          onChange={(e) => updateField('description', e.target.value)}
                          placeholder="e.g. This skill should be used when the user asks to 'write a blog post', 'create blog content'..."
                        />
                        <span className="skill-desc-guidance">
                          使用第三人称格式，如 "This skill should be used when..."，并包含具体的触发短语
                        </span>
                      </div>
                      <div className="memory-form-group" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '12px' }}>
                        <div>
                          <label>分类</label>
                          <select
                            value={formData.category}
                            onChange={(e) => updateField('category', e.target.value)}
                          >
                            {Object.entries(CATEGORY_MAP).map(([k, v]) => (
                              <option key={k} value={k}>{v}</option>
                            ))}
                          </select>
                        </div>
                        <div>
                          <label>标签</label>
                          <input
                            type="text"
                            value={formData.tags}
                            onChange={(e) => updateField('tags', e.target.value)}
                            placeholder="用逗号分隔"
                          />
                        </div>
                        <div>
                          <label>版本</label>
                          <input
                            type="text"
                            value={formData.version}
                            onChange={(e) => updateField('version', e.target.value)}
                            placeholder="1.0.0"
                          />
                        </div>
                      </div>

                      {/* Advanced section */}
                      <button
                        className={`skill-advanced-toggle ${showAdvanced ? 'expanded' : ''}`}
                        onClick={() => setShowAdvanced(!showAdvanced)}
                      >
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                          <polyline points="9 18 15 12 9 6"></polyline>
                        </svg>
                        高级选项
                      </button>
                      {showAdvanced && (
                        <div className="skill-advanced-fields">
                          <div className="memory-form-group">
                            <div className="skill-checkbox-row">
                              <input
                                type="checkbox"
                                id="userInvocable"
                                checked={formData.userInvocable}
                                onChange={(e) => updateField('userInvocable', e.target.checked)}
                              />
                              <label htmlFor="userInvocable">用户可调用（通过斜杠命令）</label>
                            </div>
                          </div>
                          <div className="memory-form-group">
                            <div className="skill-checkbox-row">
                              <input
                                type="checkbox"
                                id="disableModelInvocation"
                                checked={formData.disableModelInvocation}
                                onChange={(e) => updateField('disableModelInvocation', e.target.checked)}
                              />
                              <label htmlFor="disableModelInvocation">禁止模型自动调用（仅用户手动触发）</label>
                            </div>
                          </div>
                          <div className="memory-form-group">
                            <label>允许的工具</label>
                            <input
                              type="text"
                              value={formData.allowedTools}
                              onChange={(e) => updateField('allowedTools', e.target.value)}
                              placeholder="e.g. Read, Write, Bash（逗号分隔）"
                            />
                            <span className="memory-form-hint">留空则继承当前会话的工具权限</span>
                          </div>
                          <div className="memory-form-group">
                            <label>参数提示</label>
                            <input
                              type="text"
                              value={formData.argumentHint}
                              onChange={(e) => updateField('argumentHint', e.target.value)}
                              placeholder="e.g. &lt;query&gt; [options]"
                            />
                            <span className="memory-form-hint">用于 /help 显示的参数说明</span>
                          </div>
                        </div>
                      )}
                    </>
                  )}

                  {/* Description Tab */}
                  {activeTab === 'description' && (
                    <>
                      <GuidanceBar>
                        使用祈使句/动词开头的形式编写。解释"为什么"而不仅仅是"做什么"。此区域对应 SKILL.md 正文的概述/介绍部分。
                      </GuidanceBar>
                      <div className="skill-split-pane">
                        <div className="skill-editor-pane">
                          <textarea
                            value={formData.descriptionSection}
                            onChange={(e) => updateField('descriptionSection', e.target.value)}
                            placeholder="# 技能名称&#10;&#10;## 概述&#10;简要说明此技能的用途和适用场景...&#10;&#10;## 适用场景&#10;- 场景 1&#10;- 场景 2"
                          />
                        </div>
                        <div className="skill-preview-pane">
                          <MarkdownPreview content={formData.descriptionSection} />
                        </div>
                      </div>
                    </>
                  )}

                  {/* Workflow Tab */}
                  {activeTab === 'workflow' && (
                    <>
                      <GuidanceBar>
                        使用编号步骤描述执行流程。分阶段组织，引用工具并定义输出格式。此区域对应 SKILL.md 正文的工作流/执行步骤部分。
                      </GuidanceBar>
                      <div className="skill-split-pane">
                        <div className="skill-editor-pane">
                          <textarea
                            value={formData.workflowSection}
                            onChange={(e) => updateField('workflowSection', e.target.value)}
                            placeholder="## 推荐工作流&#10;1. **第一步**：描述第一个步骤&#10;2. **第二步**：描述第二个步骤&#10;3. **第三步**：描述第三个步骤&#10;&#10;## 输出格式&#10;描述期望的输出结构..."
                          />
                        </div>
                        <div className="skill-preview-pane">
                          <MarkdownPreview content={formData.workflowSection} />
                        </div>
                      </div>
                    </>
                  )}
                </div>
              </>
            ) : (
              /* Full Preview Mode */
              <div className="skill-full-preview">
                <pre className="frontmatter-block">{buildFrontmatterPreview(formData, tags)}</pre>
                <div className="skill-md-preview">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{assembledBody}</ReactMarkdown>
                </div>
              </div>
            )}

            <div className="memory-modal-footer">
              <button
                className="memory-btn-secondary"
                onClick={() => setShowFullPreview(!showFullPreview)}
                style={{ marginRight: 'auto' }}
              >
                {showFullPreview ? '返回编辑' : '完整预览'}
              </button>
              <button className="memory-btn-secondary" onClick={handleCloseModal}>
                取消
              </button>
              {!showFullPreview && (
                <button className="memory-btn-primary" onClick={handleSave} disabled={saving}>
                  {saving ? '保存中...' : (editingSkill ? '保存修改' : '创建')}
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default SkillPanel;
