package com.kratisai.controlplane.planningagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.Repository;
import com.kratisai.controlplane.model.RepositoryType;
import com.kratisai.controlplane.model.Team;
import com.kratisai.controlplane.model.User;
import com.kratisai.controlplane.repository.CanvasRepository;
import com.kratisai.controlplane.repository.RepositoryRepository;
import com.kratisai.controlplane.service.CanvasService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;

@SpringIntegrationTest
class CanvasToolTest extends AgentTestHarness {

    @Autowired
    private CanvasTool canvasTool;

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private CanvasRepository canvasRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    private UUID chatId;
    private ToolContext toolContext;
    private Team team;

    @BeforeEach
    void setUp() throws Exception {
        cleanDatabase();
        canvasRepository.deleteAll();

        setupTeamAndLogin();

        var users = userRepo.findAll();
        assertThat(users).isNotEmpty();
        User user = users.getFirst();

        team = createTeamWithProvider(user.getId());
        UUID teamId = team.getId();
        chatId = UUID.randomUUID();

        toolContext = new ToolContext(Map.of("chatId", chatId, "teamId", teamId));
    }

    @AfterEach
    void tearDown() {
        canvasRepository.deleteAll();
        cleanDatabase();
    }

    @Test
    void shouldWriteAndRetrieveCanvas() {
        // Test: Write a new document canvas
        String writeResult = canvasTool.writeCanvas(
                null, "Architecture Spec", "# Arch\n\nInitial content.", CanvasType.DOCUMENT, null, null, toolContext);
        assertThat(writeResult).contains("Document successfully written");

        // Verify entity exists
        List<CanvasEntity> canvases = canvasService.getCanvasesForChat(chatId);
        assertThat(canvases).hasSize(1);
        CanvasEntity canvas = canvases.getFirst();
        assertThat(canvas.getTitle()).isEqualTo("Architecture Spec");
        assertThat(canvas.getCanvasType()).isEqualTo(CanvasType.DOCUMENT);
        String docId = canvas.getDocumentId();

        // Test: Retrieve canvas
        String retrieveResult = canvasTool.retrieveFromCanvas(docId, toolContext);
        assertThat(retrieveResult).isEqualTo("# Arch\n\nInitial content.");

        // Test: List documents
        String listResult = canvasTool.listCanvasDocuments(toolContext);
        assertThat(listResult)
                .contains("Documents in canvas: [{Document ID: " + docId + ", Title: Architecture Spec"
                        + ", CanvasType: DOCUMENT}]");
    }

    @Test
    void shouldPatchCanvas() {
        // Setup initial canvas
        CanvasEntity canvas = canvasService.createCanvas(
                chatId,
                UUID.randomUUID().toString(),
                "Design",
                "The system uses MySQL database.",
                CanvasType.DOCUMENT,
                null,
                null);
        String docId = canvas.getDocumentId();

        // Test: Patch canvas
        CanvasTool.ReplaceBlock patch = new CanvasTool.ReplaceBlock("MySQL", "PostgreSQL");
        String patchResult = canvasTool.patchCanvas(docId, List.of(patch), toolContext);
        assertThat(patchResult).contains("Document successfully patched");

        // Verify patched content
        String retrieveResult = canvasTool.retrieveFromCanvas(docId, toolContext);
        assertThat(retrieveResult).isEqualTo("The system uses PostgreSQL database.");
    }

    @Test
    void shouldReplaceExistingDocumentWhenWritingCanvas() {
        // Setup: Create initial canvas
        String docId = UUID.randomUUID().toString();
        canvasService.createCanvas(
                chatId, docId, "Original Title", "# Original Content", CanvasType.DOCUMENT, null, null);

        // Test: Write canvas with same document ID should replace it
        String writeResult = canvasTool.writeCanvas(
                docId, "Updated Title", "# Updated Content", CanvasType.DOCUMENT, null, null, toolContext);
        assertThat(writeResult).contains("Document successfully written");
        assertThat(writeResult).contains("Updated Title");

        // Verify only one document exists
        List<CanvasEntity> canvases = canvasService.getCanvasesForChat(chatId);
        assertThat(canvases).hasSize(1);

        // Verify the document was replaced with new content and title
        CanvasEntity updatedCanvas = canvases.getFirst();
        assertThat(updatedCanvas.getDocumentId()).isEqualTo(docId);
        assertThat(updatedCanvas.getTitle()).isEqualTo("Updated Title");

        // Verify content was replaced
        String retrieveResult = canvasTool.retrieveFromCanvas(docId, toolContext);
        assertThat(retrieveResult).isEqualTo("# Updated Content");
    }

