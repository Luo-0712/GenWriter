package com.example.genwriter.rag.chunking;

import com.example.genwriter.model.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuralChunkStrategyTest {

    private StructuralChunkStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new StructuralChunkStrategy();
    }

    @Test
    void testGetStrategyName() {
        assertEquals("structural", strategy.getStrategyName());
    }

    @Test
    void testEmptyText() {
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(500).chunkOverlap(50).build();
        assertTrue(strategy.chunk("", config).isEmpty());
    }

    @Test
    void testMarkdownHeadings() {
        String text = """
                # 第一章 引言
                这是引言内容，介绍了背景和目的。

                ## 1.1 背景
                详细描述背景信息。

                ## 1.2 目的
                描述本文的目的。

                # 第二章 方法
                这里描述研究方法。
                """;

        ChunkingConfig config = ChunkingConfig.builder().chunkSize(500).chunkOverlap(50).build();
        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        // 每个 chunk 应包含对应标题
        boolean hasChapter1 = chunks.stream().anyMatch(c -> c.getContent().contains("第一章"));
        boolean hasChapter2 = chunks.stream().anyMatch(c -> c.getContent().contains("第二章"));
        assertTrue(hasChapter1);
        assertTrue(hasChapter2);
    }

    @Test
    void testFallbackToBlankLineSplit() {
        String text = "第一段内容，讲述了基本概念。\n\n第二段内容，深入分析了细节。\n\n第三段内容，总结了全文。";
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(500).chunkOverlap(50).build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        // 应按空行分成多段后合并
        String joined = chunks.stream().map(DocumentChunk::getContent).reduce("", (a, b) -> a + b);
        assertTrue(joined.contains("第一段内容"));
        assertTrue(joined.contains("第三段内容"));
    }

    @Test
    void testOversizedSectionSplitWithOverlap() {
        // 单节超过 chunkSize，应被再次切分
        String longSection = "# 超长章节\n" + "内容".repeat(300);
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(200).chunkOverlap(20).build();

        List<DocumentChunk> chunks = strategy.chunk(longSection, config);

        assertTrue(chunks.size() > 1);
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.getContent().length() <= 200);
        }
    }

    @Test
    void testIndexAndOffsets() {
        String text = "# 章节一\n内容A\n\n# 章节二\n内容B";
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(500).chunkOverlap(0).build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getIndex());
            assertTrue(chunks.get(i).getStartOffset() >= 0);
            assertTrue(chunks.get(i).getEndOffset() > chunks.get(i).getStartOffset());
            assertTrue(chunks.get(i).getTokenCount() > 0);
        }
    }

    @Test
    void testSmallDocumentSingleChunk() {
        String text = "# 简介\n这是一段简短的说明。";
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(500).chunkOverlap(50).build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertEquals(1, chunks.size());
        assertEquals(text.strip(), chunks.get(0).getContent());
    }

    @Test
    void testMixedHeadingLevels() {
        String text = """
                # H1 标题
                H1 正文

                ## H2 标题
                H2 正文

                ### H3 标题
                H3 正文
                """;
        ChunkingConfig config = ChunkingConfig.builder().chunkSize(1000).chunkOverlap(0).build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        String all = chunks.stream().map(DocumentChunk::getContent).reduce("", String::concat);
        assertTrue(all.contains("H1 标题"));
        assertTrue(all.contains("H2 标题"));
        assertTrue(all.contains("H3 标题"));
    }
}
