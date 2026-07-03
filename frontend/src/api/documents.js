import client from './client';

export const getDocumentsBySessionId = (sessionId) =>
  client.get(`/documents/session/${sessionId}`);

export const getDocument = (id) => client.get(`/documents/${id}`);

export const createDocument = (data) => client.post('/documents', data);

export const createDocumentVersion = (sessionId, data) =>
  client.post(`/documents/session/${sessionId}/version`, data);

export const updateDocument = (id, data) => client.put(`/documents/${id}`, data);

export const editSuggestion = (id, data) =>
  client.post(`/documents/${id}/edit-suggestion`, data);

export const exportDocument = (data) =>
  client.post('/documents/export', data, { responseType: 'blob' });
