package com.example.genwriter.rag.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索质量指标库（纯函数，无副作用，可独立校验）。
 *
 * <p>设计约定：
 * <ul>
 *   <li>指标的计算单位是 <b>文档（docId）</b>而不是 chunk。评测语料采用「一节一文件」的组织方式，
 *       因此每个 docId 就是一个语义单元，该映射与分块策略、分块尺寸无关，跨策略可比。</li>
 *   <li>相关性支持分级：2 = 直接回答，1 = 部分相关，0 = 无关。二值指标（Hit/Precision/Recall/MRR/MAP）
 *       把 grade &gt; 0 视为相关；nDCG 使用 2^grade - 1 作为增益。</li>
 *   <li>排序列表需按相似度降序，且已按 docId 去重（见 {@link #dedupeByDoc}）。</li>
 * </ul>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * 单条检索结果：命中的文档标识 + 相似度分数（分数仅用于展示，排序由列表顺序表达）。
     */
    public record RankedDoc(String docId, double score) {
    }

    /**
     * 按 docId 去重，保留排名最靠前（分数最高）的那条，保持原有相对顺序。
     *
     * <p>评分单位是文档，若同一文档的多个 chunk 同时进入 top-K，只应计一次命中，
     * 否则「切片越多越占便宜」，指标会失真。
     */
    public static List<RankedDoc> dedupeByDoc(List<RankedDoc> ranked) {
        Map<String, RankedDoc> best = new LinkedHashMap<>();
        for (RankedDoc doc : ranked) {
            if (doc == null || doc.docId() == null) {
                continue;
            }
            best.putIfAbsent(doc.docId(), doc);
        }
        return new ArrayList<>(best.values());
    }

    // ------------------------------------------------------------------ 二值指标

    /** Hit@K：top-K 内是否至少命中一个相关文档。 */
    public static double hitAtK(List<RankedDoc> ranked, Set<String> relevant, int k) {
        return topK(ranked, k).stream().anyMatch(d -> relevant.contains(d.docId())) ? 1.0 : 0.0;
    }

    /** Precision@K：top-K 中相关文档占比（分母固定为 K，不足 K 时按实际返回数计算）。 */
    public static double precisionAtK(List<RankedDoc> ranked, Set<String> relevant, int k) {
        List<RankedDoc> top = topK(ranked, k);
        if (top.isEmpty()) {
            return 0.0;
        }
        long hits = top.stream().filter(d -> relevant.contains(d.docId())).count();
        return (double) hits / top.size();
    }

    /** Recall@K：top-K 召回的相关文档占全部相关文档的比例。 */
    public static double recallAtK(List<RankedDoc> ranked, Set<String> relevant, int k) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        long hits = topK(ranked, k).stream().filter(d -> relevant.contains(d.docId())).count();
        return (double) hits / relevant.size();
    }

    /** MRR：第一个相关文档的排名倒数；未命中为 0。 */
    public static double reciprocalRank(List<RankedDoc> ranked, Set<String> relevant) {
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i).docId())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Average Precision：MAP 的单条查询分量（二值相关性，R 为相关文档总数）。 */
    public static double averagePrecision(List<RankedDoc> ranked, Set<String> relevant) {
        if (relevant.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        int hits = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (relevant.contains(ranked.get(i).docId())) {
                hits++;
                sum += (double) hits / (i + 1);
            }
        }
        return sum / relevant.size();
    }

    /** F1：阈值扫描时用于综合权衡精确率与召回率。 */
    public static double f1(double precision, double recall) {
        if (precision + recall == 0.0) {
            return 0.0;
        }
        return 2 * precision * recall / (precision + recall);
    }

    // ------------------------------------------------------------------ 分级指标

    /**
     * nDCG@K（分级相关性）。
     *
     * <p>DCG = Σ (2^grade - 1) / log2(rank + 1)；IDCG 由标注中全部文档按等级降序的「理想排序」计算，
     * 因此标注集的规模会自然影响该指标的合理上界。
     */
    public static double ndcgAtK(List<RankedDoc> ranked, Map<String, Integer> docGrades, int k) {
        List<RankedDoc> top = topK(ranked, k);
        double dcg = 0.0;
        for (int i = 0; i < top.size(); i++) {
            int grade = gradeOf(docGrades, top.get(i).docId());
            dcg += gain(grade) / log2(i + 2);
        }

        List<Integer> idealGrades = docGrades.values().stream()
                .filter(g -> g != null && g > 0)
                .sorted(Comparator.reverseOrder())
                .limit(k)
                .toList();
        double idcg = 0.0;
        for (int i = 0; i < idealGrades.size(); i++) {
            idcg += gain(idealGrades.get(i)) / log2(i + 2);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    /** 分级增益：2^grade - 1。 */
    public static double gain(int grade) {
        return Math.pow(2, Math.max(grade, 0)) - 1;
    }

    private static int gradeOf(Map<String, Integer> docGrades, String docId) {
        Integer grade = docGrades.get(docId);
        return grade == null ? 0 : grade;
    }

    // ------------------------------------------------------------------ 聚合工具

    /** 均值；空集合返回 0。 */
    public static double average(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /**
     * 分位数（最近秩方法）。
     *
     * @param samples 样本，方法内部会复制并排序，不修改入参
     * @param percentile 0.5 表示 P50，0.99 表示 P99
     */
    public static double percentile(List<Double> samples, double percentile) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    /** 保留 4 位小数，便于报告输出。 */
    public static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /** 保留 2 位小数。 */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** 从分级标注中取出相关文档集合（grade &gt; 0）。 */
    public static Set<String> relevantDocs(Map<String, Integer> docGrades) {
        Set<String> relevant = new LinkedHashSet<>();
        docGrades.forEach((docId, grade) -> {
            if (grade != null && grade > 0) {
                relevant.add(docId);
            }
        });
        return relevant;
    }

    private static List<RankedDoc> topK(List<RankedDoc> ranked, int k) {
        if (ranked == null || ranked.isEmpty() || k <= 0) {
            return List.of();
        }
        return ranked.size() <= k ? ranked : ranked.subList(0, k);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
