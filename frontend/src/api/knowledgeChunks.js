import client from './client';

export const getChunksByKnowledgeBase = (kbId) =>
  client.get(`/knowledge-chunks/kb/${kbId}`);

export const deleteChunk = (id) => client.delete(`/knowledge-chunks/${id}`);

export const deleteChunksByKnowledgeBase = (kbId) =>
  client.delete(`/knowledge-chunks/kb/${kbId}`);
