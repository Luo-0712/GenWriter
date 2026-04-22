package com.example.genwriter.rag.chunking;

import com.example.genwriter.model.dto.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecursiveChunkStrategyTest {

    private RecursiveChunkStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new RecursiveChunkStrategy();
    }

    @Test
    void testChunkWithParagraphs() {
        String text = "Paragraph 1 content.\n\nParagraph 2 content.\n\nParagraph 3 content.";
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(50)
                .chunkOverlap(5)
                .build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 1);

        for (DocumentChunk chunk : chunks) {
            assertNotNull(chunk.getContent());
            assertTrue(chunk.getContent().length() <= 50);
        }
    }

    @Test
    void testChunkWithSentences() {
        String text = "First sentence. Second sentence. Third sentence. Fourth sentence.";
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(30)
                .chunkOverlap(5)
                .build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2);
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
        assertEquals("recursive", strategy.getStrategyName());
    }

    @Test
    void testChunkWithChineseText() {
        String text = "这是第一段内容。这是第二段内容。这是第三段内容。";
        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(20)
                .chunkOverlap(5)
                .build();

        List<DocumentChunk> chunks = strategy.chunk(text, config);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 1);
    }
}
