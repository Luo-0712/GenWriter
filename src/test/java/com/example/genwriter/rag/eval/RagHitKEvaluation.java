package com.example.genwriter.rag.eval;

import com.example.genwriter.model.dto.DocumentChunk;
import com.example.genwriter.rag.chunking.ChunkingConfig;
import com.example.genwriter.rag.chunking.DocumentChunkStrategy;
import com.example.genwriter.rag.chunking.FixedSizeChunkStrategy;
import com.example.genwriter.rag.chunking.RecursiveChunkStrategy;
import com.example.genwriter.rag.chunking.StructuralChunkStrategy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * RAG 切割策略 Hit@K 评测（写作场景）。
 *
 * 流程：语料 → 三种策略切分 → 全部 chunk 与 24 个问题分别向量化（真实调用 DashScope）
 * → 余弦相似度排序 → 命中判定（chunk 包含任一 ground-truth 片段）→ Hit@1/3/5 报表。
 *
 * 设计说明：每个问题都调用 embedding API 一次、chunk 一次性批量向量化后内存比对，
 * 与生产链路（pgvector 余弦距离排序）在相似度语义上等价。
 */
public class RagHitKEvaluation {

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    record ScoredChunk(DocumentChunk chunk, double similarity, boolean hit) {}

    record QuestionResult(String question, List<ScoredChunk> ranked, boolean hitAt1, boolean hitAt3, boolean hitAt5) {}

    record StrategyReport(String strategyName, int chunkCount, double avgChunkChars,
            double hit1, double hit3, double hit5, List<QuestionResult> details,
            int unrelatedCount, double falseInjectionRate, double rejectionRate,
            List<Double> unrelatedTopScores) {}

