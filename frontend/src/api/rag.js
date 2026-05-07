import client from './client';

export const uploadDocument = (file, kbId, strategy) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('kbId', kbId);
  if (strategy) {
    formData.append('strategy', strategy);
  }
  return client.post('/rag/upload', formData);
};

export const processDocument = (data) => client.post('/rag/process/document', data);

export const processText = (data) => client.post('/rag/process/text', data);

export const searchRAG = (data) => client.post('/rag/search', data);

export const generateWithRAG = (data) => client.post('/rag/generate', data);
