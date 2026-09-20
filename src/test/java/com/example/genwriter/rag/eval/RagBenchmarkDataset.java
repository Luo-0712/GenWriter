package com.example.genwriter.rag.eval;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 评测数据集：语料、分级相关性标注、负样本、指标参数与消融配置。
 *
 * <p><b>语料组织：「一节一文件」</b>。每份源文件即一个语义单元，文件名（去扩展名）就是 docId。
 * 入库时把 chunk 的 sourceId 设为 docId 派生的稳定 UUID，检索结果即可映射回文档，
 * 从而按「query → 文档分级相关性」打分。该映射与分块策略、分块尺寸无关，跨策略可比，
 * 也避免旧评测用「chunk 文本字面包含 snippet」判定命中带来的偏差。
 *
 * <p><b>多格式</b>：语料混合 .md 与 .txt，评测时统一由 Apache Tika 解析，覆盖多格式解析链路。
 */
public final class RagBenchmarkDataset {

    /** 语料目录（classpath）。 */
    public static final String CORPUS_LOCATION = "classpath*:rag-eval/corpus/*";

    /** 指标 K 取值。 */
    public static final int[] K_VALUES = {1, 3, 5, 10};

    /** 生产默认相似度阈值（genwriter.embedding.similarity-threshold）。 */
    public static final double PRODUCTION_THRESHOLD = 0.7;

    /** 生产默认 top-K（genwriter.embedding.default-search-limit）。 */
    public static final int PRODUCTION_TOP_K = 5;

    /** 阈值扫描集合，用于绘制精确率-召回率曲线并校验 0.7 的取值合理性。 */
    public static final double[] THRESHOLD_SWEEP = {0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80};

    /** 消融使用的分块尺寸。 */
    public static final int[] ABLATION_SIZES = {500, 1000, 1500};

    /** 消融使用的分块策略（与 DocumentChunkStrategyFactory 注册名一致）。 */
    public static final List<String> ABLATION_STRATEGIES = List.of("fixed_size", "recursive", "structural");

    /** overlap 与 chunkSize 的比例：1000 → 100，与生产配置 app.knowledge.chunking.default-chunk-overlap 对齐。 */
    private static final double OVERLAP_RATIO = 0.1;

    private RagBenchmarkDataset() {
    }

    /** 一份源文档：docId（文件名去扩展名）、文件名、格式（扩展名）、真实文本内容。 */
    public record CorpusDoc(String docId, String fileName, String format, String text) {
    }

    /** 一条标注查询：query 文本 + 「docId → 相关性等级(2/1/0)」。 */
    public record QueryLabel(String query, Map<String, Integer> docGrades) {
        public java.util.Set<String> relevantDocs() {
            return RetrievalMetrics.relevantDocs(docGrades);
        }
    }

    /** 一组消融配置：分块策略 + chunkSize + overlap。 */
    public record AblationConfig(String strategy, int chunkSize, int chunkOverlap) {
        public String key() {
            return strategy + "-" + chunkSize + "-" + chunkOverlap;
        }

        public String label() {
            return strategy + "/" + chunkSize + "/" + chunkOverlap;
        }
    }

    // ---------------------------------------------------------------- 语料

