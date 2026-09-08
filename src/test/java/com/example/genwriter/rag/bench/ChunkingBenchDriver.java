package com.example.genwriter.rag.bench;

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
import java.util.List;
import java.util.Random;

/**
 * chunking 切割层 CPU 吞吐压测。
 *
 * 合成写作语料：按真实中文小说排版生成 10KB / 100KB / 1MB 三档文本，
 * 每种策略各跑 30 轮取中位数与分位，输出 P50/P90/P99 与吞吐（MB/s）。
 * 独立 main 程序，不依赖 Spring 与数据库。
 */
public class ChunkingBenchDriver {

    static final int[] SIZES = {10 * 1024, 100 * 1024, 1024 * 1024};
    static final int ROUNDS = 30;

    public static void main(String[] args) throws IOException {
        System.out.println("== chunking 切割层压测 ==");
        List<Result> results = new ArrayList<>();

        for (int size : SIZES) {
            String text = synthesizeCorpus(size);
            for (DocumentChunkStrategy strategy : List.of(
                    new FixedSizeChunkStrategy(), new RecursiveChunkStrategy(), new StructuralChunkStrategy())) {
                results.add(bench(strategy, text, size));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# chunking 切割层性能压测报告\n\n");
        sb.append("- 时间: ").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("- 语料: 合成中文写作语料（Markdown 结构），chunkSize=1000, overlap=100\n");
        sb.append("- 每组合 30 轮取延迟分位\n\n");
        sb.append("| 语料大小 | 策略 | 平均 chunk 数 | P50(ms) | P90(ms) | P99(ms) | 吞吐(MB/s) |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            sb.append(String.format("| %s | %s | %.0f | %.2f | %.2f | %.2f | %.1f |%n",
                    humanSize(r.inputSize), r.strategy, r.avgChunks, r.p50, r.p90, r.p99, r.throughput));
            System.out.printf("%s %s: p50=%.2fms p99=%.2fms throughput=%.1fMB/s chunks=%.0f%n",
                    humanSize(r.inputSize), r.strategy, r.p50, r.p99, r.throughput, r.avgChunks);
        }

        Path out = Path.of(System.getProperty("user.home"), "Desktop", "八股速记", "项目改进",
                "chunking性能压测报告-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("\n[DONE] 报告已写入: " + out);
    }

    record Result(String strategy, int inputSize, double avgChunks, double p50, double p90, double p99, double throughput) {}

    static Result bench(DocumentChunkStrategy strategy, String text, int size) {
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(1000).chunkOverlap(100).build();
        List<Long> latencies = new ArrayList<>();
        double chunkSum = 0;
        for (int i = 0; i < ROUNDS; i++) {
            long t0 = System.nanoTime();
            List<DocumentChunk> chunks = strategy.chunk(text, config);
            long cost = System.nanoTime() - t0;
            if (i >= 5) { // 前 5 轮为 JIT 预热
                latencies.add(cost);
                chunkSum += chunks.size();
            }
        }
        List<Long> sorted = new ArrayList<>(latencies);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        double p50 = sorted.get((int) (n * 0.50)) / 1e6;
        double p90 = sorted.get((int) (n * 0.90)) / 1e6;
        double p99 = sorted.get(Math.min(n - 1, (int) (n * 0.99))) / 1e6;
        double avgChunks = chunkSum / n;
        double mbPerMs = (size / 1024.0 / 1024.0) / p50;
        return new Result(strategy.getStrategyName(), size, avgChunks, p50, p90, p99, mbPerMs * 1000);
    }

    /** 生成带 Markdown 结构的合成中文写作语料：8 章 × 若干节，每节 3-6 段 */
    static String synthesizeCorpus(int targetSize) {
        Random rnd = new Random(targetSize);
        String[] sentences = {
                "写作是一种将思想凝结为文字的技艺，需要长期的练习与积累。",
                "人物的核心欲望驱动着情节的走向，显性欲望与隐性欲望的张力构成故事深度。",
                "对话中的潜台词比直白的表述更有力量，人物说出口的往往不是心里想的。",
                "场景描写应当服务于叙事，环境的变化映射着人物命运的起伏。",
                "悬念的本质是向读者许诺一个尚未兑现的答案，公平原则不可违背。",
                "修订从宏观到微观分层进行，先结构后语句，顺序不可颠倒。",
                "节奏的控制依赖句式长短的交替，短句收束长段的惯性滑动。",
                "素材库的意义在于被使用而不是被收藏，定期回顾让素材相互碰撞。",
                "叙述距离的动态调节是资深写作者的标志性技能，远近切换制造冲击。",
                "伏笔台账管理每一个埋设与回收，确保没有烂尾的承诺。"
        };
        StringBuilder sb = new StringBuilder(targetSize + 4096);
        int chapter = 1;
        while (sb.length() < targetSize) {
            sb.append("# 第").append(chapter++).append("章 合成测试章节\n\n");
            for (int sec = 1; sec <= 4; sec++) {
                sb.append("## ").append(chapter - 1).append(".").append(sec).append(" 合成小节\n\n");
                int paras = 3 + rnd.nextInt(4);
                for (int p = 0; p < paras && sb.length() < targetSize; p++) {
                    int sents = 4 + rnd.nextInt(4);
                    for (int s = 0; s < sents; s++) {
                        sb.append(sentences[rnd.nextInt(sentences.length)]);
                    }
                    sb.append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    static String humanSize(int bytes) {
        return bytes >= 1024 * 1024 ? String.format("%.0fMB", bytes / 1024.0 / 1024.0)
                : String.format("%dKB", bytes / 1024);
    }
}
