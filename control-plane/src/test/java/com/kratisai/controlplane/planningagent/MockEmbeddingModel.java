package com.kratisai.controlplane.planningagent;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

public class MockEmbeddingModel implements EmbeddingModel {

    private boolean throwException;
    private String lastModelNameOverride;

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        if (throwException) {
            throw new RuntimeException("Mock embedding exception triggered");
        }
        // Return a deterministic float array based on the text length or just a dummy array
        float val = text != null ? text.length() / 100.0f : 0.0f;
        return new float[] {0.1f + val, 0.2f + val};
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (throwException) {
            throw new RuntimeException("Mock embedding exception triggered");
        }
        List<Embedding> embeddings = request.getInstructions().stream()
                .map(instruction -> new Embedding(embed(instruction), 0))
                .collect(Collectors.toList());
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public int dimensions() {
        return 2;
    }

    public void setThrowException(boolean throwException) {
        this.throwException = throwException;
    }

    public void setLastModelNameOverride(String lastModelNameOverride) {
        this.lastModelNameOverride = lastModelNameOverride;
    }

    public String getLastModelNameOverride() {
        return lastModelNameOverride;
    }
}
