const parseMetadata = (metadata) => {
  if (!metadata) return {};
  if (typeof metadata === 'object') return metadata;
  try {
    return JSON.parse(metadata);
  } catch {
    return {};
  }
};

export const formatVersionTime = (value) => {
  if (!value) return '';
  try {
    return new Date(value).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return '';
  }
};

export const getDocumentSourceLabel = (document) => {
  const metadata = parseMetadata(document?.metadata);
  const labels = [];

  if (metadata.derivedFromVersion) labels.push(`源自 V${metadata.derivedFromVersion}`);
  if (metadata.generatedBy === 'agent') labels.push('Agent');
  else if (metadata.createdBy === 'user') labels.push('手动');
  if (labels.length === 0) labels.push(document?.type || 'draft');

  return labels.join(' · ');
};
