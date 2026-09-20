package com.example.genwriter.retrieval.gate;

/**
 * 门控决策结果，用于探针与日志归因。
 *
 * @param pass         是否通过（通过才允许注入）
 * @param topScore     最高分，用于绝对阈值判定
 * @param margin       top1 与第 k 名的间隔，用于相对置信度判定（只有一个候选时为 Double.NaN）
 * @param rejectReason 归因：EMPTY / BELOW_MIN_SCORE / MARGIN_TOO_SMALL；通过时为 null
 */
public record GateDecision(boolean pass,
                           double topScore,
                           double margin,
                           String rejectReason) {

    public static final String REASON_EMPTY = "EMPTY";
    public static final String REASON_BELOW_MIN_SCORE = "BELOW_MIN_SCORE";
    public static final String REASON_MARGIN_TOO_SMALL = "MARGIN_TOO_SMALL";

    public static GateDecision pass(double topScore, double margin) {
        return new GateDecision(true, topScore, margin, null);
    }

    public static GateDecision reject(double topScore, double margin, String reason) {
        return new GateDecision(false, topScore, margin, reason);
    }
}
