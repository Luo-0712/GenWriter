import client from './client';

export const uploadAttachment = async (file, sessionId, onProgress) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('sessionId', sessionId);

  return client.post('/attachments/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (event) => {
      if (!onProgress || !event.total) return;
      onProgress(Math.round((event.loaded * 100) / event.total));
    },
  });
};

export const deleteAttachment = (id, sessionId) =>
  client.delete(`/attachments/${id}`, { params: { sessionId } });

export const getAttachment = (id) => client.get(`/attachments/${id}`);

export const getAttachmentUrl = (id) => `/api/attachments/${id}/file`;

export const getThumbnailUrl = (id) => `/api/attachments/${id}/thumbnail`;
