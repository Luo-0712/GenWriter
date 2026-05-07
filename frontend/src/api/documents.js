import client from './client';

export const getDocumentsBySessionId = (sessionId) =>
  client.get(`/documents/session/${sessionId}`);

export const getDocument = (id) => client.get(`/documents/${id}`);

export const exportDocument = (data) =>
  client.post('/documents/export', data, { responseType: 'blob' });
