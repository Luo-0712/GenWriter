package com.example.genwriter.agent.graph.node;

import com.example.genwriter.agent.graph.node.SupervisorNode.ContentPhase;
import com.example.genwriter.agent.react.ReactStep;
import com.example.genwriter.agent.supervisor.worker.ReviewWorker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
