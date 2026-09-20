package com.example.genwriter.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigProbeTest {

    @Autowired
    private Environment env;
    @Autowired
    private LLMConfig llmConfig;
    @Autowired
    private DynamicChatModel dynamicChatModel;

    @Test
    void probe() {
        System.out.println("[P] datasource=" + env.getProperty("spring.datasource.url"));
        System.out.println("[P] defaultProvider=" + llmConfig.getDefaultProvider()
                + " defaultModel=" + llmConfig.getDefaultModel());
        llmConfig.getProviders().forEach(p -> System.out.println("[P] provider=" + p.getType()
                + " baseUrl=" + p.getBaseUrl()
                + " compat=" + p.getApiCompatibility()
                + " models=" + p.getModels()
                + " active=" + p.getActiveModel()));
        System.out.println("[P] activeModelKey=" + dynamicChatModel.getActiveModel());
        System.out.println("[P] registered=" + dynamicChatModel.getRegisteredModels());
        String reply = ChatClient.builder(dynamicChatModel).build()
                .prompt().user("回复两个字：可用").call().content();
        System.out.println("[P] reply=" + reply);
    }
}
