import { useState } from 'react';
import * as writingSkillsApi from '../api/writingSkills';
import '../styles/global.css';

const WritingSkillLearningPanel = ({ projectId, onBack }) => {
  const [articleContent, setArticleContent] = useState('');
  const [description, setDescription] = useState('');
  const [scope, setScope] = useState('GLOBAL');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const handleSubmit = async () => {
    if (!articleContent.trim()) {
      setError('请输入文章内容');
      return;
    }
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const res = await writingSkillsApi.learnFromArticle({
        articleContent: articleContent.trim(),
        description: description.trim() || undefined,
        scope,
        projectId: scope === 'PROJECT' ? projectId : undefined,
      });
      setResult(res);
      if (res.success && res.storedCount > 0) {
        setArticleContent('');
        setDescription('');
      }
    } catch (e) {
      setError(e.message || '学习请求失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="memory-panel">
      <div className="memory-panel-header">
        <div className="memory-panel-header-left">
          <button className="memory-back-btn" onClick={onBack} title="返回">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <line x1="19" y1="12" x2="5" y2="12"></line>
              <polyline points="12 19 5 12 12 5"></polyline>
            </svg>
          </button>
          <div>
            <div className="memory-panel-title">风格学习</div>
            <div className="memory-panel-subtitle">让 AI 从示例文章中提取写作技巧</div>
          </div>
        </div>
      </div>

      <div className="learning-panel-body">
        {error && (
          <div className="learning-error" onClick={() => setError(null)}>
            {error}
          </div>
        )}

        <div className="learning-form">
          <div className="learning-form-group">
            <label>
              示例文章
              <span className="required">*</span>
            </label>
            <textarea
              className="learning-textarea"
              rows={12}
              placeholder="将您希望系统学习的文章粘贴到这里..."
              value={articleContent}
              onChange={(e) => setArticleContent(e.target.value)}
            />
            <div className="learning-hint">
              {articleContent.length} 字
            </div>
          </div>

          <div className="learning-form-group">
            <label>学习说明（可选）</label>
            <input
              className="learning-input"
              type="text"
              placeholder="例如：重点学习叙事节奏和对话技巧"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <div className="learning-form-group">
            <label>适用范围</label>
            <div className="learning-scope-options">
              <button
                className={`learning-scope-btn ${scope === 'GLOBAL' ? 'active' : ''}`}
                onClick={() => setScope('GLOBAL')}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="12" cy="12" r="10"></circle>
                  <line x1="2" y1="12" x2="22" y2="12"></line>
                  <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"></path>
                </svg>
                全局可用
              </button>
              <button
                className={`learning-scope-btn ${scope === 'PROJECT' ? 'active' : ''}`}
                onClick={() => setScope('PROJECT')}
                disabled={!projectId}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                </svg>
                仅限当前项目
                {!projectId && <span className="learning-scope-hint">（需先选择项目）</span>}
              </button>
            </div>
          </div>

          <button
            className="learning-submit-btn"
            onClick={handleSubmit}
            disabled={loading || !articleContent.trim()}
          >
            {loading ? (
              <>
                <span className="learning-spinner"></span>
                正在分析文章...
              </>
            ) : (
              <>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M12 2a10 10 0 1 0 10 10H12V2z"></path>
                  <path d="M12 2a10 10 0 0 1 10 10"></path>
                  <path d="M20.2 7.2A10 10 0 0 0 12 2v10"></path>
                </svg>
                开始学习
              </>
            )}
          </button>
        </div>

        {result && (
          <div className={`learning-result ${result.success ? 'success' : 'fail'}`}>
            <div className="learning-result-header">
              <div className="learning-result-icon">
                {result.success ? (
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <polyline points="20 6 9 17 4 12"></polyline>
                  </svg>
                ) : (
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                    <circle cx="12" cy="12" r="10"></circle>
                    <line x1="12" y1="8" x2="12" y2="12"></line>
                    <line x1="12" y1="16" x2="12.01" y2="16"></line>
                  </svg>
                )}
              </div>
              <div className="learning-result-title">{result.message}</div>
            </div>
            {result.success && result.skills?.length > 0 && (
              <div className="learning-result-skills">
                <div className="learning-result-subtitle">已保存的技巧</div>
                <div className="learning-skill-list">
                  {result.skills.map((skill, idx) => (
                    <div className="learning-skill-item" key={idx}>
                      <span className="learning-skill-name">{skill.skillName}</span>
                      {skill.category && (
                        <span className="learning-skill-category">{skill.category}</span>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default WritingSkillLearningPanel;
