# GenWriter 架构模块图

> AI Agent 写作系统：Spring Boot 3.4 + Spring AI Alibaba（Graph）多智能体后端 + React 18 前端，PostgreSQL 持久化、Redis 记忆缓存。

## 一、总体模块图

```mermaid
flowchart TB
    %% ==================== 前端 ====================
    subgraph FE["前端 React 18 + Vite（frontend/src）"]
        direction TB
        APP["App.jsx<br/>主布局 · SSE 连接 · 执行轨迹缓存"]
        subgraph COMP["components 组件层"]
            CHAT["ChatArea · InputBox · MessageBubble<br/>WelcomeScreen"]
            THINK["ThinkingPanel · ChainNodeItem<br/>思考过程可视化"]
            WB["WritingWorkbench · RichMarkdownEditor<br/>VersionNavigator · ExportDialog"]
            PANELS["MemoryPanel · KnowledgeBasePanel<br/>SkillPanel · SettingsPanel"]
        end
        API["api/* axios 封装<br/>projects · sessions · messages<br/>knowledge · memory · skills · export · sse"]
    end

    %% ==================== 后端 ====================
    subgraph BE["后端 Spring Boot（com.example.genwriter）"]
        direction TB

        subgraph CTRL["controller — REST 接口层"]
            C1["项目/文档/消息/会话<br/>Project · Document · Message<br/>TaskSession · FileUpload"]
            C2["知识库/RAG<br/>KnowledgeBase · KnowledgeChunk<br/>RAGPipeline · DocumentChunking"]
            C3["模型配置/记忆<br/>LLMProvider · Memory<br/>LongTermMemory · Settings · Prompt"]
            C4["写作能力<br/>Skill · WritingTemplate<br/>WritingSkillLearning"]
            C5["SSE 推送 + debug 调试"]
        end

        subgraph SVC["service / impl — 业务服务层"]
            S1["项目/文档/消息/会话管理<br/>导出 DocumentExport"]
            S2["RAGPipeline · 知识库<br/>文档分块 Chunking"]
            S3["长期记忆 LongTermMemory<br/>记忆抽取 Memory/SettingExtraction"]
            S4["技能学习/抽取 · 写作模板"]
            SSE["SseService<br/>SSE 事件推送"]
        end

        subgraph AGENT["agent — 多智能体核心"]
            direction TB
            GRAPH["graph 图编排<br/>SupervisorGraphConfig（StateGraph）<br/>START → SupervisorNode → SsePublishNode → END"]
            SUP["supervisor 监督者<br/>ExecutionPlan · SupervisorDecision<br/>WorkerRegistry 任务分发"]
            subgraph WK["supervisor.worker 工人智能体 ×8"]
                W1["IntentRecognition<br/>意图识别"]
                W2["OutlineGeneration<br/>大纲生成"]
                W3["DraftGeneration<br/>初稿生成"]
                W4["Polish<br/>润色"]
                W5["Researcher<br/>资料研究"]
                W6["Review<br/>审阅"]
                W7["DirectAnswer<br/>直接问答"]
                W8["WritingSkillExtractor<br/>技能提取"]
            end
            CC["chatclient<br/>ChatClientFactory / Registry"]
            PROFILE["profile<br/>AgentProfileLoader 角色提示词"]
            MEM["memory<br/>RedisChatMemory 会话记忆<br/>LongTermMemoryAdvisor 长期记忆顾问"]
            STREAM["streaming 流式输出<br/>ReasoningStreamingClient<br/>ContentStreamPublisher"]
            SKILL["skill<br/>SkillLoader · WritingGenreResolver"]
            TOOL["tool<br/>KnowledgeBaseTool（工具调用）"]
        end

        subgraph INFRA["基础设施"]
            MAPPER["mapper — MyBatis-Plus 数据访问"]
            SSEPKG["sse — SSE 基础设施"]
            MISC["config · aspect · annotation<br/>exception · util · typehandler"]
        end
    end

    %% ==================== 外部存储 ====================
    DB[("PostgreSQL")]
    REDIS[("Redis")]
    LLM["LLM 服务<br/>DashScope / OpenAI 兼容"]

    %% ---------- 前端 ↔ 后端 ----------
    APP --> COMP
    APP --> API
    API -->|"HTTP REST"| CTRL
    C5 -.->|"SSE 事件流<br/>chain/thinking/content"| APP

    %% ---------- controller → service ----------
    C1 --> S1
    C2 --> S2
    C3 --> S3
    C4 --> S4
    C5 --> SSE

    %% ---------- service → 存储 ----------
    SVC --> MAPPER
    MAPPER --> DB
    S3 --> REDIS
    S1 -.-> SSE

    %% ---------- agent 内部协作 ----------
    GRAPH --> SUP
    SUP --> WK
    WK --> CC
    CC --> LLM
    WK --> MEM
    MEM --> REDIS
    W5 --> TOOL
    TOOL --> S2
    WK --> PROFILE
    SKILL --> PROFILE
    WK --> STREAM
    STREAM --> SSEPKG
```