    @Test
    void writeCanvas_onDeletedDocumentId_isBlocked() {
        String docId = UUID.randomUUID().toString();
        CanvasEntity created = canvasService.createCanvas(
                chatId, docId, "Original Title", "# Original Content", CanvasType.DOCUMENT, null, null);
        created.setDeletedAt(Instant.now());
        canvasRepository.save(created);

        assertThat(canvasService.getCanvasesForChat(chatId)).isEmpty();
        assertThat(canvasTool.retrieveFromCanvas(docId, toolContext)).isEqualTo("Document not found");
        assertThat(canvasTool.patchCanvas(
                        docId, List.of(new CanvasTool.ReplaceBlock("# Original Content", "# Patched")), toolContext))
                .contains("Error: Document not found");

        assertThatThrownBy(() -> canvasTool.writeCanvas(
                        docId, "New Title", "# New Content", CanvasType.DOCUMENT, null, null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was deleted and cannot be recreated");
    }

    @Test
    void writeCanvas_withExistingRepoName_createsSpecCanvasWithRepository() {
        Repository repo = saveRepository("backend", team);

        String writeResult = canvasTool.writeCanvas(
                "plan-exec", "Exec Plan", "# Plan", CanvasType.SPEC, "backend", null, toolContext);
        assertThat(writeResult).contains("Document successfully written");

        CanvasEntity canvas = canvasService.getCanvas(chatId, "plan-exec");
        assertThat(canvas.getCanvasType()).isEqualTo(CanvasType.SPEC);
        assertThat(canvas.getRepository().getId()).isEqualTo(repo.getId());
        assertThat(canvas.getNewRepoName()).isNull();
        assertThat(canvas.getRepoLabel()).isEqualTo("backend");
        assertThat(canvas.isNewRepo()).isFalse();
    }

    @Test
    void writeCanvas_withNewRepo_createsSpecCanvasWithoutRepositoryRow() {
        String writeResult = canvasTool.writeCanvas(
                "plan-new", "Exec Plan", "# Plan", CanvasType.SPEC, null, "fresh-repo", toolContext);
        assertThat(writeResult).contains("Document successfully written");

        CanvasEntity canvas = canvasService.getCanvas(chatId, "plan-new");
        assertThat(canvas.getCanvasType()).isEqualTo(CanvasType.SPEC);
        assertThat(canvas.getRepository()).isNull();
        assertThat(canvas.getNewRepoName()).isEqualTo("fresh-repo");
        assertThat(canvas.getRepoLabel()).isEqualTo("New: fresh-repo");
        assertThat(canvas.isNewRepo()).isTrue();
        assertThat(repositoryRepository.findByTeamIdAndName(team.getId(), "fresh-repo"))
                .isEmpty();
    }

    @Test
    void writeCanvas_specWithoutRepo_throws() {
        assertThatThrownBy(() -> canvasTool.writeCanvas(
                        "plan-x", "Exec Plan", "# Plan", CanvasType.SPEC, null, null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec canvas requires either a repoName or newRepoName");
    }

    @Test
    void writeCanvas_specWithBothRepoForms_throws() {
        saveRepository("backend", team);
        assertThatThrownBy(() -> canvasTool.writeCanvas(
                        "plan-y", "Exec Plan", "# Plan", CanvasType.SPEC, "backend", "fresh-repo", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void writeCanvas_specWithUnknownRepoName_throws() {
        assertThatThrownBy(() -> canvasTool.writeCanvas(
                        "plan-z", "Exec Plan", "# Plan", CanvasType.SPEC, "no-such-repo", null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void writeCanvas_documentWithRepo_throws() {
        saveRepository("backend", team);
        assertThatThrownBy(() -> canvasTool.writeCanvas(
                        "doc-repo", "Doc", "# Doc", CanvasType.DOCUMENT, "backend", null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document canvas must not define");
    }

    @Test
    void writeCanvas_missingCanvasType_throws() {
        assertThatThrownBy(() -> canvasTool.writeCanvas("doc-mt", "Doc", "# Doc", null, null, null, toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canvasType is required");
    }

    @Test
    void writeCanvas_newRepoWithoutName_throws() {
        assertThatThrownBy(() -> canvasTool.writeCanvas(
                        "plan-nn", "Exec Plan", "# Plan", CanvasType.SPEC, null, "  ", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newRepoName is required for a new-repository");
    }

    @Test
    void writeCanvas_withInvalidMermaid_returnsErrorAndDoesNotPersist() {
        String content = """
                ```mermaid
                graph TD
                    subgraph Control Plane Tier (Spring Boot)
                        API[REST Controllers]
                    end
                ```
                """;

        String result = canvasTool.writeCanvas(null, "Arch", content, CanvasType.DOCUMENT, null, null, toolContext);

        assertThat(result).startsWith("ERROR: Invalid Mermaid diagram syntax in content.");
        assertThat(canvasService.getCanvasesForChat(chatId)).isEmpty();
    }

    @Test
    void writeCanvas_withQuotedMermaidSubgraphTitle_succeeds() {
        String content = """
                ```mermaid
                graph TD
                    subgraph "Control Plane Tier (Spring Boot)"
                        API[REST Controllers]
                    end
                ```
                """;

        String result = canvasTool.writeCanvas(null, "Arch", content, CanvasType.DOCUMENT, null, null, toolContext);

        assertThat(result).contains("Document successfully written");
        assertThat(canvasService.getCanvasesForChat(chatId)).hasSize(1);
    }

    @Test
    void patchCanvas_producingInvalidMermaid_returnsErrorAndDoesNotPersist() {
        String docId = UUID.randomUUID().toString();
        canvasService.createCanvas(
                chatId, docId, "Design", "The system uses MySQL database.", CanvasType.DOCUMENT, null, null);

        String invalidMermaid = """
                ```mermaid
                graph TD
                    subgraph Tier (One)
                        A
                    end
                ```
                """;
        CanvasTool.ReplaceBlock patch = new CanvasTool.ReplaceBlock("MySQL database", invalidMermaid);

        String result = canvasTool.patchCanvas(docId, List.of(patch), toolContext);

        assertThat(result).startsWith("ERROR: Invalid Mermaid diagram syntax after patching.");
        assertThat(canvasTool.retrieveFromCanvas(docId, toolContext)).isEqualTo("The system uses MySQL database.");
    }

    private Repository saveRepository(String name, Team team) {
        Repository repo = new Repository(name, "https://example.com/" + name + ".git", "main", RepositoryType.GENERIC);
        repo.setTeam(team);
        return repositoryRepository.save(repo);
    }
}