    public static void main(String[] args) throws Exception {
        var api = new com.alibaba.cloud.ai.dashscope.api.DashScopeApi(System.getenv("DASHSCOPE_API_KEY"));
        var embeddingModel = new com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel(api,
                org.springframework.ai.document.MetadataMode.EMBED,
                com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions.builder()
                        .withModel(RagEvalDataset.EMBEDDING_MODEL)
                        .build());

        // 1. 语料向量化探针：确认模型可用 + 维度
        float[] probe = embeddingModel.embed(RagEvalDataset.CORPUS.substring(0, 200));
        System.out.println("[PROBE] model=" + RagEvalDataset.EMBEDDING_MODEL + " dim=" + probe.length);

        List<DocumentChunkStrategy> strategies = List.of(
                new FixedSizeChunkStrategy(), new RecursiveChunkStrategy(), new StructuralChunkStrategy());

        List<StrategyReport> reports = new ArrayList<>();
        for (DocumentChunkStrategy strategy : strategies) {
            reports.add(evalStrategy(strategy, embeddingModel));
        }

        String report = renderReport(reports, probe.length);
        Path out = Path.of(System.getProperty("user.home"), "Desktop", "八股速记", "项目改进",
                "RAG-HitK-评测报告-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println("\n[DONE] 报告已写入: " + out);
    }

    private static StrategyReport evalStrategy(DocumentChunkStrategy strategy,
            com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel embeddingModel) {
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(CHUNK_SIZE).chunkOverlap(CHUNK_OVERLAP).build();
        List<DocumentChunk> chunks = strategy.chunk(RagEvalDataset.CORPUS, config);

        // 批量向量化所有 chunk
        List<float[]> chunkVectors = embedAll(embeddingModel, chunks.stream().map(DocumentChunk::getContent).toList());

        // 逐问题向量化 + 排序 + 命中判定
        List<QuestionResult> results = new ArrayList<>();
        for (RagEvalDataset.EvalQuestion q : RagEvalDataset.questions()) {
            float[] qv = normalize(embeddingModel.embed(q.question()));
            List<ScoredChunk> ranked = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new ScoredChunk(chunks.get(i),
                            cosine(qv, chunkVectors.get(i)), isHit(chunks.get(i), q)))
                    .sorted(Comparator.comparingDouble(ScoredChunk::similarity).reversed())
                    .toList();
            results.add(new QuestionResult(q.question(), ranked,
                    ranked.size() > 0 && ranked.get(0).hit(),
                    ranked.size() > 2 && ranked.stream().limit(3).anyMatch(ScoredChunk::hit),
                    ranked.stream().limit(5).anyMatch(ScoredChunk::hit)));
        }

        double h1 = results.stream().filter(QuestionResult::hitAt1).count() / (double) results.size();
        double h3 = results.stream().filter(QuestionResult::hitAt3).count() / (double) results.size();
        double h5 = results.stream().filter(QuestionResult::hitAt5).count() / (double) results.size();
        int totalChars = chunks.stream().mapToInt(c -> c.getContent().length()).sum();

        // 负样本：与语料无关的 query，top1 余弦相似度越过判定阈值即记为「误注入」，否则为「拒答」
        double evalThreshold = evalThreshold();
        List<Double> unrelatedTopScores = new ArrayList<>();
        for (String unrelated : RagEvalDataset.unrelatedQueries()) {
            float[] uq = normalize(embeddingModel.embed(unrelated));
            double topSim = 0.0;
            for (int i = 0; i < chunks.size(); i++) {
                topSim = Math.max(topSim, cosine(uq, chunkVectors.get(i)));
            }
            unrelatedTopScores.add(topSim);
        }
        long injections = unrelatedTopScores.stream().filter(s -> s >= evalThreshold).count();
        double falseInjectionRate = unrelatedTopScores.isEmpty() ? 0.0
                : injections / (double) unrelatedTopScores.size();
        double rejectionRate = 1.0 - falseInjectionRate;

        System.out.printf("[%s] chunks=%d avgChars=%.0f Hit@1=%.1f%% Hit@3=%.1f%% Hit@5=%.1f%% "
                        + "无关误注入率=%.1f%% 拒答率=%.1f%% (threshold=%.2f)%n",
                strategy.getStrategyName(), chunks.size(), chunks.isEmpty() ? 0 : totalChars / (double) chunks.size(),
                h1 * 100, h3 * 100, h5 * 100, falseInjectionRate * 100, rejectionRate * 100, evalThreshold);
        return new StrategyReport(strategy.getStrategyName(), chunks.size(),
                chunks.isEmpty() ? 0 : totalChars / (double) chunks.size(), h1, h3, h5, results,
                unrelatedTopScores.size(), falseInjectionRate, rejectionRate, unrelatedTopScores);
    }

    /** 注入判定阈值：默认 0.70（与线上 cosine 阈值对齐），可用 -Drag.eval.similarity-threshold=0.65 覆盖 */
    private static double evalThreshold() {
        return Double.parseDouble(System.getProperty("rag.eval.similarity-threshold", "0.70"));
    }

    private static List<float[]> embedAll(com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel embeddingModel,
            List<String> texts) {
        List<float[]> all = new ArrayList<>();
        int batch = 10;
        for (int i = 0; i < texts.size(); i += batch) {
            all.addAll(embeddingModel.embed(texts.subList(i, Math.min(i + batch, texts.size()))));
        }
        return all;
    }

