import client from './client';

export const getAllKnowledgeBases = () => client.get('/knowledge-bases');

export const getKnowledgeBase = (id) => client.get(`/knowledge-bases/${id}`);