    /**
     * 从 classpath 加载语料目录下的全部文档，按文件名排序保证可复现。
     */
    public static List<CorpusDoc> loadCorpus() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(CORPUS_LOCATION);
        if (resources.length == 0) {
            throw new IllegalStateException("未找到评测语料: " + CORPUS_LOCATION
                    + "（应位于 src/test/resources/rag-eval/corpus/）");
        }
        List<CorpusDoc> docs = new ArrayList<>(resources.length);
        for (Resource resource : resources) {
            String fileName = resource.getFilename();
            if (!StringUtils.hasText(fileName)) {
                continue;
            }
            String docId = stripExtension(fileName);
            String format = extensionOf(fileName);
            docs.add(new CorpusDoc(docId, fileName, format, readUtf8(resource)));
        }
        docs.sort(Comparator.comparing(CorpusDoc::docId));
        return docs;
    }

    /**
     * docId → 稳定 UUID 的映射：与 chunk 的 source_id 列（UUID）保持一致，
     * 使同一次评测跨运行、跨分块配置都能把 chunk 归回同一文档。
     */
    public static String sourceUuidOf(String docId) {
        return UUID.nameUUIDFromBytes(("genwriter-rag-eval:" + docId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    // ---------------------------------------------------------------- 标注查询

    /**
     * 可回答查询集：每个 query 标注若干文档的分级相关性。
     * 2 = 该文档直接回答此问题；1 = 部分相关/可作补充；未列出的文档即 0。
     */
    public static List<QueryLabel> answerableQueries() {
        List<QueryLabel> queries = new ArrayList<>();
        queries.add(label("叙事节奏里张弛交替的配比应该怎么安排", Map.of("narrative-pacing", 2, "tension-escalation", 1)));
        queries.add(label("怎么用短句和长句控制故事推进的速度", Map.of("narrative-pacing", 2, "paragraph-rhythm", 1)));
        queries.add(label("小说的时间线跨度很长时如何压缩", Map.of("narrative-pacing", 2)));
        queries.add(label("对话里的潜台词怎么写才能不直白", Map.of("dialogue-subtext", 2, "description-restraint", 1)));
        queries.add(label("人物对话应该怎样承担推动剧情的作用", Map.of("dialogue-subtext", 2, "character-motivation", 1)));
        queries.add(label("场景描写应该调动哪些感官才不单调", Map.of("scene-sensory-detail", 2, "description-restraint", 1)));
        queries.add(label("写作时如何挑选细节而不是堆砌形容词", Map.of("scene-sensory-detail", 2, "description-restraint", 1)));
        queries.add(label("如何让人物的动机显得可信", Map.of("character-motivation", 2, "theme-embodiment", 1)));
        queries.add(label("人物的欲望和恐惧应该怎么设计", Map.of("character-motivation", 2)));
        queries.add(label("三幕结构里几个转折点分别应该落在哪里", Map.of("three-act-structure", 2, "tension-escalation", 1)));
        queries.add(label("小说开篇怎样在前几句就抓住读者", Map.of("opening-hook", 2, "foreshadowing", 1)));
        queries.add(label("标题和开篇之间应该是什么关系", Map.of("opening-hook", 2)));
        queries.add(label("比喻用滥了该怎么改", Map.of("metaphor-technique", 2)));
        queries.add(label("通感和排比适合在什么场合使用", Map.of("metaphor-technique", 2, "paragraph-rhythm", 1)));
        queries.add(label("段落的长短如何影响阅读节奏", Map.of("paragraph-rhythm", 2, "narrative-pacing", 1)));
        queries.add(label("伏笔埋下之后间隔多久回收比较合适", Map.of("foreshadowing", 2, "ending-resonance", 1)));
        queries.add(label("叙述视角距离的远近会带来什么不同效果", Map.of("perspective-distance", 2, "description-restraint", 1)));
        queries.add(label("什么时候应该使用限知视角", Map.of("perspective-distance", 2)));
        queries.add(label("描写太满会不会拖慢故事", Map.of("description-restraint", 2, "narrative-pacing", 1)));
        queries.add(label("如何让冲突一轮一轮地升级", Map.of("tension-escalation", 2, "three-act-structure", 1)));
        queries.add(label("结尾怎么写才有余韵", Map.of("ending-resonance", 2, "opening-hook", 1)));
        queries.add(label("主题怎么通过情节具象化而不是变成说教", Map.of("theme-embodiment", 2, "character-motivation", 1)));
        queries.add(label("正式文体的遣词造句有哪些规范", Map.of("style-formal-guide", 2)));
        queries.add(label("口语化写作需要注意什么", Map.of("style-casual-guide", 2, "dialogue-subtext", 1)));
        queries.add(label("修改稿子的时候按什么清单逐项检查", Map.of("editing-checklist", 2)));
        queries.add(label("世界观设定需要向读者交代到什么程度", Map.of("worldbuilding-basics", 2, "description-restraint", 1)));
        return queries;
    }

    /**
     * 负样本查询集：与写作技法语料完全无关，用于度量「误注入率」与「拒答率」。
     * 期望行为是阈值过滤后返回空结果（拒答）。
     */
    public static List<String> negativeQueries() {
        return List.of(
                "如何用 Python 连接 PostgreSQL 数据库并建立索引",
                "红烧肉的正宗做法需要放多少冰糖",
                "去年新能源乘用车的销量排名前十是哪些品牌",
                "怎么计算圆锥曲线的离心率",
                "感冒发烧的时候应该吃哪种药",
                "足球比赛里越位规则的具体判定标准是什么",
                "如何给一台服务器配置 HTTPS 证书",
                "股票的市盈率和市净率有什么区别");
    }

    // ---------------------------------------------------------------- 消融配置

    /**
     * 消融配置矩阵：策略 × chunkSize × 按比例换算的 overlap。
     * 唯一变量是分块配置，语料、查询集、向量模型、检索路径全部保持不变，保证结果可比。
     */
    public static List<AblationConfig> ablationConfigs() {
        List<AblationConfig> configs = new ArrayList<>();
        for (String strategy : ABLATION_STRATEGIES) {
            for (int size : ABLATION_SIZES) {
                configs.add(new AblationConfig(strategy, size, overlapOf(size)));
            }
        }
        return configs;
    }

    /** overlap = chunkSize × 10%，向下取整后至少为 0。 */
    public static int overlapOf(int chunkSize) {
        return (int) Math.floor(chunkSize * OVERLAP_RATIO);
    }

    /** 生产默认分块配置（recursive / 1000 / 100）。 */
    public static AblationConfig productionConfig() {
        return new AblationConfig("recursive", 1000, 100);
    }

    private static QueryLabel label(String query, Map<String, Integer> docGrades) {
        return new QueryLabel(query, new LinkedHashMap<>(docGrades));
    }

    private static String readUtf8(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }

    /** 供报告使用的数据集摘要。 */
    public static Map<String, String> summary(List<CorpusDoc> corpus) {
        long mdCount = corpus.stream().filter(d -> "md".equals(d.format())).count();
        long txtCount = corpus.stream().filter(d -> "txt".equals(d.format())).count();
        int totalChars = corpus.stream().mapToInt(d -> d.text().length()).sum();
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("语料文档数", String.valueOf(corpus.size()));
        summary.put("语料格式分布", "md=" + mdCount + ", txt=" + txtCount);
        summary.put("语料总字符数", String.valueOf(totalChars));
        summary.put("可回答查询数", String.valueOf(answerableQueries().size()));
        summary.put("负样本查询数", String.valueOf(negativeQueries().size()));
        summary.put("消融配置数", String.valueOf(ablationConfigs().size()));
        summary.put("指标 K 取值", Arrays.toString(K_VALUES));
        return summary;
    }
}
