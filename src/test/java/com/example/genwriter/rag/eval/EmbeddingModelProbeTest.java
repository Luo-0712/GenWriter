package com.example.genwriter.rag.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实环境探针：验证 DashScope 向量模型可用性与输出维度。
 * 未设置 DASHSCOPE_API_KEY 时自动跳过。
 */
class EmbeddingModelProbeTest {

    private static final String MODELS_ENV = System.getenv("DASHSCOPE_API_KEY");

    private DashScopeEmbeddingModel buildModel(String model) {
        DashScopeApi api = new DashScopeApi(MODELS_ENV);
        return new DashScopeEmbeddingModel(api,
                org.springframework.ai.document.MetadataMode.EMBED,
                DashScopeEmbeddingOptions.builder()
                        .withModel(model)
                        .withTextType("document")
                        .build());
    }

    @Test
    void probeQwen37TextEmbeddingFlash() {
        assumeTrue(MODELS_ENV != null && !MODELS_ENV.isBlank(), "DASHSCOPE_API_KEY not set");
        DashScopeEmbeddingModel model = buildModel(RagEvalDataset.EMBEDDING_MODEL);
        try {
            EmbeddingResponse resp = model.call(new EmbeddingRequest(
                    List.of("写作系统的向量模型探针：主角在雨夜的便利店门口停下脚步。"), null));
            float[] vec = resp.getResults().get(0).getOutput();
            System.out.println("[PROBE] model=" + RagEvalDataset.EMBEDDING_MODEL
                    + " dim=" + vec.length
                    + " meta=" + resp.getMetadata());
            assertTrue(vec.length > 0, "embedding should not be empty");
        } catch (Exception e) {
            // key 失效等情况下跳过而非阻塞构建，错误信息保留在输出中
            System.out.println("[PROBE-SKIP] model=" + RagEvalDataset.EMBEDDING_MODEL
                    + " 调用失败: " + e.getMessage());
            assumeTrue(false, "Embedding API unavailable: " + e.getMessage());
        }
    }
}
