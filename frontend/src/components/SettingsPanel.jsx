import { useState, useEffect, useCallback } from 'react';
import * as llmApi from '../api/llmProviders';
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

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [providersData, activeData] = await Promise.all([
        llmApi.getAllProviders(),
        llmApi.getActiveModel(),
      ]);
      setProviders(providersData || []);
      setActiveModel(activeData || {});

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
          <h1 className="settings-title">模型设置</h1>
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
          供应商配置通过 application.yml 管理，如需添加新供应商请修改配置文件。
        </p>
      </div>
    </div>
  );
};

export default SettingsPanel;
