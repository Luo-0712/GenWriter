import { useState, useEffect, useCallback } from 'react';
import * as llmApi from '../api/llmProviders';
import * as settingsApi from '../api/settings';
import '../styles/global.css';

const PROVIDER_TYPE_LABEL = {
  dashscope: '通义千问',
  deepseek: 'DeepSeek',
  moonshot: 'Kimi',
  zhipu: '智谱GLM',
  openai_compatible: 'OpenAI 兼容',
};

const PROVIDER_TYPE_COLOR = {
  dashscope: '#78716c',
  deepseek: '#78716c',
  moonshot: '#78716c',
  zhipu: '#78716c',
  openai_compatible: '#78716c',
};

const SettingsPanel = () => {
  const [providers, setProviders] = useState([]);
  const [activeModel, setActiveModel] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [switchingProvider, setSwitchingProvider] = useState(null);
  const [selectedModels, setSelectedModels] = useState({});
  const [testingProvider, setTestingProvider] = useState(null);
  const [testResults, setTestResults] = useState({});
  const [outputSettings, setOutputSettings] = useState({ markdownEnabled: true, format: 'markdown' });
  const [savingOutputSettings, setSavingOutputSettings] = useState(false);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [providersData, activeData, outputData] = await Promise.all([
        llmApi.getAllProviders(),
        llmApi.getActiveModel(),
        settingsApi.getWritingOutputSettings(),
      ]);
      setProviders(providersData || []);
      setActiveModel(activeData || {});
      setOutputSettings(outputData || { markdownEnabled: true, format: 'markdown' });

      // 初始化每个供应商的选中模型
      const models = {};
      (providersData || []).forEach((p) => {
        models[p.type] = p.activeModel;
      });
      setSelectedModels(models);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleModelChange = (providerType, modelName) => {
    setSelectedModels((prev) => ({ ...prev, [providerType]: modelName }));
  };

  const handleSwitchModel = async (providerType) => {
    const modelName = selectedModels[providerType];
    if (!modelName) return;
    setSwitchingProvider(providerType);
    try {
      await llmApi.switchModel(providerType, modelName);
      await fetchData();
    } catch (e) {
      setError(e.message);
    } finally {
      setSwitchingProvider(null);
    }
  };

  const handleTestConnection = async (providerType) => {
    setTestingProvider(providerType);
    setTestResults((prev) => ({ ...prev, [providerType]: null }));
    try {
      const result = await llmApi.testConnection(providerType);
      setTestResults((prev) => ({ ...prev, [providerType]: result }));
    } catch (e) {
      setTestResults((prev) => ({
        ...prev,
        [providerType]: { success: false, message: e.message },
      }));
    } finally {
      setTestingProvider(null);
    }
  };

  const handleMarkdownToggle = async () => {
    const nextValue = !outputSettings.markdownEnabled;
    setSavingOutputSettings(true);
    setError(null);
    try {
      const updated = await settingsApi.updateWritingOutputSettings(nextValue);
      setOutputSettings(updated || { markdownEnabled: nextValue, format: nextValue ? 'markdown' : 'plain' });
    } catch (e) {
      setError(e.message);
    } finally {
      setSavingOutputSettings(false);
    }
  };

  if (loading && providers.length === 0) {
    return (
      <div className="settings-panel">
        <div className="settings-loading">加载中...</div>
      </div>
    );
  }

  return (
    <div className="settings-panel">
      <div className="settings-header">
        <div className="settings-header-left">
          <h1 className="settings-title">系统设置</h1>
        </div>
      </div>

      {error && (
        <div className="settings-error">
          <span>{error}</span>
          <button onClick={() => setError(null)}>&times;</button>
        </div>
      )}

      {/* 当前激活模型 */}
      {activeModel && (
        <div className="settings-active-bar">
          <div className="settings-active-indicator" />
          <div className="settings-active-info">
            <span className="settings-active-label">当前模型</span>
            <span className="settings-active-value">
              {activeModel.displayName} / {activeModel.modelName}
            </span>
          </div>
        </div>
      )}

      {/* 供应商列表 */}
      <div className="settings-provider-list">
        <div className="settings-provider-card settings-output-card">
          <div className="settings-provider-header">
            <div className="settings-provider-title-row">
              <span className="settings-provider-badge settings-output-badge">输出</span>
              <h3 className="settings-provider-name">输出格式</h3>
              <span className="settings-active-tag">
                {outputSettings.markdownEnabled ? 'Markdown' : '纯文本'}
              </span>
            </div>
            <div className="settings-provider-meta">
              <span>控制 AI 最终写作结果的格式。</span>
            </div>
          </div>
          <div className="settings-provider-body">
            <div className="settings-toggle-row">
              <div>
                <div className="settings-toggle-title">允许 Markdown 格式</div>
                <div className="settings-toggle-description">
                  {outputSettings.markdownEnabled
                    ? 'AI 可以使用标题、列表和强调等 Markdown 语法。'
                    : 'AI 将只输出纯文本，适合直接复制到无格式编辑器。'}
                </div>
              </div>
              <button
                type="button"
                className={`settings-toggle ${outputSettings.markdownEnabled ? 'active' : ''}`}
                onClick={handleMarkdownToggle}
                disabled={savingOutputSettings}
                aria-pressed={outputSettings.markdownEnabled}
              >
                <span className="settings-toggle-knob" />
              </button>
            </div>
          </div>
        </div>

        {providers.map((provider) => {
          const isActive = provider.isActive;
          const selectedModel = selectedModels[provider.type] || provider.activeModel;
          const testResult = testResults[provider.type];

          return (
            <div
              key={provider.type}
              className={`settings-provider-card ${isActive ? 'active' : ''}`}
            >
              <div className="settings-provider-header">
                <div className="settings-provider-title-row">
                  <span
                    className="settings-provider-badge"
                    style={{
                      background: PROVIDER_TYPE_COLOR[provider.type] || '#6b7280',
                    }}
                  >
                    {PROVIDER_TYPE_LABEL[provider.type] || provider.type}
                  </span>
                  <h3 className="settings-provider-name">{provider.displayName}</h3>
                  {isActive && <span className="settings-active-tag">使用中</span>}
                </div>
                <div className="settings-provider-meta">
                  <span className="settings-api-key">
                    API Key: {provider.apiKey}
                  </span>
                  {provider.baseUrl && (
                    <span className="settings-base-url">
                      URL: {provider.baseUrl}
                    </span>
                  )}
                </div>
              </div>

              <div className="settings-provider-body">
                <div className="settings-model-select-row">
                  <label className="settings-model-label">选择模型</label>
                  <select
                    className="settings-model-select"
                    value={selectedModel}
                    onChange={(e) =>
                      handleModelChange(provider.type, e.target.value)
                    }
                  >
                    {(provider.models || []).map((m) => (
                      <option key={m} value={m}>
                        {m}
                      </option>
                    ))}
                  </select>
                  <button
                    className="settings-switch-btn"
                    disabled={
                      switchingProvider === provider.type ||
                      (isActive && selectedModel === provider.activeModel)
                    }
                    onClick={() => handleSwitchModel(provider.type)}
                  >
                    {switchingProvider === provider.type ? '切换中...' : '切换'}
                  </button>
                </div>

                <div className="settings-provider-actions">
                  <button
                    className="settings-test-btn"
                    disabled={testingProvider === provider.type}
                    onClick={() => handleTestConnection(provider.type)}
                  >
                    {testingProvider === provider.type ? '测试中...' : '测试连接'}
                  </button>
                  {testResult && (
                    <span
                      className={`settings-test-result ${testResult.success ? 'success' : 'fail'}`}
                    >
                      {testResult.success ? '连接成功' : testResult.message}
                    </span>
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <div className="settings-footer">
        <p className="settings-footer-hint">
          供应商配置通过 application.yml 管理；输出格式开关为运行时设置，重启后回到配置默认值。
        </p>
      </div>
    </div>
  );
};

export default SettingsPanel;
