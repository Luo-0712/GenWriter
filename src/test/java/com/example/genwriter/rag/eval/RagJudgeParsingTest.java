package com.example.genwriter.rag.eval;

import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 判官输出解析的纯函数单测：不依赖 Spring 上下文、数据库与模型 API。
 *
 * <p>判官是自由文本模型，实际输出经常带解释、代码块或越界编号，因此解析必须容错。
 * 这里把 {@link RagGenerationJudgeTest#parseJudge} 与
 * {@link RagGenerationJudgeTest#renderContexts} 的行为钉死，
 * 使得在「对话模型不可用」的环境下也能验证生成端评测的判定逻辑本身是正确的。
 */
@DisplayName("RAG 判官输出解析与上下文渲染")
class RagJudgeParsingTest {

    private final RagGenerationJudgeTest judge = new RagGenerationJudgeTest();

    @Test
    @DisplayName("标准 JSON：分数与引用编号被正确读出")
    void parsesPlainJson() {
        String raw = "{\"faithfulness\": 0.8, \"answer_relevancy\": 0.9, "
                + "\"cited_context_indices\": [1, 3], \"reason\": \"都能在资料中找到依据\"}";
        RagGenerationJudgeTest.JudgeResult result = judge.parseJudge(raw, 3);

        assertTrue(result.parsed());
        assertEquals(0.8, result.faithfulness(), 1e-9);
        assertEquals(0.9, result.answerRelevancy(), 1e-9);
        assertEquals(List.of(1, 3), result.citedIndices());
        assertEquals("都能在资料中找到依据", result.reason());
    }

    @Test
    @DisplayName("带解释文字与代码块标记时仍能取出 JSON")
    void parsesJsonWrappedInProseAndFence() {
        String raw = "好的，我的评审结果如下：\n```json\n{\"faithfulness\": 1.0, "
                + "\"answer_relevancy\": 0.5, \"cited_context_indices\": [2]}\n```\n希望有帮助。";
        RagGenerationJudgeTest.JudgeResult result = judge.parseJudge(raw, 2);

        assertTrue(result.parsed());
        assertEquals(1.0, result.faithfulness(), 1e-9);
        assertEquals(0.5, result.answerRelevancy(), 1e-9);
        assertEquals(List.of(2), result.citedIndices());
    }

    @Test
    @DisplayName("越界与非正数引用编号被过滤，避免映射到不存在的上下文")
    void filtersOutOfRangeCitations() {
        String raw = "{\"faithfulness\": 0.6, \"answer_relevancy\": 0.6, "
                + "\"cited_context_indices\": [0, 2, 5, -1]}";
        RagGenerationJudgeTest.JudgeResult result = judge.parseJudge(raw, 3);

        assertEquals(List.of(2), result.citedIndices());
    }

    @Test
    @DisplayName("分数被裁剪到 [0,1]，非数值分数记为 null 但不影响整体解析成功")
    void clampsScoresAndToleratesNonNumeric() {
        RagGenerationJudgeTest.JudgeResult high = judge.parseJudge(
                "{\"faithfulness\": 1.4, \"answer_relevancy\": -0.2}", 1);
        assertEquals(1.0, high.faithfulness(), 1e-9);
        assertEquals(0.0, high.answerRelevancy(), 1e-9);
        assertTrue(high.parsed());

        RagGenerationJudgeTest.JudgeResult mixed = judge.parseJudge(
                "{\"faithfulness\": \"很高\", \"answer_relevancy\": 0.4}", 1);
        assertNull(mixed.faithfulness());
        assertEquals(0.4, mixed.answerRelevancy(), 1e-9);
        assertTrue(mixed.parsed());
    }

    @Test
    @DisplayName("完全没有 JSON 时标记为未解析，而不是抛出异常中断评测")
    void toleratesMalformedOutput() {
        RagGenerationJudgeTest.JudgeResult result = judge.parseJudge("抱歉，我无法完成评审。", 3);

        assertFalse(result.parsed());
        assertNull(result.faithfulness());
        assertNull(result.answerRelevancy());
        assertTrue(result.citedIndices().isEmpty());
        assertFalse(result.reason().isBlank());
    }

    @Test
    @DisplayName("引用字段缺失或类型错误时视为未引用任何资料")
    void toleratesMissingCitationField() {
        RagGenerationJudgeTest.JudgeResult result = judge.parseJudge(
                "{\"faithfulness\": 0.5, \"answer_relevancy\": 0.5, \"cited_context_indices\": \"1,2\"}", 2);

        assertTrue(result.parsed());
        assertTrue(result.citedIndices().isEmpty());
    }

    @Test
    @DisplayName("上下文渲染：编号从 1 开始、带 docId、折叠空白")
    void rendersNumberedContexts() {
        List<KnowledgeChunkDTO> contexts = List.of(
                KnowledgeChunkDTO.builder().sourceId("doc-a").content("第一段\n\n内容   有空白").build(),
                KnowledgeChunkDTO.builder().sourceId("doc-b").content("第二段内容").build());

        String rendered = RagGenerationJudgeTest.renderContexts(contexts);

        assertTrue(rendered.contains("[1] (doc: doc-a)"), rendered);
        assertTrue(rendered.contains("[2] (doc: doc-b)"), rendered);
        assertTrue(rendered.contains("第一段 内容 有空白"), rendered);
    }

    @Test
    @DisplayName("上下文渲染：超长内容被截断，避免 prompt 被单条片段撑爆")
    void truncatesLongContext() {
        String longContent = "字".repeat(1200);
        List<KnowledgeChunkDTO> contexts = List.of(
                KnowledgeChunkDTO.builder().sourceId("doc-long").content(longContent).build());

        String rendered = RagGenerationJudgeTest.renderContexts(contexts);

        assertTrue(rendered.contains("字".repeat(700) + "…"));
        assertFalse(rendered.contains("字".repeat(701)));
    }
}
