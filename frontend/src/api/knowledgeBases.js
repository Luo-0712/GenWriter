import client from './client';

export const getAllKnowledgeBases = () => client.get('/knowledge-bases');

export const getKnowledgeBaseById = (id) => client.get(`/knowledge-bases/${id}`);

export const createKnowledgeBase = (data) => client.post('/knowledge-bases', data);

export const updateKnowledgeBase = (id, data) => client.put(`/knowledge-bases/${id}`, data);

export const deleteKnowledgeBase = (id) => client.delete(`/knowledge-bases/${id}`);

export const searchKnowledgeBases = (name) =>
  client.get('/knowledge-bases/search', { params: { name } });
