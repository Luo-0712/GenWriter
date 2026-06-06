import client from './client';

export const getWritingOutputSettings = () => client.get('/settings/writing-output');

export const updateWritingOutputSettings = (markdownEnabled) =>
  client.put('/settings/writing-output', { markdownEnabled });
