-- ========================================================
-- GenWriter Agent写作系统数据库脚本
-- PostgreSQL with pgvector扩展
-- ========================================================

-- ========================================================
-- 1. 启用必要的扩展
-- ========================================================

-- 启用UUID扩展
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 启用向量扩展(用于语义搜索)
CREATE EXTENSION IF NOT EXISTS vector;

-- ========================================================
-- 2. 创建项目表
-- ========================================================
CREATE TABLE IF NOT EXISTS project (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 项目表注释
COMMENT ON TABLE project IS '项目表,存储写作项目,每个项目下可包含多个任务会话';
COMMENT ON COLUMN project.id IS '项目唯一标识符(UUID)';
COMMENT ON COLUMN project.name IS '项目名称';
COMMENT ON COLUMN project.description IS '项目描述';
COMMENT ON COLUMN project.status IS '项目状态: active(活跃), archived(归档)';
COMMENT ON COLUMN project.metadata IS '元数据(JSON格式),存储额外配置';

-- 项目表索引
CREATE INDEX IF NOT EXISTS idx_project_status ON project(status);
CREATE INDEX IF NOT EXISTS idx_project_updated_at ON project(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_project_name ON project(name);

-- ========================================================
-- 3. 创建任务会话表
-- ========================================================
CREATE TABLE IF NOT EXISTS task_session (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    project_id UUID,
    title VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'writing',
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    topic TEXT,
    style VARCHAR(50),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_session_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE SET NULL
);

-- 任务会话表注释
COMMENT ON TABLE task_session IS '任务会话表,存储写作任务的生命周期和上下文';
COMMENT ON COLUMN task_session.id IS '会话唯一标识符(UUID)';
COMMENT ON COLUMN task_session.project_id IS '所属项目ID,外键关联project表';
COMMENT ON COLUMN task_session.title IS '会话标题';
COMMENT ON COLUMN task_session.type IS '会话类型: writing(写作), editing(编辑), brainstorming(头脑风暴)';
COMMENT ON COLUMN task_session.status IS '会话状态: active(活跃), paused(暂停), completed(完成), archived(归档)';
COMMENT ON COLUMN task_session.topic IS '写作主题/目标描述';
COMMENT ON COLUMN task_session.style IS '写作风格: formal(正式), casual(随意), academic(学术), creative(创意)';
COMMENT ON COLUMN task_session.metadata IS '元数据(JSON格式),存储额外配置如字数限制、语言偏好等';

-- 任务会话表索引
CREATE INDEX IF NOT EXISTS idx_task_session_project_id ON task_session(project_id);
CREATE INDEX IF NOT EXISTS idx_task_session_status ON task_session(status);
CREATE INDEX IF NOT EXISTS idx_task_session_type ON task_session(type);
CREATE INDEX IF NOT EXISTS idx_task_session_updated_at ON task_session(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_task_session_status_updated ON task_session(status, updated_at DESC);

-- ========================================================
-- 4. 创建消息表
-- ========================================================
CREATE TABLE IF NOT EXISTS message (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL,
    role VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'text',
    content TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    parent_id UUID,
    sequence INTEGER DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES task_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_message_parent FOREIGN KEY (parent_id) REFERENCES message(id) ON DELETE SET NULL
);

-- 消息表注释
COMMENT ON TABLE message IS '消息表,存储任务会话中的对话消息';
COMMENT ON COLUMN message.id IS '消息唯一标识符(UUID)';
COMMENT ON COLUMN message.session_id IS '所属会话ID,外键关联task_session表';
COMMENT ON COLUMN message.role IS '消息角色: user(用户), assistant(AI助手), system(系统), tool(工具)';
COMMENT ON COLUMN message.type IS '消息类型: text(文本), image(图片), file(文件), thinking(思考过程)';
COMMENT ON COLUMN message.content IS '消息内容';
COMMENT ON COLUMN message.metadata IS '消息元数据(JSON格式),存储额外信息如token数、模型名称等';
COMMENT ON COLUMN message.parent_id IS '父消息ID,用于构建消息链';
COMMENT ON COLUMN message.sequence IS '消息序号,用于排序';

-- 消息表索引
CREATE INDEX IF NOT EXISTS idx_message_session_id ON message(session_id);
CREATE INDEX IF NOT EXISTS idx_message_session_sequence ON message(session_id, sequence);
CREATE INDEX IF NOT EXISTS idx_message_role ON message(role);
CREATE INDEX IF NOT EXISTS idx_message_created_at ON message(created_at);
CREATE INDEX IF NOT EXISTS idx_message_session_role ON message(session_id, role);

-- ========================================================
-- 5. 创建文档表
-- ========================================================
CREATE TABLE IF NOT EXISTS document (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    session_id UUID NOT NULL,
    title VARCHAR(500) NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'draft',
    content TEXT,
    format VARCHAR(50) DEFAULT 'markdown',
    version INTEGER DEFAULT 1,
    status VARCHAR(50) DEFAULT 'editing',
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_session FOREIGN KEY (session_id) REFERENCES task_session(id) ON DELETE CASCADE
);

-- 文档表注释
COMMENT ON TABLE document IS '文档表,存储生成的写作成果和草稿';
COMMENT ON COLUMN document.id IS '文档唯一标识符(UUID)';
COMMENT ON COLUMN document.session_id IS '所属会话ID,外键关联task_session表';
COMMENT ON COLUMN document.title IS '文档标题';
COMMENT ON COLUMN document.type IS '文档类型: draft(草稿), final(终稿), template(模板)';
COMMENT ON COLUMN document.content IS '文档内容';
COMMENT ON COLUMN document.format IS '文档格式: markdown, html, plain, json';
COMMENT ON COLUMN document.version IS '文档版本号';
COMMENT ON COLUMN document.status IS '文档状态: editing(编辑中), reviewing(审核中), published(已发布)';
COMMENT ON COLUMN document.metadata IS '元数据(JSON格式),存储字数、标签、分类等';

-- 文档表索引
CREATE INDEX IF NOT EXISTS idx_document_session_id ON document(session_id);
CREATE INDEX IF NOT EXISTS idx_document_type ON document(type);
CREATE INDEX IF NOT EXISTS idx_document_status ON document(status);
CREATE INDEX IF NOT EXISTS idx_document_version ON document(session_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_document_updated_at ON document(updated_at DESC);

-- ========================================================
-- 6. 创建知识库表
-- ========================================================
CREATE TABLE IF NOT EXISTS knowledge_base (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(50) NOT NULL DEFAULT 'reference',
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 知识库表注释
COMMENT ON TABLE knowledge_base IS '知识库表,存储写作参考材料和知识片段';
COMMENT ON COLUMN knowledge_base.id IS '知识库唯一标识符(UUID)';
COMMENT ON COLUMN knowledge_base.name IS '知识库名称';
COMMENT ON COLUMN knowledge_base.description IS '知识库描述';
COMMENT ON COLUMN knowledge_base.type IS '知识库类型: reference(参考资料), template(模板), style(风格指南)';
COMMENT ON COLUMN knowledge_base.metadata IS '元数据(JSON格式),存储分类、标签、来源等';

-- 知识库表索引
CREATE INDEX IF NOT EXISTS idx_knowledge_base_type ON knowledge_base(type);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_updated_at ON knowledge_base(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_name ON knowledge_base(name);

-- ========================================================
-- 7. 创建知识片段表(向量表)
-- ========================================================
CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    kb_id UUID NOT NULL,
    source_id UUID,
    content TEXT NOT NULL,
    embedding VECTOR(1024),  -- 默认1024维向量,可根据实际模型调整
    embedding_dimension INTEGER DEFAULT 1024,
    embedding_model VARCHAR(100) DEFAULT 'bge-m3',
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_chunk_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base(id) ON DELETE CASCADE
);

-- 知识片段表注释
COMMENT ON TABLE knowledge_chunk IS '知识片段表,存储知识库中的向量化文本片段,支持语义检索';
COMMENT ON COLUMN knowledge_chunk.id IS '片段唯一标识符(UUID)';
COMMENT ON COLUMN knowledge_chunk.kb_id IS '所属知识库ID,外键关联knowledge_base表';
COMMENT ON COLUMN knowledge_chunk.source_id IS '原始文档ID(可选)';
COMMENT ON COLUMN knowledge_chunk.content IS '片段内容';
COMMENT ON COLUMN knowledge_chunk.embedding IS '嵌入向量,用于语义相似度搜索';
COMMENT ON COLUMN knowledge_chunk.embedding_dimension IS '向量维度';
COMMENT ON COLUMN knowledge_chunk.embedding_model IS '嵌入模型类型标识';
COMMENT ON COLUMN knowledge_chunk.metadata IS '元数据(JSON格式),存储来源位置、权重等';

-- 知识片段表索引
CREATE INDEX IF NOT EXISTS idx_chunk_kb_id ON knowledge_chunk(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_source_id ON knowledge_chunk(source_id);
CREATE INDEX IF NOT EXISTS idx_chunk_created_at ON knowledge_chunk(created_at);

-- 向量索引(使用IVFFlat索引类型,适用于高维向量)
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_ivf ON knowledge_chunk 
    USING ivfflat (embedding vector_l2_ops) WITH (lists = 100);

-- 可选: HNSW索引(更高效的近似最近邻搜索,但构建较慢)
-- CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw ON knowledge_chunk 
--     USING hnsw (embedding vector_l2_ops) WITH (m = 16, ef_construction = 64);

-- ========================================================
-- 8. 创建长期记忆表
-- ========================================================
CREATE TABLE IF NOT EXISTS long_term_memory (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content TEXT NOT NULL,
    memory_type VARCHAR(50) NOT NULL,
    scope VARCHAR(20) NOT NULL DEFAULT 'GLOBAL',
    project_id UUID,
    session_id UUID,
    embedding VECTOR(1024),
    embedding_model VARCHAR(50) DEFAULT 'text-embedding-v1',
    importance VARCHAR(10) DEFAULT 'MEDIUM',
    metadata JSONB,
    access_count INTEGER DEFAULT 0,
    last_accessed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE long_term_memory IS '长期记忆表,存储从对话中提取的原子事实,支持向量检索';
COMMENT ON COLUMN long_term_memory.id IS '记忆唯一标识符(UUID)';
COMMENT ON COLUMN long_term_memory.content IS '记忆内容(原子事实)';
COMMENT ON COLUMN long_term_memory.memory_type IS '记忆类型: WRITING_PREFERENCE, CORRECTION_PATTERN, WORLD_SETTING, CHARACTER_PROFILE, FORESHADOWING, DOMAIN_KNOWLEDGE';
COMMENT ON COLUMN long_term_memory.scope IS '作用域: GLOBAL(全局), PROJECT(项目级)';
COMMENT ON COLUMN long_term_memory.project_id IS '所属项目ID(scope=PROJECT时必填)';
COMMENT ON COLUMN long_term_memory.session_id IS '来源会话ID';
COMMENT ON COLUMN long_term_memory.embedding IS '嵌入向量,用于语义相似度搜索';
COMMENT ON COLUMN long_term_memory.embedding_model IS '嵌入模型类型标识';
COMMENT ON COLUMN long_term_memory.importance IS '重要程度: HIGH, MEDIUM, LOW';
COMMENT ON COLUMN long_term_memory.metadata IS '元数据(JSON格式)';
COMMENT ON COLUMN long_term_memory.access_count IS '访问次数统计';
COMMENT ON COLUMN long_term_memory.last_accessed_at IS '最后访问时间';

CREATE INDEX IF NOT EXISTS idx_ltm_memory_type ON long_term_memory(memory_type);
CREATE INDEX IF NOT EXISTS idx_ltm_scope ON long_term_memory(scope);
CREATE INDEX IF NOT EXISTS idx_ltm_scope_project ON long_term_memory(scope, project_id);
CREATE INDEX IF NOT EXISTS idx_ltm_embedding ON long_term_memory USING hnsw (embedding vector_cosine_ops);

CREATE TRIGGER update_long_term_memory_updated_at
    BEFORE UPDATE ON long_term_memory
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ========================================================
-- 9. 创建写作模板表
-- ========================================================
CREATE TABLE IF NOT EXISTS writing_template (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(50) NOT NULL,
    category VARCHAR(100),
    content TEXT NOT NULL,
    variables JSONB DEFAULT '{}',
    example TEXT,
    is_system BOOLEAN DEFAULT FALSE,
    usage_count INTEGER DEFAULT 0,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 写作模板表注释
COMMENT ON TABLE writing_template IS '写作模板表,存储可复用的写作模板和结构';
COMMENT ON COLUMN writing_template.id IS '模板唯一标识符(UUID)';
COMMENT ON COLUMN writing_template.name IS '模板名称';
COMMENT ON COLUMN writing_template.description IS '模板描述';
COMMENT ON COLUMN writing_template.type IS '模板类型: article(文章), report(报告), email(邮件), social(社交媒体)等';
COMMENT ON COLUMN writing_template.category IS '模板分类';
COMMENT ON COLUMN writing_template.content IS '模板内容(包含占位符)';
COMMENT ON COLUMN writing_template.variables IS '模板变量定义(JSON格式),描述占位符和默认值';
COMMENT ON COLUMN writing_template.example IS '示例内容';
COMMENT ON COLUMN writing_template.is_system IS '是否系统模板';
COMMENT ON COLUMN writing_template.usage_count IS '使用次数统计';
COMMENT ON COLUMN writing_template.metadata IS '元数据(JSON格式)';

-- 写作模板表索引
CREATE INDEX IF NOT EXISTS idx_template_type ON writing_template(type);
CREATE INDEX IF NOT EXISTS idx_template_category ON writing_template(category);
CREATE INDEX IF NOT EXISTS idx_template_is_system ON writing_template(is_system);
CREATE INDEX IF NOT EXISTS idx_template_usage_count ON writing_template(usage_count DESC);
CREATE INDEX IF NOT EXISTS idx_template_type_category ON writing_template(type, category);

-- ========================================================
-- 9. 创建更新时间触发器函数
-- ========================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 为所有表创建更新时间触发器
CREATE TRIGGER update_project_updated_at
    BEFORE UPDATE ON project
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_task_session_updated_at
    BEFORE UPDATE ON task_session
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_message_updated_at
    BEFORE UPDATE ON message
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_document_updated_at
    BEFORE UPDATE ON document
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_knowledge_base_updated_at
    BEFORE UPDATE ON knowledge_base
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_knowledge_chunk_updated_at
    BEFORE UPDATE ON knowledge_chunk
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_writing_template_updated_at
    BEFORE UPDATE ON writing_template
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ========================================================
-- 10. 插入默认数据
-- ========================================================

-- 插入默认项目
INSERT INTO project (name, description, status, metadata)
VALUES ('默认项目', '系统默认项目,用于组织您的写作会话', 'active', '{"isDefault": true}'::jsonb)
ON CONFLICT DO NOTHING;

-- 插入默认写作模板
INSERT INTO writing_template (name, description, type, category, content, variables, example, is_system, metadata)
VALUES 
(
    '标准文章模板',
    '适用于一般性文章写作的标准模板',
    'article',
    '通用',
    '# {title}

## 引言
{introduction}

## 正文
{body}

## 结论
{conclusion}',
    '{
        "title": {"type": "string", "default": "文章标题", "description": "文章标题"},
        "introduction": {"type": "text", "default": "", "description": "引言部分"},
        "body": {"type": "text", "default": "", "description": "正文内容"},
        "conclusion": {"type": "text", "default": "", "description": "结论部分"}
    }'::jsonb,
    '# 示例文章

## 引言
这是一篇示例文章的引言部分。

## 正文
这是文章的正文内容...

## 结论
这是文章的结论。',
    TRUE,
    '{"tags": ["通用", "文章"], "difficulty": "easy"}'::jsonb
),
(
    '技术报告模板',
    '适用于技术文档和报告的写作',
    'report',
    '技术',
    '# {title}

## 摘要
{abstract}

## 背景
{background}

## 方法
{methodology}

## 结果
{results}

## 讨论
{discussion}

## 结论
{conclusion}',
    '{
        "title": {"type": "string", "default": "技术报告标题", "description": "报告标题"},
        "abstract": {"type": "text", "default": "", "description": "摘要"},
        "background": {"type": "text", "default": "", "description": "背景介绍"},
        "methodology": {"type": "text", "default": "", "description": "研究方法"},
        "results": {"type": "text", "default": "", "description": "研究结果"},
        "discussion": {"type": "text", "default": "", "description": "讨论"},
        "conclusion": {"type": "text", "default": "", "description": "结论"}
    }'::jsonb,
    NULL,
    TRUE,
    '{"tags": ["技术", "报告"], "difficulty": "medium"}'::jsonb
)
ON CONFLICT DO NOTHING;

-- ========================================================
-- 11. 创建常用查询视图
-- ========================================================

-- 会话统计视图
CREATE OR REPLACE VIEW v_session_stats AS
SELECT
    ts.id,
    ts.project_id,
    ts.title,
    ts.type,
    ts.status,
    ts.created_at,
    ts.updated_at,
    COUNT(DISTINCT m.id) AS message_count,
    COUNT(DISTINCT d.id) AS document_count
FROM task_session ts
LEFT JOIN message m ON ts.id = m.session_id
LEFT JOIN document d ON ts.id = d.session_id
GROUP BY ts.id, ts.project_id, ts.title, ts.type, ts.status, ts.created_at, ts.updated_at;

COMMENT ON VIEW v_session_stats IS '会话统计视图,包含消息和文档数量';

-- 知识库统计视图
CREATE OR REPLACE VIEW v_knowledge_base_stats AS
SELECT 
    kb.id,
    kb.name,
    kb.type,
    kb.created_at,
    kb.updated_at,
    COUNT(kc.id) AS chunk_count
FROM knowledge_base kb
LEFT JOIN knowledge_chunk kc ON kb.id = kc.kb_id
GROUP BY kb.id, kb.name, kb.type, kb.created_at, kb.updated_at;

COMMENT ON VIEW v_knowledge_base_stats IS '知识库统计视图,包含片段数量';

-- ========================================================
-- 完成
-- ========================================================