## 二、Agent 执行流程图

```mermaid
flowchart LR
    U["用户输入"] --> SN["SupervisorNode<br/>ReAct 循环决策"]

    SN -->|"意图识别"| WI["IntentRecognitionWorker"]
    SN -->|"需要资料"| WR["ResearcherWorker<br/>KnowledgeBaseTool → RAG"]
    SN -->|"写大纲"| WO["OutlineGenerationWorker"]
    SN -->|"写初稿"| WD["DraftGenerationWorker"]
    SN -->|"润色"| WP["PolishWorker"]
    SN -->|"审阅"| WV["ReviewWorker"]
    SN -->|"简单提问"| WA["DirectAnswerWorker"]
    SN -->|"学习技能"| WS["WritingSkillExtractorWorker"]

    WD -.->|"reviewCount 未达标<br/>回炉重写"| WD
    WV -.->|"反馈 feedback"| WD

    SN --> SP["SsePublishNode"]
    SP -->|"chain / thinking / content 事件"| FE["前端 SSE 渲染"]
```

## 三、模块职责速览

| 模块 | 职责 |
|---|---|
| `frontend/` | React 单页应用：聊天界面、思考链可视化、写作工作台、知识库/记忆/技能管理面板 |
| `controller` | REST 接口层，约 19 个 Controller，按领域分组 |
| `service(.impl)` | 业务逻辑：项目管理、知识库 RAG、长期记忆、技能学习、文档导出、SSE 推送 |
| `mapper` | MyBatis-Plus 数据访问（PostgreSQL） |
| `agent.graph` | 基于 spring-ai-alibaba-graph 的 StateGraph 编排：`START → supervisor → sse_publish → END` |
| `agent.supervisor` | 监督者模式：ExecutionPlan 执行计划 + WorkerRegistry 分发 8 类工人智能体 |
| `agent.chatclient` | ChatClient 工厂/注册表，统一管理 LLM 客户端 |
| `agent.profile` | 角色画像加载与提示词渲染（definitions/ 下各角色定义） |
| `agent.memory` | RedisChatMemory 会话记忆 + LongTermMemoryAdvisor 长期记忆注入 |
| `agent.streaming` | 推理过程/内容流式发布（ReasoningStreamingClient 等） |
| `agent.skill` | 写作技能 Markdown 加载校验、文体 Genre 解析 |
| `agent.tool` | Function Calling 工具（如 KnowledgeBaseTool 检索知识库） |
| `sse` / `message` | SSE 事件基础设施与消息事件定义 |
| `config` / `aspect` / `util` 等 | Spring 配置、AOP 切面、通用工具 |

## 四、关键技术栈

- **后端**：Spring Boot 3.4.4 · Spring AI 1.0.0-M6 · Spring AI Alibaba（Graph 图编排）· MyBatis-Plus · PostgreSQL · Redis · LangChain4j · Apache POI / PDFBox / Tika
- **前端**：React 18 · Vite · Axios · react-markdown
