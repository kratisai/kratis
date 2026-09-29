package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.service.BatchResolutionService;
import com.kratisai.controlplane.service.CanvasService;
import com.kratisai.controlplane.service.ScratchpadService;
import com.kratisai.controlplane.service.TeamService;
import com.kratisai.controlplane.validation.MermaidDiagramValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Unit tests for tool beans. Tests the @Tool methods directly without Spring
 * context.
 */
class ToolBeanUnitTest {

    @Test
    void webSearchToolShouldReturnResponseWhenNoApiKey() {
        // Create a mock team service that returns null for API key
        TeamService mockTeamService = mock(TeamService.class);
        when(mockTeamService.getTavilyApiKey(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenReturn(null);

        WebSearchTool webSearchTool = new WebSearchTool(mockTeamService);
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), UUID.randomUUID()).toToolContext();

        WebSearchTool.WebSearchResponse response = webSearchTool.webSearch("Java Spring", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.markdownSummary()).contains("Java Spring");
    }

    @Test
    void saveToScratchpadToolShouldReturnSuccess() {
        // Create a mock scratchpad service
        ScratchpadService mockService = mock(ScratchpadService.class);
        UUID session_1 = UUID.randomUUID();
        when(mockService.addFact(session_1, "Test fact"))
                .thenReturn(new com.kratisai.controlplane.model.ScratchpadEntity());

        ScratchpadTool scratchpadTool = new ScratchpadTool(mockService);
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), session_1).toToolContext();

        ScratchpadTool.SaveToScratchpadResponse response = scratchpadTool.saveToScratchpad("Test fact", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.status()).contains("saved");
    }

    @Test
    void deleteFromScratchpadToolShouldReturnSuccess() {
        // Create a mock scratchpad service
        ScratchpadService mockService = mock(ScratchpadService.class);
        UUID session_1 = UUID.randomUUID();
        doNothing().when(mockService).deleteFact(session_1, "Test fact");

        ScratchpadTool scratchpadTool = new ScratchpadTool(mockService);
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), session_1).toToolContext();

        ScratchpadTool.DeleteFromScratchpadResponse response =
                scratchpadTool.deleteFromScratchpad("Test fact", toolContext);

        assertThat(response).isNotNull();
        assertThat(response.status()).contains("deleted");
    }

    @Test
    void listCanvasDocumentsToolShouldReturnEmptyList() {
        // Create a mock canvas service that returns empty list
        CanvasService mockService = mock(CanvasService.class);
        when(mockService.getCanvasesForChat(any(UUID.class))).thenReturn(List.of());

        CanvasTool canvasTool =
                new CanvasTool(mockService, mock(BatchResolutionService.class), new MermaidDiagramValidator());
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), UUID.randomUUID()).toToolContext();

        String result = canvasTool.listCanvasDocuments(toolContext);

        assertThat(result).isNotNull();
        assertThat(result).contains("Documents in canvas: []");
    }

    @Test
    void listCanvasDocumentsToolShouldReturnMultipleDocuments() {
        // Create a mock canvas service that returns multiple documents
        CanvasService mockService = mock(CanvasService.class);
        UUID chatIdUuid = UUID.randomUUID();
        CanvasEntity doc1 = new CanvasEntity(chatIdUuid, "doc-1", "Document One", "Content 1");
        CanvasEntity doc2 = new CanvasEntity(chatIdUuid, "doc-2", "Document Two", "Content 2");
        when(mockService.getCanvasesForChat(chatIdUuid)).thenReturn(List.of(doc1, doc2));

        CanvasTool canvasTool =
                new CanvasTool(mockService, mock(BatchResolutionService.class), new MermaidDiagramValidator());
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), chatIdUuid).toToolContext();

        String result = canvasTool.listCanvasDocuments(toolContext);

        assertThat(result).isNotNull();
        assertThat(result).contains("Document ID: doc-1, Title: Document One");
        assertThat(result).contains("Document ID: doc-2, Title: Document Two");
    }

    @Test
    void retrieveFromCanvasToolShouldReturnContent() {
        // Create a mock canvas service that returns a document
        CanvasService mockService = mock(CanvasService.class);
        UUID chatIdUuid = UUID.randomUUID();
        CanvasEntity canvas = new CanvasEntity(chatIdUuid, "doc-1", "Test Document", "# Hello World");
        when(mockService.getCanvas(chatIdUuid, "doc-1")).thenReturn(canvas);

        CanvasTool canvasTool =
                new CanvasTool(mockService, mock(BatchResolutionService.class), new MermaidDiagramValidator());
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), chatIdUuid).toToolContext();

        String result = canvasTool.retrieveFromCanvas("doc-1", toolContext);

        assertThat(result).isNotNull();
        assertThat(result).contains("# Hello World");
    }

    @Test
    void retrieveFromCanvasToolShouldReturnNotFoundForMissingDocument() {
        // Create a mock canvas service that returns null for missing document
        CanvasService mockService = mock(CanvasService.class);
        UUID chatIdUuid = UUID.randomUUID();
        when(mockService.getCanvas(chatIdUuid, "nonexistent")).thenReturn(null);

        CanvasTool canvasTool =
                new CanvasTool(mockService, mock(BatchResolutionService.class), new MermaidDiagramValidator());
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), chatIdUuid).toToolContext();

        String result = canvasTool.retrieveFromCanvas("nonexistent", toolContext);

        assertThat(result).isEqualTo("Document not found");
    }

    @Test
    void retrieveFromCanvasToolShouldHandleException() {
        // Create a mock canvas service that throws an exception
        CanvasService mockService = mock(CanvasService.class);
        UUID chatIdUuid = UUID.randomUUID();
        when(mockService.getCanvas(chatIdUuid, "doc-1")).thenThrow(new RuntimeException("Database error"));

        CanvasTool canvasTool =
                new CanvasTool(mockService, mock(BatchResolutionService.class), new MermaidDiagramValidator());
        ToolContext toolContext = new PlanningContext(UUID.randomUUID(), chatIdUuid).toToolContext();

        String result = canvasTool.retrieveFromCanvas("doc-1", toolContext);

        assertThat(result).contains("Retrieving canvas failed:");
        assertThat(result).contains("Database error");
    }
}
