package com.example.genwriter.rag.chunking;

import com.example.genwriter.model.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FixedSizeChunkStrategyTest {

    private FixedSizeChunkStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FixedSizeChunkStrategy();
    }

    @Test
    void testChunkWithNormalText() {
        String text = "A".repeat(2500);
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(1000)
                .chunkOverlap(100)
                .build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        assertEquals(1000, chunks.get(0).getContent().length());
        assertTrue(chunks.size() >= 3);

        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getIndex());
            assertNotNull(chunks.get(i).getContent());
            assertTrue(chunks.get(i).getTokenCount() > 0);
        }
    }

    @Test
    void testChunkWithOverlap() {
        String text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(10)
                .chunkOverlap(3)
                .build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        assertEquals("ABCDEFGHIJ", chunks.get(0).getContent());
        // all characters should be covered by chunks
        StringBuilder reconstructed = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            reconstructed.append(chunk.getContent());
        }
        assertTrue(reconstructed.toString().contains(text),
                "All original text should be covered by chunks");
    }

    @Test
    void testChunkWithEmptyText() {
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(100)
                .chunkOverlap(10)
                .build();

        List<DocumentChunk> chunks = strategy.chunk("", config);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testChunkWithSmallText() {
        String text = "Small text";
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(100)
                .chunkOverlap(10)
                .build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertEquals(1, chunks.size());
        assertEquals("Small text", chunks.get(0).getContent());
    }

    @Test
    void testGetStrategyName() {
        assertEquals("fixed_size", strategy.getStrategyName());
    }

    @Test
    void testOffsetsAreCorrect() {
        String text = "01234567890123456789";
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(10)
                .chunkOverlap(0)
                .build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertEquals(0, chunks.get(0).getStartOffset());
        assertEquals(10, chunks.get(0).getEndOffset());
        assertEquals(10, chunks.get(1).getStartOffset());
        assertEquals(20, chunks.get(1).getEndOffset());
    }

    @Test
    void testChunkMarkdownFile() throws IOException {
        // 读取markdown文件
        Path markdownPath = Path.of("e:\\JavaProjects\\GenWriter\\temp\\Spring AI Alibaba RAG最佳实践.md");
        String markdownContent = Files.readString(markdownPath);
        
        System.out.println("========================================");
        System.out.println("文档切分测试 - Spring AI Alibaba RAG最佳实践.md");
        System.out.println("========================================");
        System.out.println("原始文档大小: " + markdownContent.length() + " 字符");
        
        // 配置切分参数: 每块1000字符,重叠200字符
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(1000)
                .chunkOverlap(200)
                .build();
        
        // 执行切分
        long startTime = System.currentTimeMillis();
        List<DocumentChunk> chunks = strategy.chunk(markdownContent, config);
        long endTime = System.currentTimeMillis();
        
        // 打印测试结果到console
        System.out.println("切分策略: " + strategy.getStrategyName());
        System.out.println("配置: chunkSize=" + config.getChunkSize() + ", overlap=" + config.getChunkOverlap());
        System.out.println("切分耗时: " + (endTime - startTime) + " ms");
        System.out.println("生成块数量: " + chunks.size());
        System.out.println("========================================");
        System.out.println();
        
        // 打印每个块的详细信息
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            System.out.println("--- 块 #" + (i + 1) + " ---");
            System.out.println("索引: " + chunk.getIndex());
            System.out.println("字符范围: [" + chunk.getStartOffset() + " - " + chunk.getEndOffset() + ")");
            System.out.println("字符长度: " + chunk.getContent().length());
            System.out.println("估算Token数: " + chunk.getTokenCount());
            System.out.println("内容预览: " + chunk.getContent().substring(0, Math.min(150, chunk.getContent().length())).replace("\n", "\\n"));
            System.out.println();
        }
        
        // 基本断言验证
        assertFalse(chunks.isEmpty(), "切分结果不应为空");
        assertTrue(chunks.size() >= 5, "文档应该被切分成至少5个块");
        
        // 验证块大小符合配置(第一个块)
        assertTrue(chunks.get(0).getContent().length() <= config.getChunkSize(),
                "块大小不应超过配置的chunkSize");
        
        // 验证索引连续性
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getIndex(), "索引应连续");
        }
        
        // 验证所有字符都被覆盖
        StringBuilder reconstructed = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            reconstructed.append(chunk.getContent());
        }
        assertTrue(reconstructed.length() >= markdownContent.length(),
                "切分后的内容应该覆盖原文档");
        
        System.out.println("========================================");
        System.out.println("测试通过! 所有断言验证成功");
        System.out.println("========================================");
    }
}