    /** chunk 内容包含任一 ground-truth 片段即命中 */
    private static boolean isHit(DocumentChunk chunk, RagEvalDataset.EvalQuestion q) {
        return q.groundTruthSnippets().stream().anyMatch(s -> chunk.getContent().contains(s));
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    private static String renderReport(List<StrategyReport> reports, int dim) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG 切割策略 Hit@K 评测报告\n\n");
        sb.append("- 时间：").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("- 向量模型：`").append(RagEvalDataset.EMBEDDING_MODEL).append("`（维度 ").append(dim).append("）\n");
        sb.append("- 语料：写作技法手册 8 章约 ").append(RagEvalDataset.CORPUS.length()).append(" 字（Markdown）\n");
        sb.append("- 问题集：").append(RagEvalDataset.questions().size()).append(" 个写作场景问题，人工标注正确内容片段\n");
        sb.append("- 负样本集：").append(RagEvalDataset.unrelatedQueries().size())
                .append(" 个与语料无关的 query（与记忆侧 UNRELATED_QUERIES 语义等价）\n");
        sb.append("- 注入判定阈值：").append(evalThreshold())
                .append("（-Drag.eval.similarity-threshold 可覆盖）\n");
        sb.append("- 切分参数：chunkSize=").append(CHUNK_SIZE).append(", overlap=").append(CHUNK_OVERLAP).append('\n');
        sb.append("- 命中判定：chunk 文本包含任一标注片段即视为正确\n\n");

        sb.append("## 总览\n\n");
        sb.append("| 策略 | chunk 数 | 平均 chunk 字数 | Hit@1 | Hit@3 | Hit@5 | 无关误注入率 | 拒答率 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (StrategyReport r : reports) {
            sb.append(String.format("| %s | %d | %.0f | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% |%n",
                    r.strategyName(), r.chunkCount(), r.avgChunkChars(),
                    r.hit1() * 100, r.hit3() * 100, r.hit5() * 100,
                    r.falseInjectionRate() * 100, r.rejectionRate() * 100));
        }

        sb.append("\n## 负样本明细（无关 query 的 top1 余弦相似度）\n\n");
        List<String> unrelated = RagEvalDataset.unrelatedQueries();
        sb.append("| 无关 query |");
        for (StrategyReport r : reports) {
            sb.append(" ").append(r.strategyName()).append(" |");
        }
        sb.append("\n|---|");
        for (int i = 0; i < reports.size(); i++) {
            sb.append("---|");
        }
        sb.append('\n');
        for (int i = 0; i < unrelated.size(); i++) {
            sb.append("| ").append(unrelated.get(i)).append(" |");
            for (StrategyReport r : reports) {
                double score = i < r.unrelatedTopScores().size() ? r.unrelatedTopScores().get(i) : 0.0;
                sb.append(" ").append(String.format("%.4f%s", score, score >= evalThreshold() ? " ⚠️" : "")).append(" |");
            }
            sb.append('\n');
        }

        sb.append("\n## 各策略失题明细\n\n");
        for (StrategyReport r : reports) {
            sb.append("### ").append(r.strategyName()).append("\n\n");
            List<QuestionResult> misses = r.details().stream()
                    .filter(d -> !d.hitAt5()).toList();
            if (misses.isEmpty()) {
                sb.append("Top5 全部命中，无失题。\n\n");
                continue;
            }
            for (QuestionResult m : misses) {
                sb.append("- ❌ ").append(m.question()).append('\n');
                List<ScoredChunk> top3 = m.ranked().stream().limit(3).toList();
                for (int i = 0; i < top3.size(); i++) {
                    ScoredChunk sc = top3.get(i);
                    sb.append("  - Top").append(i + 1)
                            .append(" (sim=").append(String.format("%.4f", sc.similarity())).append(") ")
                            .append(truncate(sc.chunk().getContent(), 60)).append('\n');
                }
            }
            sb.append('\n');
        }

        sb.append("## 低分问题跨策略对照（Top1 错误时各策略排第一的 chunk）\n\n");
        int n = RagEvalDataset.questions().size();
        for (int qi = 0; qi < n; qi++) {
            final int idx = qi;
            boolean anyMissAt1 = reports.stream().anyMatch(r -> !r.details().get(idx).hitAt1());
            if (!anyMissAt1) continue;
            sb.append("**Q: ").append(reports.get(0).details().get(idx).question()).append("**\n\n");
            for (StrategyReport r : reports) {
                QuestionResult d = r.details().get(qi);
                sb.append("- ").append(r.strategyName()).append(d.hitAt1() ? " ✅" : " ❌")
                        .append(" Top1: ").append(truncate(d.ranked().get(0).chunk().getContent(), 50)).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String s, int len) {
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() <= len ? flat : flat.substring(0, len) + "…";
    }
}
