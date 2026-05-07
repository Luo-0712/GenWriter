import client from './client';

export const getMessagesBySessionId = (sessionId) =>
  client.get(`/messages/session/${sessionId}`);

export const createMessage = (data) => client.post('/messages', data);

export const chat = (sessionId, userInput, type = 'CREATE') =>
  client.post(`/messages/${sessionId}/chat?type=${type}`, userInput, {
    headers: { 'Content-Type': 'text/plain' },
  });

export const updateMessage = (id, data) => client.put(`/messages/${id}`, data);

export const deleteMessagesBySessionId = (sessionId) =>
  client.delete(`/messages/session/${sessionId}`);
