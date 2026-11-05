import client from './client';

const base = (projectId) => `/projects/${projectId}/workspace`;
export const getWorkspace = (projectId) => client.get(base(projectId));
export const createNode = (projectId, data) => client.post(`${base(projectId)}/nodes`, data);
export const renameNode = (projectId, nodeId, title) => client.put(`${base(projectId)}/nodes/${nodeId}`, { title });
export const moveNode = (projectId, nodeId, data) => client.post(`${base(projectId)}/nodes/${nodeId}/move`, data);
export const deleteNode = (projectId, nodeId) => client.delete(`${base(projectId)}/nodes/${nodeId}`);
export const getVersions = (projectId, nodeId) => client.get(`${base(projectId)}/nodes/${nodeId}/versions`);
export const saveSnapshot = (projectId, nodeId, data) => client.post(`${base(projectId)}/nodes/${nodeId}/versions`, data);
export const getCandidates = (projectId) => client.get(`${base(projectId)}/candidates`);
export const proposeSnapshot = (projectId, data) => client.post(`${base(projectId)}/candidates`, data);
export const adoptCandidate = (projectId, candidateId) => client.post(`${base(projectId)}/candidates/${candidateId}/adopt`);
export const getLegacy = (projectId) => client.get(`${base(projectId)}/legacy`);
export const importLegacy = (projectId, data) => client.post(`${base(projectId)}/legacy/import`, data);
export const getReading = (projectId) => client.get(`${base(projectId)}/reading`);
export const exportWork = (projectId, format) => client.get(`${base(projectId)}/export?format=${format}`, { responseType: 'blob' });
