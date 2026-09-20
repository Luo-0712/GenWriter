package com.example.genwriter.rag.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 评测报告渲染与落盘。
 *
 * <p>报告写到 Maven 模块工作目录下的 {@code target/rag-eval/}（构建产物目录），
 * 文件名带时间戳，便于多次运行对照；不写 {@code docs/}，保证报告可随 {@code mvn clean} 清理、
 * 不污染源码仓库。
 *
 * <p>报告固定包含「测试流程」一节：从 Tika 解析到指标计算的每一步，用于对照代码复述评测链路。
 */
public final class RagEvalReportWriter {

    /** 报告输出目录（相对模块工作目录）。 */
    public static final String OUTPUT_DIR = "target/rag-eval";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String title;
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final List<String> sections = new ArrayList<>();

    public RagEvalReportWriter(String title) {
        this.title = title;
    }

    /** 追加一条运行元信息（模型、维度、阈值、数据集规模等）。 */
    public RagEvalReportWriter meta(String key, Object value) {
        metadata.put(key, value == null ? "-" : String.valueOf(value));
        return this;
    }

    /** 批量追加运行元信息。 */
    public RagEvalReportWriter metaAll(Map<String, String> values) {
        if (values != null) {
            values.forEach(this::meta);
        }
        return this;
    }

    /** 追加一节正文，body 为 Markdown 片段。 */
    public RagEvalReportWriter section(String heading, String body) {
        sections.add("## " + heading + "\n\n" + (body == null ? "" : body.strip()) + "\n");
        return this;
    }

    /** 渲染完整 Markdown。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("- 生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append('\n');
        metadata.forEach((k, v) -> sb.append("- ").append(k).append("：").append(v).append('\n'));
        sb.append('\n');
        for (String section : sections) {
            sb.append(section).append('\n');
        }
        return sb.toString();
    }

    /** 渲染并写入 target/rag-eval/&lt;filePrefix&gt;-&lt;时间戳&gt;.md，返回文件路径。 */
    public Path write(String filePrefix) throws IOException {
        Path out = Path.of(OUTPUT_DIR, filePrefix + "-" + LocalDateTime.now().format(TS) + ".md");
        Files.createDirectories(out.getParent());
        String content = render();
        Files.writeString(out, content, StandardCharsets.UTF_8);
        return out;
    }

    /**
     * 固定小节：测试流程说明。逐步骤对应到具体类与方法，便于对照代码讲解。
     */
    public RagEvalReportWriter processSection(String ingestNote, String retrievalNote, String metricNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("本评测全程复用生产链路组件，不做内存近似替代。\n\n");
        sb.append("1. **语料与解析**：语料位于 `src/test/resources/rag-eval/corpus/`（一节一文件，md/txt 混合）。")
                .append("运行时先落盘到临时目录，再由 `DocumentChunkingService#chunkDocument` 经 Apache Tika 解析真实文件。")
                .append(ingestNote == null ? "" : " " + ingestNote)
                .append('\n');
        sb.append("2. **分块 → 向量化 → 入库**：分块调用 `DocumentChunkStrategyFactory` 中的真实策略；")
                .append("向量化由 `KnowledgeChunkService#createChunks` 内部调用真实 `EmbeddingService`（L2 归一化）；")
                .append("落库到真实 `knowledge_chunk` 表。\n");
        sb.append("3. **检索**：").append(retrievalNote).append('\n');
        sb.append("4. **指标计算**：").append(metricNote).append('\n');
        sb.append("5. **标注与判定口径**：`RagBenchmarkDataset` 为每条 query 标注了相关文档及其分级（2=直接回答，")
                .append("1=部分相关，0=无关），用于 Recall@K、MRR、MAP、nDCG@K；另有一组与语料完全无关的负样本 query，")
                .append("用于拒答率与误注入率。分级标注与「一节一文件」的语料组织方式配合，")
                .append("使标注不依赖任何分块策略或尺寸，跨配置可比。\n");
        sb.append("6. **清理**：评测使用独立 `kbId` 隔离，结束后删除知识库，`knowledge_chunk` 通过外键级联清理，不污染生产数据。\n\n");
        sb.append("> 可重复性说明：检索走的是 HNSW 近似索引，索引图构建与近邻查询本身带有随机性，")
                .append("因此同一份代码在不同次运行之间会出现小数级波动，这属于预期行为；")
                .append("报告中「HNSW 近似检索代价」小节同时给出近似召回率，用于量化这种波动的量级。")
                .append("若要完全消除波动，可将消融与质量指标改走精确路径（顺序扫描），代价是评测耗时上升两个数量级。\n");
        return section("测试流程", sb.toString());
    }

    /** Markdown 表格：表头 + 数据行。 */
    public static String table(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append("---|".repeat(headers.size())).append('\n');
        for (List<String> row : rows) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString();
    }

    /** 百分比格式：0.8123 → "81.23%" */
    public static String pct(double value) {
        return String.format("%.2f%%", value * 100);
    }

    /** 四位小数格式。 */
    public static String num4(double value) {
        return String.format("%.4f", value);
    }

    /** 两位小数格式。 */
    public static String num2(double value) {
        return String.format("%.2f", value);
    }
}
