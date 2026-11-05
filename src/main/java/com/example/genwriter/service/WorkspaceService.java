package com.example.genwriter.service;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.model.dto.workspace.WorkspaceModels.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

/** Owns the work tree and its immutable snapshots; sessions only identify provenance. */
@Service
@RequiredArgsConstructor
public class WorkspaceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final MessageService messages;

    @Transactional
    public Candidate proposeSnapshot(String projectId, ProposalRequest request) {
        lockProject(projectId);
        ContentNode target = node(projectId, request.nodeId());
        requireBase(target, request.baseDocumentId());
        String sessionId = existingSourceSession(projectId, request.sourceSessionId());
        Candidate proposed = createCandidate(sessionId, new WritingTarget(projectId, target.id(), "REPLACE", null, null,
                target.currentDocumentId()), request.title(), request.content(), request.instruction(), "markdown");
        if (sessionId != null) {
            messages.createMessage(sessionId, "user", Objects.toString(request.instruction(), "选区修改"));
            messages.createMessage(sessionId, "assistant", request.content());
        }
        return proposed;
    }

    @Transactional
    public Workspace getWorkspace(String projectId) {
        lockProject(projectId);
        ContentNode root = ensureRoot(projectId);
        return new Workspace(projectId, root.id(), orderedNodes(projectId));
    }

    @Transactional
    public ContentNode createNode(String projectId, NodeRequest request) {
        lockProject(projectId);
        ContentNode root = ensureRoot(projectId);
        String parentId = blank(request.parentId()) ? root.id() : request.parentId();
        node(projectId, parentId);
        return insertNode(projectId, parentId, request.title(), request.position());
    }

    @Transactional
    public ContentNode renameNode(String projectId, String nodeId, NodeRequest request) {
        lockProject(projectId);
        node(projectId, nodeId);
        jdbc.update("UPDATE content_node SET title=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", request.title().trim(), uuid(nodeId));
        return node(projectId, nodeId);
    }

    @Transactional
    public ContentNode moveNode(String projectId, String nodeId, MoveRequest request) {
        lockProject(projectId);
        ContentNode moving = node(projectId, nodeId);
        if (moving.root()) throw invalid("正文根节点不能移动");
        ContentNode parent = node(projectId, request.parentId());
        for (ContentNode cursor = parent; cursor != null; cursor = cursor.parentId() == null ? null : node(projectId, cursor.parentId())) {
            if (cursor.id().equals(nodeId)) throw invalid("不能将节点移动到自身或子节点内");
        }
        List<ContentNode> siblings = children(projectId, parent.id()).stream().filter(n -> !n.id().equals(nodeId)).toList();
        int position = position(request.position(), siblings.size());
        jdbc.update("UPDATE content_node SET parent_id=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", uuid(parent.id()), uuid(nodeId));
        List<String> reordered = new ArrayList<>(siblings.stream().map(ContentNode::id).toList());
        reordered.add(position, nodeId);
        setPositions(reordered);
        if (!Objects.equals(moving.parentId(), parent.id())) normalizeChildren(projectId, moving.parentId());
        return node(projectId, nodeId);
    }

    @Transactional
    public void deleteNode(String projectId, String nodeId) {
        lockProject(projectId);
        ContentNode target = node(projectId, nodeId);
        if (target.root()) throw invalid("正文根节点不能删除");
        Set<String> deleting = new HashSet<>();
        deleting.add(nodeId);
        for (ContentNode entry : orderedNodes(projectId)) {
            if (deleting.contains(entry.parentId())) deleting.add(entry.id());
        }
        for (String id : deleting) {
            jdbc.update("UPDATE content_node SET deleted=TRUE, updated_at=CURRENT_TIMESTAMP WHERE id=?", uuid(id));
        }
        normalizeChildren(projectId, target.parentId());
    }

    @Transactional(readOnly = true)
    public List<DocumentDTO> versions(String projectId, String nodeId) {
        node(projectId, nodeId);
        return jdbc.query("SELECT * FROM document WHERE node_id=? ORDER BY version DESC", DOCUMENT, uuid(nodeId));
    }

    @Transactional
    public DocumentDTO saveSnapshot(String projectId, String nodeId, SaveRequest request) {
        lockProject(projectId);
        ContentNode target = node(projectId, nodeId);
        requireBase(target, request.baseDocumentId());
        String sessionId = existingSourceSession(projectId, request.sourceSessionId());
        return writeSnapshot(target, request.title(), request.content(), "markdown", "手动保存", new SnapshotSource(sessionId, null, "user", sessionId));
    }

    @Transactional(readOnly = true)
    public List<DocumentDTO> legacyDocuments(String projectId) {
        requireProject(projectId);
        return jdbc.query("SELECT * FROM document WHERE project_id=? AND node_id IS NULL ORDER BY updated_at DESC, version DESC", DOCUMENT, uuid(projectId));
    }

    @Transactional
    public Adoption importLegacy(String projectId, ImportRequest request) {
        lockProject(projectId);
        DocumentDTO legacy = snapshot(request.documentId());
        if (!Objects.equals(projectId, legacy.getProjectId()) || legacy.getNodeId() != null) throw forbidden();
        ContentNode root = ensureRoot(projectId);
        String parentId = blank(request.parentId()) ? root.id() : request.parentId();
        node(projectId, parentId);
        ContentNode imported = insertNode(projectId, parentId, legacy.getTitle(), null);
        DocumentDTO document = writeSnapshot(imported, legacy.getTitle(), Objects.toString(legacy.getContent(), ""),
                Objects.toString(legacy.getFormat(), "markdown"), "导入历史文稿", new SnapshotSource(legacy.getSessionId(), legacy.getId(), "import", legacyOrigin(legacy)));
        return new Adoption(node(projectId, imported.id()), document, null);
    }

    /** Freeze the target before publishing the asynchronous chat event. */
    @Transactional
    public WritingTarget prepareTarget(String sessionId, WritingTarget requested, String legacyDocumentId) {
        List<String> projects = jdbc.query("SELECT project_id::text FROM task_session WHERE id=?", (rs, n) -> rs.getString(1), uuid(sessionId));
        if (projects.isEmpty()) throw new BizException(BizException.ErrorCode.SESSION_NOT_FOUND);
        String projectId = projects.get(0);
        if (blank(projectId)) {
            if (requested != null && !"DISCUSS".equalsIgnoreCase(requested.operation())) throw invalid("请先将会话放入作品项目");
            if (!blank(legacyDocumentId) && !Objects.equals(snapshot(legacyDocumentId).getSessionId(), sessionId)) throw forbidden();
            return new WritingTarget(null, null, "DISCUSS", null, null, blank(legacyDocumentId) ? null : legacyDocumentId);
        }
        lockProject(projectId);
        ContentNode root = ensureRoot(projectId);
        if (requested == null) {
            String selected = root.id();
            if (!blank(legacyDocumentId)) {
                DocumentDTO document = snapshot(legacyDocumentId);
                if (!Objects.equals(projectId, document.getProjectId())) throw forbidden();
                if (document.getNodeId() != null) selected = document.getNodeId();
            }
            ContentNode current = node(projectId, selected);
            return new WritingTarget(projectId, selected, "DISCUSS", null, null, current.currentDocumentId());
        }
        if (!blank(requested.projectId()) && !projectId.equals(requested.projectId())) throw forbidden();
        String operation = Objects.toString(requested.operation(), "DISCUSS").toUpperCase(Locale.ROOT);
        if (!Set.of("DISCUSS", "REPLACE", "APPEND", "CREATE_NODE").contains(operation)) throw invalid("未知写入操作");
        ContentNode target = node(projectId, blank(requested.nodeId()) ? root.id() : requested.nodeId());
        if (!operation.equals("DISCUSS")) requireBase(target, requested.baseDocumentId());
        String parentId = null;
        String afterId = null;
        if (operation.equals("CREATE_NODE")) {
            parentId = blank(requested.parentId()) ? (target.root() ? root.id() : target.parentId()) : requested.parentId();
            node(projectId, parentId);
            afterId = requested.afterNodeId();
            if (!blank(afterId) && !parentId.equals(node(projectId, afterId).parentId())) throw invalid("新增位置不属于所选父节点");
        }
        return new WritingTarget(projectId, target.id(), operation, parentId, afterId, target.currentDocumentId());
    }

    @Transactional
    public Candidate createCandidate(String sessionId, WritingTarget target, String title, String content, String instruction, String format) {
        lockProject(target.projectId());
        node(target.projectId(), target.nodeId());
        String id = UUID.randomUUID().toString();
        String source = existingSourceSession(target.projectId(), sessionId);
        jdbc.update("""
                INSERT INTO writing_candidate(id,project_id,session_id,source_session_id,target_node_id,parent_node_id,
                    after_node_id,operation,base_document_id,title,content,instruction,format)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, uuid(id), uuid(target.projectId()), uuid(source), uuid(sessionId), uuid(target.nodeId()),
                uuid(target.parentId()), uuid(target.afterNodeId()), target.operation(), uuid(target.baseDocumentId()),
                safeTitle(title), content, Objects.toString(instruction, ""), Objects.toString(format, "markdown"));
        return candidate(target.projectId(), id);
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(String projectId) {
        requireProject(projectId);
        return jdbc.query("SELECT * FROM writing_candidate WHERE project_id=? ORDER BY created_at DESC", CANDIDATE, uuid(projectId));
    }

    @Transactional
    public Adoption adopt(String projectId, String candidateId) {
        // Serializes saves, tree mutations and adoption, including concurrent/repeated adoption requests.
        lockProject(projectId);
        Candidate candidate = candidate(projectId, candidateId);
        if (candidate.status().equals("ADOPTED")) {
            return new Adoption(node(projectId, candidate.acceptedNodeId()), snapshot(candidate.acceptedDocumentId()), candidate);
        }
        ContentNode target = node(projectId, candidate.targetNodeId());
        requireBase(target, candidate.baseDocumentId());
        String content = candidate.content();
        if (candidate.operation().equals("CREATE_NODE")) {
            node(projectId, candidate.parentNodeId());
            int insertion = children(projectId, candidate.parentNodeId()).size();
            if (!blank(candidate.afterNodeId())) {
                ContentNode after = node(projectId, candidate.afterNodeId());
                if (!Objects.equals(after.parentId(), candidate.parentNodeId())) throw conflict("目录位置已改变，请重新生成新增章节候选");
                insertion = after.position() + 1;
            }
            target = insertNode(projectId, candidate.parentNodeId(), candidate.title(), insertion);
        } else if (candidate.operation().equals("APPEND") && target.currentDocumentId() != null) {
            content = Objects.toString(snapshot(target.currentDocumentId()).getContent(), "") + "\n\n" + content;
        }
        DocumentDTO document = writeSnapshot(target, candidate.operation().equals("CREATE_NODE") ? candidate.title() : target.title(),
                content, candidate.format(), candidate.instruction(), new SnapshotSource(existingSourceSession(projectId, candidate.sessionId()), candidate.id(), "agent", candidate.sourceSessionId()));
        jdbc.update("UPDATE writing_candidate SET status='ADOPTED', accepted_document_id=?, accepted_node_id=?, adopted_at=CURRENT_TIMESTAMP WHERE id=?",
                uuid(document.getId()), uuid(target.id()), uuid(candidateId));
        return new Adoption(node(projectId, target.id()), document, candidate(projectId, candidateId));
    }

    @Transactional
    public String wholeContent(String projectId) {
        lockProject(projectId);
        ensureRoot(projectId);
        StringBuilder result = new StringBuilder();
        Map<String, Integer> depths = new HashMap<>();
        for (ContentNode entry : orderedNodes(projectId)) {
            int depth = entry.root() ? 0 : depths.getOrDefault(entry.parentId(), 0) + 1;
            depths.put(entry.id(), depth);
            if (!entry.root()) result.append("#".repeat(Math.min(6, depth + 1))).append(" ").append(entry.title()).append("\n\n");
            if (entry.currentDocumentId() != null) result.append(Objects.toString(snapshot(entry.currentDocumentId()).getContent(), "")).append("\n\n");
        }
        return result.toString().trim();
    }

    @Transactional(readOnly = true)
    public String writingContext(WritingTarget target) {
        if (target == null) return "";
        if (target.projectId() == null) return "\n本次为历史独立会话的自由讨论，不自动写入作品正文。\n";
        List<ContentNode> ordered = orderedNodes(target.projectId());
        String description = jdbc.queryForObject("SELECT COALESCE(description,'') FROM project WHERE id=?", String.class, uuid(target.projectId()));
        StringBuilder result = new StringBuilder("\n## 作品目标与约束\n").append(limit(description, 4000)).append("\n## 作品目录\n");
        for (ContentNode entry : ordered) {
            if (result.length() > 16000) { result.append("（其余目录省略）\n"); break; }
            result.append(entry.id().equals(target.nodeId()) ? "当前：" : "- ").append(entry.title()).append('\n');
        }
        List<ContentNode> preceding = new ArrayList<>();
        Map<String, ContentNode> byId = new HashMap<>();
        ordered.forEach(entry -> byId.put(entry.id(), entry));
        Deque<ContentNode> ancestors = new ArrayDeque<>();
        ContentNode selected = byId.get(target.nodeId());
        for (ContentNode ancestor = selected == null ? null : byId.get(selected.parentId()); ancestor != null; ancestor = byId.get(ancestor.parentId())) ancestors.push(ancestor);
        result.append("\n## 上层节点的已保存约束与正文\n");
        Set<String> ancestorIds = new HashSet<>();
        for (ContentNode ancestor : ancestors) {
            ancestorIds.add(ancestor.id());
            if (ancestor.currentDocumentId() != null) {
                String text = blank(ancestor.summary()) ? snapshot(ancestor.currentDocumentId()).getContent() : ancestor.summary();
                result.append(ancestor.title()).append(": ").append(limit(text, 1200)).append('\n');
            }
            if (result.length() > 28000) break;
        }
        for (ContentNode entry : ordered) { if (entry.id().equals(target.nodeId())) break; if (entry.currentDocumentId() != null && !ancestorIds.contains(entry.id())) preceding.add(entry); }
        result.append("\n## 前序正文（只含已保存版本）\n");
        for (ContentNode entry : preceding.subList(Math.max(0, preceding.size() - 12), preceding.size())) {
            String summary = entry.summary();
            if (blank(summary)) summary = limit(snapshot(entry.currentDocumentId()).getContent(), 700);
            result.append(entry.title()).append(": ").append(limit(summary, 900)).append('\n');
        }
        result.append("\n会话中的试写和候选仅供讨论，不能替代作品已保存正文和明确确认的设定。\n");
        switch (target.operation()) {
            case "APPEND" -> result.append("本次只输出当前正文之后新增的续写段落，不要重复原文，不另起下一章。\n");
            case "CREATE_NODE" -> result.append("本次生成一个新章节的完整正文；新章节仍是待采纳候选，不修改原章节。\n");
            case "REPLACE" -> result.append("本次输出目标节点修改后的完整正文，供用户预览采纳。\n");
            default -> result.append("本次为自由讨论，结果不会自动写入作品正文。\n");
        }
        return result.toString();
    }

    public DocumentDTO snapshot(String documentId) {
        return jdbc.query("SELECT * FROM document WHERE id=?", DOCUMENT, uuid(documentId)).stream().findFirst()
                .orElseThrow(() -> new BizException(BizException.ErrorCode.DOCUMENT_NOT_FOUND));
    }

    public boolean isCurrentSnapshot(String nodeId, String documentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_node WHERE id=? AND current_document_id=? AND NOT deleted", Integer.class, uuid(nodeId), uuid(documentId)) > 0;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void storeSummary(String nodeId, String documentId, String summary) {
        jdbc.update("UPDATE content_node SET summary=?, summary_document_id=? WHERE id=? AND current_document_id=? AND NOT deleted",
                summary, uuid(documentId), uuid(nodeId), uuid(documentId));
    }

    private record SnapshotSource(String sessionId, String sourceId, String kind, String originalSessionId) {}

    private String legacyOrigin(DocumentDTO document) {
        if (blank(document.getMetadata())) return document.getSessionId();
        try {
            var metadata = json.readTree(document.getMetadata());
            return metadata == null ? document.getSessionId() : metadata.path("sourceSessionId").asText(document.getSessionId());
        } catch (JsonProcessingException ignored) { return document.getSessionId(); }
    }

    private DocumentDTO writeSnapshot(ContentNode target, String title, String content, String format, String instruction, SnapshotSource source) {
        String id = UUID.randomUUID().toString();
        int version = target.version() + 1;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceSessionId", source.originalSessionId());
        metadata.put("derivedFromDocumentId", target.currentDocumentId());
        metadata.put("derivedFromVersion", target.version() > 0 ? target.version() : null);
        metadata.put("sourceId", source.sourceId());
        metadata.put("generatedBy", source.kind());
        if (source.kind().equals("user")) metadata.put("createdBy", "user");
        metadata.put("instruction", instruction);
        String metadataJson;
        try { metadataJson = json.writeValueAsString(metadata); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法保存快照来源", e); }
        jdbc.update("""
                INSERT INTO document(id,project_id,node_id,session_id,title,type,content,format,version,status,metadata)
                VALUES (?,?,?,?,?,'draft',?,?,?,'editing',CAST(? AS jsonb))
                """, uuid(id), uuid(target.projectId()), uuid(target.id()), uuid(source.sessionId()), safeTitle(title), content, format, version, metadataJson);
        jdbc.update("UPDATE content_node SET current_document_id=?, current_version=?, title=?, summary='', summary_document_id=NULL, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                uuid(id), version, safeTitle(title), uuid(target.id()));
        events.publishEvent(new SnapshotSaved(target.projectId(), target.id(), id, source.sessionId(), safeTitle(title), content, instruction));
        return snapshot(id);
    }

    private ContentNode ensureRoot(String projectId) {
        List<ContentNode> roots = jdbc.query("SELECT * FROM content_node WHERE project_id=? AND is_root", NODE, uuid(projectId));
        if (!roots.isEmpty()) return roots.get(0);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO content_node(id,project_id,title,is_root) VALUES (?,?,'正文',TRUE)", uuid(id), uuid(projectId));
        return node(projectId, id);
    }

    private ContentNode insertNode(String projectId, String parentId, String title, Integer requestedPosition) {
        List<ContentNode> siblings = children(projectId, parentId);
        int insertion = position(requestedPosition, siblings.size());
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO content_node(id,project_id,parent_id,title,position) VALUES (?,?,?,?,?)", uuid(id), uuid(projectId), uuid(parentId), safeTitle(title), insertion);
        List<String> reordered = new ArrayList<>(siblings.stream().map(ContentNode::id).toList());
        reordered.add(insertion, id);
        setPositions(reordered);
        return node(projectId, id);
    }

    private ContentNode node(String projectId, String id) {
        ContentNode result = jdbc.query("SELECT * FROM content_node WHERE id=? AND NOT deleted", NODE, uuid(id)).stream().findFirst()
                .orElseThrow(() -> new BizException("404", "正文节点不存在或已删除"));
        if (!result.projectId().equals(projectId)) throw forbidden();
        return result;
    }

    private List<ContentNode> children(String projectId, String parentId) {
        return jdbc.query("SELECT * FROM content_node WHERE project_id=? AND parent_id=? AND NOT deleted ORDER BY position,created_at,id", NODE, uuid(projectId), uuid(parentId));
    }

    private List<ContentNode> orderedNodes(String projectId) {
        List<ContentNode> flat = jdbc.query("SELECT * FROM content_node WHERE project_id=? AND NOT deleted ORDER BY position,created_at,id", NODE, uuid(projectId));
        Map<String, List<ContentNode>> children = new HashMap<>();
        for (ContentNode entry : flat) children.computeIfAbsent(Objects.toString(entry.parentId(), ""), ignored -> new ArrayList<>()).add(entry);
        Deque<Iterator<ContentNode>> stack = new ArrayDeque<>();
        stack.push(children.getOrDefault("", List.of()).iterator());
        List<ContentNode> ordered = new ArrayList<>();
        while (!stack.isEmpty()) {
            if (!stack.peek().hasNext()) { stack.pop(); continue; }
            ContentNode next = stack.peek().next();
            ordered.add(next);
            stack.push(children.getOrDefault(next.id(), List.of()).iterator());
        }
        return ordered;
    }

    private Candidate candidate(String projectId, String id) {
        Candidate result = jdbc.query("SELECT * FROM writing_candidate WHERE id=?", CANDIDATE, uuid(id)).stream().findFirst()
                .orElseThrow(() -> new BizException("404", "候选不存在"));
        if (!projectId.equals(result.projectId())) throw forbidden();
        return result;
    }

    private void requireProject(String projectId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM project WHERE id=?", Integer.class, uuid(projectId)) == 0)
            throw new BizException(BizException.ErrorCode.PROJECT_NOT_FOUND);
    }

    private void lockProject(String projectId) {
        if (jdbc.queryForList("SELECT id FROM project WHERE id=? FOR UPDATE", uuid(projectId)).isEmpty())
            throw new BizException(BizException.ErrorCode.PROJECT_NOT_FOUND);
    }

    private String existingSourceSession(String projectId, String sessionId) {
        if (blank(sessionId)) return null;
        List<String> projects = jdbc.query("SELECT project_id::text FROM task_session WHERE id=?", (rs, n) -> rs.getString(1), uuid(sessionId));
        if (projects.isEmpty()) return null;
        if (!projectId.equals(projects.get(0))) throw forbidden();
        return sessionId;
    }

    private void normalizeChildren(String projectId, String parentId) { setPositions(children(projectId, parentId).stream().map(ContentNode::id).toList()); }
    private void setPositions(List<String> ids) { for (int i = 0; i < ids.size(); i++) jdbc.update("UPDATE content_node SET position=? WHERE id=?", i, uuid(ids.get(i))); }
    private static int position(Integer requested, int size) { if (requested != null && requested < 0) throw invalid("排序位置不能为负数"); return requested == null ? size : Math.min(requested, size); }
    private static void requireBase(ContentNode target, String base) { if (!Objects.equals(target.currentDocumentId(), blank(base) ? null : base)) throw conflict("正文已变化，请保存当前修改并基于最新正文重新生成候选"); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static UUID uuid(String value) { return blank(value) ? null : UUID.fromString(value); }
    private static String safeTitle(String title) { String value = blank(title) ? "未命名章节" : title.trim(); if (value.length() > 500) throw invalid("标题不能超过 500 字符"); return value; }
    private static String limit(String value, int length) { return value == null ? "" : value.substring(0, Math.min(value.length(), length)); }
    private static BizException invalid(String message) { return new BizException("400", message); }
    private static BizException conflict(String message) { return new BizException("409", message); }
    private static BizException forbidden() { return new BizException(BizException.ErrorCode.FORBIDDEN); }
    private static String id(ResultSet rs, String name) throws SQLException { return rs.getString(name); }

    private static final RowMapper<ContentNode> NODE = (rs, n) -> new ContentNode(id(rs,"id"),id(rs,"project_id"),id(rs,"parent_id"),rs.getString("title"),
            rs.getInt("position"),rs.getBoolean("is_root"),id(rs,"current_document_id"),rs.getInt("current_version"),
            Objects.equals(id(rs,"current_document_id"),id(rs,"summary_document_id")) ? rs.getString("summary") : "");
    private static final RowMapper<DocumentDTO> DOCUMENT = (rs, n) -> DocumentDTO.builder().id(id(rs,"id")).projectId(id(rs,"project_id"))
            .nodeId(id(rs,"node_id")).sessionId(id(rs,"session_id")).title(rs.getString("title")).type(rs.getString("type"))
            .content(rs.getString("content")).format(rs.getString("format")).version(rs.getInt("version")).status(rs.getString("status"))
            .metadata(rs.getString("metadata")).createdAt(rs.getTimestamp("created_at").toLocalDateTime()).updatedAt(rs.getTimestamp("updated_at").toLocalDateTime()).build();
    private static final RowMapper<Candidate> CANDIDATE = (rs, n) -> new Candidate(id(rs,"id"),id(rs,"project_id"),id(rs,"session_id"),id(rs,"source_session_id"),
            id(rs,"target_node_id"),id(rs,"parent_node_id"),id(rs,"after_node_id"),rs.getString("operation"),id(rs,"base_document_id"),
            rs.getString("title"),rs.getString("content"),rs.getString("instruction"),rs.getString("format"),rs.getString("status"),
            id(rs,"accepted_document_id"),id(rs,"accepted_node_id"),rs.getObject("created_at", OffsetDateTime.class));
}
