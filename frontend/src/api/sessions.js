import client from './client';

export const getAllSessions = () => client.get('/sessions');

export const getSession = (id) => client.get(`/sessions/${id}`);

export const createSession = (data) => client.post('/sessions', data);

export const deleteSession = (id) => client.delete(`/sessions/${id}`);

export const updateSession = (id, data) => client.put(`/sessions/${id}`, data);

export const getSessionsByProjectId = (projectId) => client.get(`/sessions/project/${projectId}`);
