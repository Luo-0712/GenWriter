package com.example.genwriter.service;

import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识导入服务 - 异步处理文件上传并入库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeImportService {

    private final RAGPipelineService ragPipelineService;
    private final SseService sseService;

    @Async("taskExecutor")
    public void processImportAsync(String taskId, String filePath, String kbId, String strategy) {
        log.info("Async import started: taskId={}, filePath={}, kbId={}", taskId, filePath, kbId);

        try {
            publishProgress(taskId, "UPLOADED", "File uploaded successfully, starting processing...");

            String actualStrategy = strategy != null ? strategy : "recursive";
            publishProgress(taskId, "PARSING", "Parsing document content...");

            List<KnowledgeChunkDTO> chunks = ragPipelineService.processDocument(filePath, kbId, actualStrategy);

            publishProgress(taskId, "COMPLETED",
                    "Import completed. Total chunks: " + chunks.size());

            sseService.complete(taskId);
            log.info("Async import completed: taskId={}, chunks={}", taskId, chunks.size());

        } catch (Exception e) {
            log.error("Async import failed: taskId={}", taskId, e);
            publishError(taskId, "Import failed: " + e.getMessage());
        }
    }

    private void publishProgress(String taskId, String stage, String message) {
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_EXECUTING)
                .payload(SseMessage.Payload.builder()
                        .data(new ImportProgress(stage, message))
                        .statusText(message)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .resourceId(taskId)
                        .build())
                .build();
        sseService.publish(taskId, sseMessage);
    }

    private void publishError(String taskId, String errorMessage) {
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.ERROR)
                .payload(SseMessage.Payload.builder()
                        .data(new ImportProgress("FAILED", errorMessage))
                        .statusText(errorMessage)
                        .done(true)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .resourceId(taskId)
                        .build())
                .build();
        sseService.publish(taskId, sseMessage);
        sseService.complete(taskId);
    }

    public record ImportProgress(String stage, String message) {}
}
