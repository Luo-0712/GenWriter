package com.example.genwriter.rag.chunking;

import com.example.genwriter.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class DocumentChunkStrategyFactory {

    private final Map<String, DocumentChunkStrategy> strategies = new HashMap<>();

    public DocumentChunkStrategyFactory() {
        registerStrategy(new FixedSizeChunkStrategy());
        registerStrategy(new RecursiveChunkStrategy());
        registerStrategy(new StructuralChunkStrategy());
    }

    private void registerStrategy(DocumentChunkStrategy strategy) {
        strategies.put(strategy.getStrategyName(), strategy);
    }

    public DocumentChunkStrategy getStrategy(String strategyName) {
        DocumentChunkStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new BizException("INVALID_STRATEGY", "Unsupported chunking strategy: " + strategyName +
                    ". Available strategies: " + String.join(", ", strategies.keySet()));
        }
        return strategy;
    }

    public Map<String, DocumentChunkStrategy> getAllStrategies() {
        return Map.copyOf(strategies);
    }

    public boolean hasStrategy(String strategyName) {
        return strategies.containsKey(strategyName);
    }
}
