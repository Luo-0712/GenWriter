package com.example.genwriter.rag.chunking;

import com.example.genwriter.model.dto.DocumentChunk;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

class ChunkingAdapter {

    static List<DocumentChunk> toDocumentChunks(List<TextSegment> segments, String originalText) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int searchPos = 0;

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            String content = segment.text();

            int startOffset = findStartOffset(originalText, content, searchPos);
            int endOffset = startOffset + content.length();
            searchPos = endOffset;

            chunks.add(DocumentChunk.builder()
                    .index(i)
                    .content(content)
                    .startOffset(startOffset)
                    .endOffset(endOffset)
                    .tokenCount(estimateTokenCount(content))
                    .build());
        }

        return chunks;
    }

    private static int findStartOffset(String originalText, String content, int fromIndex) {
        int idx = originalText.indexOf(content, fromIndex);
        if (idx != -1) {
            return idx;
        }
        idx = originalText.indexOf(content);
        return idx != -1 ? idx : fromIndex;
    }

    private static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 4.0);
    }
}
