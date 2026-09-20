package com.example.genwriter.rag.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalEnvProbeTest {

    @Autowired
    private Environment env;

    @Test
    void printConfig() {
        System.out.println("[PROBE] datasource.url=" + env.getProperty("spring.datasource.url"));
        System.out.println("[PROBE] datasource.driver=" + env.getProperty("spring.datasource.driver-class-name"));
        System.out.println("[PROBE] dashscope.chat.enabled=" + env.getProperty("spring.ai.alibaba.dashscope.chat.enabled"));
        System.out.println("[PROBE] dashscope.embedding.enabled="
                + env.getProperty("spring.ai.alibaba.dashscope.embedding.enabled"));
        System.out.println("[PROBE] dashscope.api-key=" + env.getProperty("spring.ai.alibaba.dashscope.api-key"));
        System.out.println("[PROBE] genwriter.embedding.default-model="
                + env.getProperty("genwriter.embedding.default-model"));
        System.out.println("[PROBE] genwriter.embedding.dimension=" + env.getProperty("genwriter.embedding.dimension"));
        System.out.println("[PROBE] retrieval.rerank.enabled=" + env.getProperty("retrieval.rerank.enabled"));
        System.out.println("[PROBE] profiles=" + String.join(",", env.getActiveProfiles()));
    }
}
