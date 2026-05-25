import { useState, useEffect, useCallback, useRef } from 'react';
import * as skillsApi from '../api/skills';
import '../styles/global.css';

const CATEGORY_MAP = {
  writing: '写作',
  research: '调研',
  workflow: '流程',
  style: '风格',
};

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
          <span>将 .md 格式的 skill 文件放入 skills 目录，或点击重新加载</span>
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
                    {skill.builtIn && <span className="skill-badge skill-badge-built-in">内置</span>}
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
    </div>
  );
};

export default SkillPanel;
