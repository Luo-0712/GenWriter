package com.example.genwriter.service;

import com.example.genwriter.config.WorkspaceSchemaInitializer;
import com.example.genwriter.mapper.DocumentMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.model.dto.workspace.WorkspaceModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL exercises locking, JSONB, migration and foreign-key behavior. */
@EnabledIfEnvironmentVariable(named = "GENWRITER_WORKSPACE_TEST_URL", matches = "jdbc:postgresql:.*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceServicePostgresTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private JdbcTemplate admin;
    private WorkspaceService service;
    private String schema;
    private String projectId;
    private String legacyProject;
    private String legacySession;
    private String legacyDocument;
    private java.time.LocalDateTime legacyUpdatedAt;
    private DataSource source;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @BeforeAll
    void initialize() throws Exception {
        String url = System.getenv("GENWRITER_WORKSPACE_TEST_URL");
        String user = System.getenv().getOrDefault("GENWRITER_WORKSPACE_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("GENWRITER_WORKSPACE_TEST_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "workspace_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public", user, password);
        jdbc = new JdbcTemplate(source);
        try (var input = new ClassPathResource("db/schema.sql").getInputStream()) {
            // Let PostgreSQL parse its dollar-quoted trigger functions without splitting on semicolons.
            jdbc.execute(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        legacyProject = project("历史作品");
        legacySession = session(legacyProject);
        legacyDocument = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO document(id,session_id,title,content,version) VALUES (?,?,'旧标题','旧文稿原文',7)", id(legacyDocument), id(legacySession));
        legacyUpdatedAt = jdbc.queryForObject("SELECT updated_at FROM document WHERE id=?", java.sql.Timestamp.class, id(legacyDocument)).toLocalDateTime();

        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(TransactionTemplate.class, () -> new TransactionTemplate(context.getBean(PlatformTransactionManager.class)));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(MessageService.class, () -> mock(MessageService.class));
        context.registerBean(WorkspaceSchemaInitializer.class);
        context.registerBean(WorkspaceService.class);
        context.refresh();
        service = context.getBean(WorkspaceService.class);
    }

    @BeforeEach
    void newProject() { projectId = project("工作区测试"); }

    @AfterAll
    void cleanUp() {
        if (context != null) context.close();
        if (schema != null && schema.matches("workspace_it_[a-f0-9]{32}")) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void rootCreationIsIdempotentAndEditingDoesNotRequireASession() {
        Workspace first = service.getWorkspace(projectId);
        assertEquals(first.rootId(), service.getWorkspace(projectId).rootId());
        assertEquals(1, first.nodes().size());
        DocumentDTO saved = save(first.rootId(), "独立正文", null);
        assertNull(saved.getSessionId());
        assertEquals(projectId, saved.getProjectId());
        assertEquals(first.rootId(), saved.getNodeId());
    }

    @Test
    void nestedNodesCanMoveAndReorderWithoutChangingSnapshotOwnership() {
        String root = service.getWorkspace(projectId).rootId();
        ContentNode chapter = create(root, "章");
        ContentNode section = create(chapter.id(), "节");
        ContentNode detail = create(section.id(), "小节");
        DocumentDTO original = save(section.id(), "节正文", null);
        BizException cycle = assertThrows(BizException.class, () -> service.moveNode(projectId, chapter.id(), new MoveRequest(detail.id(), 0)));
        assertEquals("400", cycle.getErrorCode());
        service.moveNode(projectId, section.id(), new MoveRequest(root, 0));
        List<ContentNode> tree = service.getWorkspace(projectId).nodes();
        assertEquals(List.of(root, section.id(), detail.id(), chapter.id()), tree.stream().map(ContentNode::id).toList());
        assertEquals(original.getId(), service.versions(projectId, section.id()).get(0).getId());
        service.renameNode(projectId, section.id(), new NodeRequest("新节名", null, null));
        assertEquals("新节名", service.getWorkspace(projectId).nodes().get(1).title());
    }

    @Test
    void crossProjectMovesAndTargetsAreRejected() {
        String root = service.getWorkspace(projectId).rootId();
        ContentNode chapter = create(root, "章");
        String otherProject = project("另一部作品");
        String otherRoot = service.getWorkspace(otherProject).rootId();
        BizException move = assertThrows(BizException.class, () -> service.moveNode(projectId, chapter.id(), new MoveRequest(otherRoot, 0)));
        assertEquals("403", move.getErrorCode());
        BizException target = assertThrows(BizException.class, () -> service.prepareTarget(session(projectId), new WritingTarget(otherProject, otherRoot, "REPLACE", null, null, null), null));
        assertEquals("403", target.getErrorCode());
    }

    @Test
    void snapshotsAreAppendOnlyAndStaleSavesDoNotModifyTheHead() {
        String root = service.getWorkspace(projectId).rootId();
        DocumentDTO one = save(root, "第一版", null);
        DocumentDTO two = save(root, "第二版", one.getId());
        assertEquals(1, one.getVersion());
        assertEquals(2, two.getVersion());
        assertEquals("第一版", service.snapshot(one.getId()).getContent());
        assertEquals("409", assertThrows(BizException.class, () -> save(root, "过期覆盖", one.getId())).getErrorCode());
        assertEquals(two.getId(), service.getWorkspace(projectId).nodes().get(0).currentDocumentId());
        assertEquals(2, service.versions(projectId, root).size());
    }

    @Test
    void anotherSessionInTheSameProjectCanContinueTheSavedText() {
        String root = service.getWorkspace(projectId).rootId();
        String firstSession = session(projectId), secondSession = session(projectId);
        DocumentDTO original = service.saveSnapshot(projectId, root, new SaveRequest("正文", "跨会话正文", null, firstSession));
        WritingTarget frozen = service.prepareTarget(secondSession, new WritingTarget(projectId, root, "APPEND", null, null, original.getId()), null);
        assertEquals(original.getId(), frozen.baseDocumentId());
        assertEquals(root, frozen.nodeId());
    }

    @Test
    void candidatesDoNotChangeTheBodyUntilAdoptionAndAppendIsIdempotent() {
        String root = service.getWorkspace(projectId).rootId();
        DocumentDTO base = save(root, "前文", null);
        Candidate candidate = candidate(root, "APPEND", base.getId(), null, null, "后文");
        assertEquals(1, service.versions(projectId, root).size());
        assertEquals("前文", service.snapshot(base.getId()).getContent());
        Adoption result = service.adopt(projectId, candidate.id());
        assertEquals("前文\n\n后文", result.document().getContent());
        assertEquals(result.document().getId(), service.adopt(projectId, candidate.id()).document().getId());
        assertEquals(2, service.versions(projectId, root).size());
        assertEquals("ADOPTED", service.candidates(projectId).get(0).status());
    }

    @Test
    void newChapterOnlyEntersTheTreeWhenItsCandidateIsAdopted() {
        String root = service.getWorkspace(projectId).rootId();
        Candidate candidate = candidate(root, "CREATE_NODE", null, root, null, "下一章正文");
        assertEquals(1, service.getWorkspace(projectId).nodes().size());
        Adoption result = service.adopt(projectId, candidate.id());
        assertEquals(root, result.node().parentId());
        assertNotEquals(root, result.node().id());
        assertEquals("下一章正文", result.document().getContent());
        assertTrue(service.versions(projectId, root).isEmpty());
        assertEquals(2, service.getWorkspace(projectId).nodes().size());
    }

    @Test
    void outdatedCandidatesRemainAvailableAndNeverOverwriteNewerText() {
        String root = service.getWorkspace(projectId).rootId();
        DocumentDTO base = save(root, "原文", null);
        Candidate candidate = candidate(root, "REPLACE", base.getId(), null, null, "候选内容");
        DocumentDTO newer = save(root, "新的手动修改", base.getId());
        assertEquals("409", assertThrows(BizException.class, () -> service.adopt(projectId, candidate.id())).getErrorCode());
        assertEquals(newer.getId(), service.getWorkspace(projectId).nodes().get(0).currentDocumentId());
        assertEquals("PENDING", service.candidates(projectId).get(0).status());
        assertEquals(2, service.versions(projectId, root).size());
    }

    @Test
    void twoConcurrentAdoptionsCreateExactlyOneSnapshot() throws Exception {
        String root = service.getWorkspace(projectId).rootId();
        Candidate candidate = candidate(root, "REPLACE", null, null, null, "并发采纳正文");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Adoption> left = pool.submit(() -> service.adopt(projectId, candidate.id()));
            Future<Adoption> right = pool.submit(() -> service.adopt(projectId, candidate.id()));
            assertEquals(left.get(10, TimeUnit.SECONDS).document().getId(), right.get(10, TimeUnit.SECONDS).document().getId());
            assertEquals(1, service.versions(projectId, root).size());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void deletingASessionPreservesSnapshotsAndCandidateProvenance() {
        String root = service.getWorkspace(projectId).rootId();
        String source = session(projectId);
        DocumentDTO saved = service.saveSnapshot(projectId, root, new SaveRequest("正文", "永久正文", null, source));
        Candidate candidate = service.createCandidate(source, new WritingTarget(projectId, root, "REPLACE", null, null, saved.getId()), "正文", "候选", "请求", "markdown");
        jdbc.update("DELETE FROM task_session WHERE id=?", id(source));
        assertNull(service.snapshot(saved.getId()).getSessionId());
        assertTrue(service.snapshot(saved.getId()).getMetadata().contains(source));
        Candidate retained = service.candidates(projectId).get(0);
        assertNull(retained.sessionId());
        assertEquals(source, retained.sourceSessionId());
        assertEquals(candidate.id(), retained.id());
    }

    @Test
    void migrationPreservesLegacyVersionsAndImportCopiesRatherThanReassigns() {
        DocumentDTO migrated = service.snapshot(legacyDocument);
        assertEquals(legacyUpdatedAt, migrated.getUpdatedAt());
        assertEquals(legacyProject, migrated.getProjectId());
        assertEquals(7, migrated.getVersion());
        assertTrue(migrated.getMetadata().contains(legacySession));
        assertNull(migrated.getNodeId());
        Adoption imported = service.importLegacy(legacyProject, new ImportRequest(legacyDocument, null));
        assertEquals("旧文稿原文", imported.document().getContent());
        assertEquals(1, imported.document().getVersion());
        assertNull(service.snapshot(legacyDocument).getNodeId());
        assertEquals(7, service.snapshot(legacyDocument).getVersion());
        assertEquals(legacyUpdatedAt, service.snapshot(legacyDocument).getUpdatedAt());
    }

    @Test
    void wholeWorkIncludesParentTextBeforeChildrenAndExcludesCandidatesAndDeletedNodes() {
        String root = service.getWorkspace(projectId).rootId();
        save(root, "根正文", null);
        ContentNode parent = create(root, "第一章");
        save(parent.id(), "章引言", null);
        ContentNode child = create(parent.id(), "第一节");
        save(child.id(), "节正文", null);
        ContentNode removed = create(root, "移除章节");
        save(removed.id(), "不应导出", null);
        service.deleteNode(projectId, removed.id());
        candidate(child.id(), "REPLACE", service.versions(projectId, child.id()).get(0).getId(), null, null, "未采纳内容");
        String whole = service.wholeContent(projectId);
        assertTrue(whole.indexOf("根正文") < whole.indexOf("章引言"));
        assertTrue(whole.indexOf("章引言") < whole.indexOf("节正文"));
        assertFalse(whole.contains("未采纳内容"));
        assertFalse(whole.contains("不应导出"));
        assertFalse(service.writingContext(new WritingTarget(projectId, child.id(), "REPLACE", null, null, null)).contains("未采纳内容"));
    }

    @Test
    void deletingTheProjectCleansUpItsTreeSnapshotsAndCandidatesTogether() {
        String root = service.getWorkspace(projectId).rootId();
        DocumentDTO document = save(root, "完整作品", null);
        candidate(root, "REPLACE", document.getId(), null, null, "候选");
        jdbc.update("DELETE FROM project WHERE id=?", id(projectId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE id=?", Integer.class, id(document.getId())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM content_node WHERE project_id=?", Integer.class, id(projectId)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM writing_candidate WHERE project_id=?", Integer.class, id(projectId)));
    }

    @Test
    void mybatisPreservesNullNodeAndSessionFieldsRatherThanConstructorColumnOrder() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(DocumentMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        try (var sqlSession = factory.getObject().openSession()) {
            DocumentMapper mapper = sqlSession.getMapper(DocumentMapper.class);
            var old = mapper.selectById(legacyDocument);
            assertNull(old.getNodeId());
            assertEquals(legacySession, old.getSessionId());
            assertEquals(legacyProject, old.getProjectId());
            String root = service.getWorkspace(projectId).rootId();
            DocumentDTO saved = save(root, "无来源会话的正文", null);
            var body = mapper.selectById(saved.getId());
            assertNull(body.getSessionId());
            assertEquals(root, body.getNodeId());
            assertEquals(projectId, body.getProjectId());
        }
    }

    private ContentNode create(String parentId, String title) { return service.createNode(projectId, new NodeRequest(title, parentId, null)); }
    private DocumentDTO save(String nodeId, String content, String base) { return service.saveSnapshot(projectId, nodeId, new SaveRequest("正文", content, base, null)); }
    private Candidate candidate(String nodeId, String operation, String base, String parent, String after, String content) {
        return service.createCandidate(session(projectId), new WritingTarget(projectId, nodeId, operation, parent, after, base), "新章节", content, "测试要求", "markdown");
    }
    private String project(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO project(id,name) VALUES (?,?)", id(id), name);
        return id;
    }
    private String session(String projectId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO task_session(id,project_id,title,type) VALUES (?,?,'创作会话','CHAT')", id(id), id(projectId));
        return id;
    }
    private static UUID id(String value) { return UUID.fromString(value); }
}
