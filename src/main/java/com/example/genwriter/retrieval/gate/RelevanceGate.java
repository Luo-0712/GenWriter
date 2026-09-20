package com.example.genwriter.retrieval.gate;

import java.util.List;

/**
 * 统一相关性闸门：评估有序分数列表并返回注入决策。
 *
 * <p>语义（两侧统一）：「top1 绝对分达标」AND「top1 与第 k 名相对间隔达标」，
 * 任一条不满足即整段不注入。
 */
public interface RelevanceGate {

    /**
     * @param sortedScoresDesc 按分数降序排列的最终候选分数（精排分数或融合分）
     */
    GateDecision evaluate(List<Double> sortedScoresDesc);
}
