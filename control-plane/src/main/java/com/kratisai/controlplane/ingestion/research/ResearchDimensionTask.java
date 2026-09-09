package com.kratisai.controlplane.ingestion.research;

import com.kratisai.controlplane.model.CtxDimension;
import com.kratisai.controlplane.model.ModelProvider;
import java.util.UUID;

public record ResearchDimensionTask(UUID batchId, ModelProvider provider, String modelName, CtxDimension dimension) {}
