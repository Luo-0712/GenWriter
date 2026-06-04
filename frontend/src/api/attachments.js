import client from './client';

export const uploadAttachment = (file, sessionId, onProgress) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('sessionId', sessionId);
  return client.post('/attachments/upload', formData, {
    headers: { 'Content-Type': undefined },
    onUploadProgress: (e) => {
      if (onProgress && e.total) {
        onProgress(Math.round((e.loaded * 100) / e.total));
      }
    },
  });
};

export const getAttachment = (id) => client.get(`/attachments/${id}`);
export const getAttachmentUrl = (id) => `/api/attachments/${id}/file`;
export const getThumbnailUrl = (id) => `/api/attachments/${id}/thumbnail`;
export const deleteAttachment = (id, sessionId) => client.delete(`/attachments/${id}`, { params: { sessionId } });
export const getSessionAttachments = (sessionId) => client.get(`/attachments/session/${sessionId}`);
