package com.kratisai.controlplane.planningagent;

import java.util.List;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.augment.ToolInputSchemaAugmenter;

public record AgentThinking(
        @ToolParam(description = "Your reasoning for calling this tool (max 10 words)")
        String innerThought) {

    public static List<ToolInputSchemaAugmenter.AugmentedArgumentType> argumentTypes() {
        return ToolInputSchemaAugmenter.toAugmentedArgumentTypes(AgentThinking.class);
    }
}
