import client from './client';

export const getAllSkills = (params) => client.get('/skills', { params });

export const getSkill = (name) => client.get(`/skills/${name}`);

export const createSkill = (data) => client.post('/skills', data);

export const updateSkill = (name, data) => client.put(`/skills/${name}`, data);

export const deleteSkill = (name) => client.delete(`/skills/${name}`);

export const toggleSkill = (name, enabled) =>
  client.post(`/skills/${name}/toggle`, null, { params: { enabled } });

export const reloadSkills = () => client.post('/skills/reload');

export const getCategories = () => client.get('/skills/categories');
