import client from './client';

export const getMemories = (params) => client.get('/long-term-memories', { params });

export const getMemory = (id) => client.get(`/long-term-memories/${id}`);

export const createMemory = (data) => client.post('/long-term-memories', data);

export const updateMemory = (id, data) => client.put(`/long-term-memories/${id}`, data);

export const deleteMemory = (id) => client.delete(`/long-term-memories/${id}`);

export const batchDeleteMemories = (ids) => client.delete('/long-term-memories', { data: ids });

export const searchMemories = (params) => client.get('/long-term-memories/search', { params });
