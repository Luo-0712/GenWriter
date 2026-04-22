package com.example.genwriter.rag.chunking;

import com.example.genwriter.model.dto.DocumentChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按文档结构切分：优先以 Markdown 标题（#/##/### 等）为边界，
 * 无标题时退化为按空行分段。超出 chunkSize 的节再按字符截断；
 * 过小的节向后合并，直到满足最小粒度。
 */
public class StructuralChunkStrategy implements DocumentChunkStrategy {

    // 匹配行首的 Markdown 标题（1-6 级）
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^(#{1,6})\\s+.+$");

    @Override
    public List<DocumentChunk> chunk(String text, ChunkingConfig config) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<String> sections = splitIntoSections(text);
        List<String> merged = mergeSections(sections, config.getChunkSize());
        List<String> finalChunks = splitOversized(merged, config.getChunkSize(), config.getChunkOverlap());

        return buildChunks(finalChunks, text);
    }

    @Override
    public String getStrategyName() {
        return "structural";
    }

    // -------------------------------------------------------------------------

    private List<String> splitIntoSections(String text) {
        Matcher matcher = HEADING_PATTERN.matcher(text);
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);

        while (matcher.find()) {
            if (matcher.start() > 0) {
                boundaries.add(matcher.start());
            }
        }
        boundaries.add(text.length());

        // 如果没有找到任何标题，按空行分段
        if (boundaries.size() == 2) {
            return splitByBlankLine(text);
        }

        List<String> sections = new ArrayList<>();
        for (int i = 0; i < boundaries.size() - 1; i++) {
            String section = text.substring(boundaries.get(i), boundaries.get(i + 1)).strip();
            if (!section.isEmpty()) {
                sections.add(section);
            }
        }
        return sections;
    }

    private List<String> splitByBlankLine(String text) {
        List<String> sections = new ArrayList<>();
        String[] parts = text.split("\\n{2,}");
        for (String part : parts) {
            String stripped = part.strip();
            if (!stripped.isEmpty()) {
                sections.add(stripped);
            }
        }
        return sections.isEmpty() ? List.of(text.strip()) : sections;
    }

    /** 将过小的节向后合并，避免碎片化 chunk */
    private List<String> mergeSections(List<String> sections, int chunkSize) {
        int minSize = chunkSize / 4;
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String section : sections) {
            if (buffer.length() > 0 && buffer.length() + section.length() + 1 > chunkSize) {
                result.add(buffer.toString().strip());
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            buffer.append(section);

            if (buffer.length() >= minSize) {
                result.add(buffer.toString().strip());
                buffer.setLength(0);
            }
        }

        if (buffer.length() > 0) {
            result.add(buffer.toString().strip());
        }
        return result;
    }

    /** 超出 chunkSize 的节按字符截断，带 overlap */
    private List<String> splitOversized(List<String> sections, int chunkSize, int overlap) {
        List<String> result = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= chunkSize) {
                result.add(section);
            } else {
                int start = 0;
                while (start < section.length()) {
                    int end = Math.min(start + chunkSize, section.length());
                    result.add(section.substring(start, end));
                    start = end - overlap;
                    if (start >= end) break;
                }
            }
        }
        return result;
    }

    private List<DocumentChunk> buildChunks(List<String> chunkTexts, String originalText) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int searchPos = 0;
        for (int i = 0; i < chunkTexts.size(); i++) {
            String content = chunkTexts.get(i);
            int startOffset = originalText.indexOf(content, searchPos);
            if (startOffset == -1) {
                startOffset = originalText.indexOf(content);
            }
            if (startOffset == -1) {
                startOffset = searchPos;
            }
            int endOffset = startOffset + content.length();
            searchPos = endOffset;

            chunks.add(DocumentChunk.builder()
                    .index(i)
                    .content(content)
                    .startOffset(startOffset)
                    .endOffset(endOffset)
                    .tokenCount((int) Math.ceil(content.length() / 4.0))
                    .build());
        }
        return chunks;
    }
}
