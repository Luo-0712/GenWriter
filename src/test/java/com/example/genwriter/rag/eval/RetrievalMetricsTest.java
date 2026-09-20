package com.example.genwriter.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RetrievalMetrics} 的纯函数单测：不依赖 Spring、数据库与外部 API。
 *
 * <p>检索评测的结论全部由这些指标函数汇总而来，因此先用手工可验算的小样例把每个函数钉死，
 * 保证报告里的数字是「指标算法正确 + 真实检索结果」共同产出的，而不是算法自身引入了偏差。
 */
@DisplayName("RetrievalMetrics 指标算法")
class RetrievalMetricsTest {

    private static RetrievalMetrics.RankedDoc doc(String docId) {
        return new RetrievalMetrics.RankedDoc(docId, 0.0);
    }

    @Test
    @DisplayName("按 docId 去重时保留排名最靠前的一条，且不改变相对顺序")
    void dedupeByDocKeepsFirstOccurrence() {
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("a"), doc("b"), doc("a"), doc("c"), doc("b"));
        List<RetrievalMetrics.RankedDoc> deduped = RetrievalMetrics.dedupeByDoc(ranked);
        assertEquals(List.of("a", "b", "c"), deduped.stream().map(RetrievalMetrics.RankedDoc::docId).toList());
    }

    @Test
    @DisplayName("Hit@K：top-K 内出现相关文档即为 1")
    void hitAtK() {
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("x"), doc("y"), doc("z"));
        Set<String> relevant = Set.of("y");
        assertEquals(0.0, RetrievalMetrics.hitAtK(ranked, relevant, 1));
        assertEquals(1.0, RetrievalMetrics.hitAtK(ranked, relevant, 2));
        assertEquals(1.0, RetrievalMetrics.hitAtK(ranked, relevant, 10));
    }

    @Test
    @DisplayName("Precision@K：分母取实际返回条数，不足 K 时不虚增精确率")
    void precisionAtK() {
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("a"), doc("x"), doc("b"));
        Set<String> relevant = Set.of("a", "b");
        assertEquals(2.0 / 3, RetrievalMetrics.precisionAtK(ranked, relevant, 3), 1e-9);
        // 只返回 2 条（命中 1 条）时，分母是 2 而不是 K=3，否则会被算成 1/3
        assertEquals(0.5, RetrievalMetrics.precisionAtK(ranked.subList(0, 2), relevant, 3), 1e-9);
    }

    @Test
    @DisplayName("Recall@K：分母是标注中的相关文档总数")
    void recallAtK() {
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("a"), doc("x"));
        Set<String> relevant = Set.of("a", "b");
        assertEquals(0.5, RetrievalMetrics.recallAtK(ranked, relevant, 5), 1e-9);
        assertEquals(0.0, RetrievalMetrics.recallAtK(ranked, Set.of(), 5), 1e-9);
    }

    @Test
    @DisplayName("MRR：取第一个相关文档排名的倒数")
    void reciprocalRank() {
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("x"), doc("y"), doc("b"));
        assertEquals(1.0 / 3, RetrievalMetrics.reciprocalRank(ranked, Set.of("b")), 1e-9);
        assertEquals(1.0, RetrievalMetrics.reciprocalRank(ranked, Set.of("x")), 1e-9);
        assertEquals(0.0, RetrievalMetrics.reciprocalRank(ranked, Set.of("none")), 1e-9);
    }

    @Test
    @DisplayName("Average Precision：位置加权累计命中数 / 相关文档总数")
    void averagePrecision() {
        // 命中位置为第 2、3 位：(1/2 + 2/3) / 2
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("a"), doc("b"), doc("c"));
        Set<String> relevant = Set.of("b", "c");
        assertEquals((0.5 + 2.0 / 3) / 2, RetrievalMetrics.averagePrecision(ranked, relevant), 1e-9);
    }

    @Test
    @DisplayName("nDCG@K：分级增益 2^grade-1，IDCG 由标注的理想排序得到")
    void ndcgAtK() {
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("a"), doc("b"), doc("c"));
        Map<String, Integer> grades = Map.of("a", 2, "b", 0, "c", 1);
        // DCG = 3/1 + 0 + 1/2 = 3.5；IDCG（理想排序 2,1）= 3/1 + 1/log2(3) ≈ 3.63093
        double idcg = 3.0 + 1.0 / (Math.log(3) / Math.log(2));
        assertEquals(3.5 / idcg, RetrievalMetrics.ndcgAtK(ranked, grades, 3), 1e-9);
    }

    @Test
    @DisplayName("nDCG@K：无任何相关文档时返回 0 而不是 NaN")
    void ndcgAtKWithoutRelevant() {
        List<RetrievalMetrics.RankedDoc> ranked = List.of(doc("a"));
        assertEquals(0.0, RetrievalMetrics.ndcgAtK(ranked, Map.of("a", 0), 3));
    }

    @Test
    @DisplayName("F1 与平均值工具函数")
    void f1AndAverage() {
        assertEquals(0.5, RetrievalMetrics.f1(0.5, 0.5), 1e-9);
        assertEquals(0.0, RetrievalMetrics.f1(0.0, 0.0), 1e-9);
        assertEquals(2.0, RetrievalMetrics.average(List.of(1.0, 2.0, 3.0)), 1e-9);
        assertEquals(0.0, RetrievalMetrics.average(List.of()), 1e-9);
    }

    @Test
    @DisplayName("分位数采用最近秩方法，空样本返回 0")
    void percentile() {
        List<Double> samples = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        assertEquals(5.0, RetrievalMetrics.percentile(samples, 0.50), 1e-9);
        assertEquals(10.0, RetrievalMetrics.percentile(samples, 0.99), 1e-9);
        assertEquals(0.0, RetrievalMetrics.percentile(List.of(), 0.50), 1e-9);
    }

    @Test
    @DisplayName("分级标注中只把 grade > 0 视为相关文档")
    void relevantDocsFromGrades() {
        Map<String, Integer> grades = new java.util.LinkedHashMap<>();
        grades.put("a", 2);
        grades.put("b", 1);
        grades.put("c", 0);
        Set<String> relevant = RetrievalMetrics.relevantDocs(grades);
        assertEquals(2, relevant.size());
        assertTrue(relevant.contains("a") && relevant.contains("b"));
    }
}
