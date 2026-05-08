import client from './client';

export const getAllProviders = () => client.get('/llm/providers');

export const getActiveModel = () => client.get('/llm/active-model');

export const switchModel = (providerType, modelName) =>
  client.post('/llm/switch-model', { providerType, modelName });

export const testConnection = (providerType) =>
  client.post(`/llm/providers/${providerType}/test`);
