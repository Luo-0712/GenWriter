package com.example.genwriter.util;

import java.util.List;

/**
 * 向量工具类
 */
public class VectorUtils {

    /**
     * 计算两个向量之间的余弦相似度
     */
    public static double cosineSimilarity(float[] vecA, float[] vecB) {
        if (vecA == null || vecB == null || vecA.length != vecB.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += Math.pow(vecA[i], 2);
            normB += Math.pow(vecB[i], 2);
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 计算欧几里得距离
     */
    public static float euclideanDistance(float[] vecA, float[] vecB) {
        if (vecA == null || vecB == null || vecA.length != vecB.length) {
            return Float.MAX_VALUE;
        }

        float sum = 0.0f;
        for (int i = 0; i < vecA.length; i++) {
            float diff = vecA[i] - vecB[i];
            sum += diff * diff;
        }

        return (float) Math.sqrt(sum);
    }

    /**
     * 批量计算查询向量与文档向量的相似度
     */
    public static float[] computeSimilarities(float[] query, List<float[]> documentVectors) {
        float[] similarities = new float[documentVectors.size()];
        for (int i = 0; i < documentVectors.size(); i++) {
            similarities[i] = (float) cosineSimilarity(query, documentVectors.get(i));
        }
        return similarities;
    }

    /**
     * 归一化向量
     */
    public static float[] normalize(float[] vector) {
        float magnitude = 0.0f;
        for (float v : vector) {
            magnitude += v * v;
        }
        magnitude = (float) Math.sqrt(magnitude);

        if (magnitude == 0.0f) {
            return new float[vector.length];
        }

        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / magnitude;
        }
        return normalized;
    }

    /**
     * 将向量字符串转换为float数组
     */
    public static float[] vectorStringToArray(String vectorString) {
        if (vectorString == null || vectorString.isEmpty()) {
            return new float[0];
        }

        // 移除方括号
        String content = vectorString.replaceAll("[\\[\\]]", "");
        String[] parts = content.split(",");
        float[] vector = new float[parts.length];

        try {
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Float.parseFloat(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("向量格式错误: " + vectorString);
        }

        return vector;
    }

    /**
     * 将float数组转换为向量字符串
     */
    public static String arrayToVectorString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");

        return sb.toString();
    }
}