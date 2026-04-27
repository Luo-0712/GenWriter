import client from './client';

export const getAllTemplates = () => client.get('/templates');

export const getSystemTemplates = () => client.get('/templates/system');
