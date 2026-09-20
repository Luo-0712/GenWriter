package com.example.genwriter.retrieval;

import com.example.genwriter.retrieval.gate.RelevanceGate;
import com.example.genwriter.retrieval.gate.RetrievalGate;
import com.example.genwriter.retrieval.gate.RetrievalGateProperties;
import com.example.genwriter.retrieval.rerank.CrossEncoderReranker;
import com.example.genwriter.retrieval.rerank.NoOpReranker;
import com.example.genwriter.retrieval.rerank.RerankProperties;
import com.example.genwriter.retrieval.rerank.Reranker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索精排与门控装配。
 *
 * <p>rerank 默认关闭（NoOpReranker 兜底），开启需配置 retrieval.rerank.enabled=true 与 endpoint，
 * 保证新逻辑可整体回退。
 */
@Configuration
public class RetrievalConfig {

    @Bean
    @ConditionalOnProperty(prefix = "retrieval.rerank", name = "enabled", havingValue = "true")
    public Reranker crossEncoderReranker(CrossEncoderReranker crossEncoderReranker) {
        return crossEncoderReranker;
    }

    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    public Reranker noOpReranker() {
        return new NoOpReranker();
    }

    @Bean
    public RelevanceGate relevanceGate(RetrievalGateProperties properties) {
        return new RetrievalGate(properties);
    }
}
