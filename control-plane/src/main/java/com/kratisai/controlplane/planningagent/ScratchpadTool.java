package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.service.ScratchpadService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ScratchpadTool {
    private static final Logger logger = LoggerFactory.getLogger(ScratchpadTool.class);

    private final ScratchpadService scratchpadService;

    public ScratchpadTool(ScratchpadService scratchpadService) {
        this.scratchpadService = scratchpadService;
    }

    public record SaveToScratchpadResponse(
            @ToolParam(description = "Operation status message")
            String status) {}

    public record DeleteFromScratchpadResponse(
            @ToolParam(description = "Operation status message")
            String status) {}

    @Tool(name = "save_to_scratchpad", description = "Remember a fact on the scratchpad for this conversation")
    public SaveToScratchpadResponse saveToScratchpad(
            @ToolParam(description = "The fact or note to save") String fact, ToolContext toolContext) {
        UUID chatId = (UUID) toolContext.getContext().get("chatId");
        logger.info("Saving to scratchpad for session {}: {}", chatId, fact);
        try {
            scratchpadService.addFact(chatId, fact);
            return new SaveToScratchpadResponse("Fact saved successfully");
        } catch (Exception e) {
            logger.error("Failed to save to scratchpad", e);
            return new SaveToScratchpadResponse("Tool execution failed: " + e.getMessage());
        }
    }

    @Tool(name = "delete_from_scratchpad", description = "Delete a fact from the scratchpad")
    public DeleteFromScratchpadResponse deleteFromScratchpad(
            @ToolParam(description = "The fact to delete") String fact, ToolContext toolContext) {
        UUID chatId = (UUID) toolContext.getContext().get("chatId");
        logger.info("Deleting from scratchpad for session {}: {}", chatId, fact);
        try {
            scratchpadService.deleteFact(chatId, fact);
            return new DeleteFromScratchpadResponse("Fact deleted successfully");
        } catch (Exception e) {
            logger.error("Failed to delete from scratchpad", e);
            return new DeleteFromScratchpadResponse("Tool execution failed: " + e.getMessage());
        }
    }
}
