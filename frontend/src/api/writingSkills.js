import client from './client';

export const learnFromArticle = (data) => client.post('/writing-skills/learn', data);
