-- Applied transactionally once by WorkspaceSchemaInitializer; existing documents stay unassigned.
ALTER TABLE document ADD COLUMN IF NOT EXISTS project_id UUID REFERENCES project(id) ON DELETE CASCADE;
ALTER TABLE document ADD COLUMN IF NOT EXISTS node_id UUID;
UPDATE document d SET project_id = s.project_id,
    metadata = COALESCE(d.metadata, '{}'::jsonb) || jsonb_build_object('sourceSessionId', s.id, 'sourceSessionTitle', s.title)
FROM task_session s WHERE d.session_id = s.id AND d.project_id IS NULL;
ALTER TABLE document ALTER COLUMN session_id DROP NOT NULL;
ALTER TABLE document DROP CONSTRAINT IF EXISTS fk_document_session;
ALTER TABLE document ADD CONSTRAINT fk_document_session FOREIGN KEY (session_id) REFERENCES task_session(id) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS content_node (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    parent_id UUID,
    title VARCHAR(500) NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    is_root BOOLEAN NOT NULL DEFAULT FALSE,
    current_document_id UUID,
    current_version INTEGER NOT NULL DEFAULT 0,
    summary TEXT NOT NULL DEFAULT '',
    summary_document_id UUID,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, project_id),
    FOREIGN KEY (parent_id, project_id) REFERENCES content_node(id, project_id),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_content_node_root ON content_node(project_id) WHERE is_root;
CREATE INDEX IF NOT EXISTS idx_content_node_tree ON content_node(project_id, parent_id, position);
ALTER TABLE document ADD CONSTRAINT fk_document_node FOREIGN KEY (node_id) REFERENCES content_node(id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_document_node_version ON document(node_id, version) WHERE node_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_document_project ON document(project_id);

CREATE TABLE IF NOT EXISTS writing_candidate (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    session_id UUID REFERENCES task_session(id) ON DELETE SET NULL,
    source_session_id UUID,
    target_node_id UUID NOT NULL,
    parent_node_id UUID,
    after_node_id UUID,
    operation VARCHAR(30) NOT NULL,
    base_document_id UUID,
    title VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    instruction TEXT NOT NULL DEFAULT '',
    format VARCHAR(30) NOT NULL DEFAULT 'markdown',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    accepted_document_id UUID,
    accepted_node_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    adopted_at TIMESTAMPTZ,
    FOREIGN KEY (target_node_id, project_id) REFERENCES content_node(id, project_id)
);
CREATE INDEX IF NOT EXISTS idx_writing_candidate_project ON writing_candidate(project_id, created_at DESC);
