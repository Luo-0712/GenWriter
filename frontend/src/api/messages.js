import client from './client';

export const getMessagesBySessionId = (sessionId) =>
  client.get(`/messages/session/${sessionId}`);

export const createMessage = (data) => client.post('/messages', data);

export const chat = (sessionId, userInput, type = 'AUTO', webSearch = true, kbId = '', attachmentIds = []) => {
  const params = new URLSearchParams({ type, webSearch });
  if (kbId) params.append('kbId', kbId);
  return client.post(`/messages/${sessionId}/chat?${params}`, {
    text: userInput,
    attachmentIds,
  });
};

export const updateMessage = (id, data) => client.put(`/messages/${id}`, data);

export const deleteMessagesBySessionId = (sessionId) =>
  client.delete(`/messages/session/${sessionId}`);
