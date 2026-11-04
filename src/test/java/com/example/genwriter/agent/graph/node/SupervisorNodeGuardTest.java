package com.example.genwriter.agent.graph.node;

import com.example.genwriter.agent.graph.node.SupervisorNode.ContentPhase;
import com.example.genwriter.agent.react.ReactStep;
import com.example.genwriter.agent.supervisor.worker.ReviewWorker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SupervisorNode 内容流水线硬护栏（{@link SupervisorNode#forcedContentStep}）的纯逻辑单测。
 *
 * <p>护栏不变式：draft 之后必须先 polish（ReviewWorker 只评审 polishedContent，draft 一变
 * 润色稿即过期）；polish 之后必须先 review；评审额度耗尽后以最新润色稿定稿。
 */
class SupervisorNodeGuardTest {

    @Test
    void forcesIntentRecognitionFirstRegardlessOfPhase() {
        for (ContentPhase phase : ContentPhase.values()) {
            ReactStep step = SupervisorNode.forcedContentStep(false, phase, 0);
            assertEquals("intent_recognition", step.action());
        }
    }

    @Test
    void idleLeavesDecisionToLlm() {
        assertNull(SupervisorNode.forcedContentStep(true, ContentPhase.IDLE, 0));
    }

    @Test
    void forcesPolishAfterDraft() {
        ReactStep step = SupervisorNode.forcedContentStep(true, ContentPhase.POLISH_AFTER_DRAFT, 9);

        assertEquals("polish", step.action());
    }

    @Test
    void forcesReviewAfterPolishWithinRoundBudget() {
        for (int round = 0; round < ReviewWorker.MAX_REVIEW_ROUNDS; round++) {
            ReactStep step = SupervisorNode.forcedContentStep(true, ContentPhase.REVIEW_AFTER_POLISH, round);
            assertEquals("review", step.action());
        }
    }

    @Test
    void finishesWhenReviewBudgetExhaustedAfterPolish() {
        ReactStep step = SupervisorNode.forcedContentStep(
                true, ContentPhase.REVIEW_AFTER_POLISH, ReviewWorker.MAX_REVIEW_ROUNDS);

        assertTrue(step.isFinish());
    }

    @Test
    void staleReviewCountsDoNotChangeForcedActions() {
        // 额度耗尽只影响 polish 之后的衔接：draft 修订即使超额度也必须先 polish 刷新内容再定稿
        ReactStep afterDraft = SupervisorNode.forcedContentStep(
                true, ContentPhase.POLISH_AFTER_DRAFT, ReviewWorker.MAX_REVIEW_ROUNDS);
        ReactStep afterPolish = SupervisorNode.forcedContentStep(
                true, ContentPhase.REVIEW_AFTER_POLISH, ReviewWorker.MAX_REVIEW_ROUNDS);

        assertEquals("polish", afterDraft.action());
        assertTrue(afterPolish.isFinish());
    }

    /**
     * 护栏步的 reasoning 会作为轨迹摘要直接渲染给用户，因此只能是用户语言：
     * worker id、字段名、护栏术语都不得出现（技术原因走 {@code forcedContentStepDiagnostic} 与日志）。
     */
    @Test
    void forcedReasoningIsUserFacingCopy() {
        List<ReactStep> steps = List.of(
                SupervisorNode.forcedContentStep(false, ContentPhase.IDLE, 0),
                SupervisorNode.forcedContentStep(true, ContentPhase.POLISH_AFTER_DRAFT, 0),
                SupervisorNode.forcedContentStep(true, ContentPhase.REVIEW_AFTER_POLISH, 0),
                SupervisorNode.forcedContentStep(
                        true, ContentPhase.REVIEW_AFTER_POLISH, ReviewWorker.MAX_REVIEW_ROUNDS));

        List<String> forbidden = List.of("护栏", "强制", "ReviewWorker", "polishedContent",
                "intent_recognition", "reviewCount", "review", "polish", "draft");
        for (ReactStep step : steps) {
            assertNotNull(step, "护栏分支都应产出步骤");
            assertFalse(step.reasoning() == null || step.reasoning().isBlank());
            for (String word : forbidden) {
                assertFalse(step.reasoning().contains(word),
                        "用户可见的护栏文案不应出现内部术语「" + word + "」: " + step.reasoning());
            }
        }
    }

    /** 技术原因仍可通过诊断文案取到，保证排查能力没有随文案中性化一起丢掉。 */
    @Test
    void diagnosticKeepsTechnicalReason() {
        String afterDraft = SupervisorNode.forcedContentStepDiagnostic(
                true, ContentPhase.POLISH_AFTER_DRAFT, 0);

        assertTrue(afterDraft.contains("polish"));
        assertTrue(SupervisorNode.forcedContentStepDiagnostic(false, ContentPhase.IDLE, 0)
                .contains("intent_recognition"));
    }
}
