import client from './client';

export const getAllSkills = (params) => client.get('/skills', { params });

export const getSkill = (name) => client.get(`/skills/${name}`);

export const toggleSkill = (name, enabled) =>
  client.post(`/skills/${name}/toggle`, null, { params: { enabled } });

export const reloadSkills = () => client.post('/skills/reload');

export const getCategories = () => client.get('/skills/categories');
