package com.example.genwriter.rag.chunking;

import com.example.genwriter.model.dto.DocumentChunk;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

public class RecursiveChunkStrategy implements DocumentChunkStrategy {

    @Override
    public List<DocumentChunk> chunk(String text, ChunkingConfig config) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        Document document = Document.from(text);
        DocumentSplitter splitter = DocumentSplitters.recursive(config.getChunkSize(), config.getChunkOverlap());
        List<TextSegment> segments = splitter.split(document);

        return ChunkingAdapter.toDocumentChunks(segments, text);
    }

    @Override
    public String getStrategyName() {
        return "recursive";
    }
}
