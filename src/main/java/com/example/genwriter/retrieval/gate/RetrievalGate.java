package com.example.genwriter.retrieval.gate;

import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 默认门控实现：绝对阈值 + 相对间隔双条件。
 *
 * <p>单阈值已被评测证伪（阈值过低噪声率高，过高 Hit@K 掉档），
 * 因此采用双条件：既看绝对分数（问值），也看 top1 与第 k 名的间隔（排序置信度）。
 */
@RequiredArgsConstructor
public class RetrievalGate implements RelevanceGate {

    private final RetrievalGateProperties properties;

    @Override
    public GateDecision evaluate(List<Double> sortedScoresDesc) {
        if (!properties.isEnabled()) {
            return GateDecision.pass(
                    sortedScoresDesc == null || sortedScoresDesc.isEmpty() ? 0.0 : sortedScoresDesc.get(0),
                    Double.NaN);
        }
        if (sortedScoresDesc == null || sortedScoresDesc.isEmpty()) {
            return GateDecision.reject(0.0, Double.NaN, GateDecision.REASON_EMPTY);
        }

        double topScore = sortedScoresDesc.get(0);
        if (topScore < properties.getMinTopScore()) {
            return GateDecision.reject(topScore, Double.NaN, GateDecision.REASON_BELOW_MIN_SCORE);
        }

        // 单候选没有可比对象，间隔判定跳过
        if (sortedScoresDesc.size() == 1) {
            return GateDecision.pass(topScore, Double.NaN);
        }

        int marginIdx = Math.min(Math.max(properties.getMarginIndex(), 1), sortedScoresDesc.size()) - 1;
        double kthScore = sortedScoresDesc.get(marginIdx);
        double margin = topScore - kthScore;
        if (margin < properties.getMinMargin()) {
            return GateDecision.reject(topScore, margin, GateDecision.REASON_MARGIN_TOO_SMALL);
        }
        return GateDecision.pass(topScore, margin);
    }
}
