package com.kratisai.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kratisai.controlplane.DatabaseCleaner;
import com.kratisai.controlplane.SpringIntegrationTest;
import com.kratisai.controlplane.TestDataFactory;
import com.kratisai.controlplane.model.CanvasEntity;
import com.kratisai.controlplane.model.CanvasType;
import com.kratisai.controlplane.model.ChatEntity;
import com.kratisai.controlplane.model.Repository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SpringIntegrationTest
class CanvasServiceTest {

    @Autowired
    private CanvasService canvasService;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestDataFactory testDataFactory;

    @BeforeEach
    void setUp() {
        databaseCleaner.cleanAll();
    }

    @Test
    void testCreateCanvas() {
        UUID chatId = UUID.randomUUID();
        String documentId = "doc-123";

        CanvasEntity entity = canvasService.createCanvas(
                chatId, documentId, "Test Canvas", "# Hello", CanvasType.DOCUMENT, null, null);

        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getChatId()).isEqualTo(chatId);
        assertThat(entity.getDocumentId()).isEqualTo(documentId);
        assertThat(entity.getTitle()).isEqualTo("Test Canvas");
        assertThat(entity.getContent()).isEqualTo("# Hello");
    }

    @Test
    void testUpdateCanvas() {
        UUID chatId = UUID.randomUUID();
        String documentId = "doc-123";

        // Create first
        canvasService.createCanvas(chatId, documentId, "Test Canvas", "# Hello", CanvasType.DOCUMENT, null, null);

        // Update
        CanvasEntity updated = canvasService.updateCanvas(chatId, documentId, "# Updated");

        assertThat(updated.getContent()).isEqualTo("# Updated");
    }

    @Test
    void testUpdateCanvasNotFound() {
        UUID chatId = UUID.randomUUID();

        assertThatThrownBy(() -> canvasService.updateCanvas(chatId, "nonexistent", "# Updated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Canvas not found");
    }

    @Test
    void testGetCanvasesForSession() {
        UUID chatId = UUID.randomUUID();

        canvasService.createCanvas(chatId, "doc-1", "Doc 1", "# Content 1", CanvasType.DOCUMENT, null, null);
        canvasService.createCanvas(chatId, "doc-2", "Doc 2", "# Content 2", CanvasType.DOCUMENT, null, null);

        List<CanvasEntity> canvases = canvasService.getCanvasesForChat(chatId);

        assertThat(canvases).hasSize(2);
    }

    @Test
    void testGetCanvas() {
        UUID chatId = UUID.randomUUID();
        String documentId = "doc-123";

        canvasService.createCanvas(chatId, documentId, "Test Canvas", "# Hello", CanvasType.DOCUMENT, null, null);

        CanvasEntity found = canvasService.getCanvas(chatId, documentId);

        assertThat(found).isNotNull();
        assertThat(found.getDocumentId()).isEqualTo(documentId);
    }

    @Test
    void testGetCanvasNotFound() {
        UUID chatId = UUID.randomUUID();

        CanvasEntity found = canvasService.getCanvas(chatId, "nonexistent");

        assertThat(found).isNull();
    }

    @Test
    void createCanvas_specWithRepository_persistsSpecType() {
        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        Repository repository = ctx.repository();
        UUID chatId = UUID.randomUUID();

        CanvasEntity entity = canvasService.createCanvas(
                chatId, "task-doc", "Task Plan", "# Plan", CanvasType.SPEC, repository, null);

        assertThat(entity.getCanvasType()).isEqualTo(CanvasType.SPEC);
        assertThat(entity.getRepository().getId()).isEqualTo(repository.getId());
        assertThat(entity.getNewRepoName()).isNull();
    }

    @Test
    void createCanvas_specWithNewRepoName_persistsSpecType() {
        UUID chatId = UUID.randomUUID();

        CanvasEntity entity = canvasService.createCanvas(
                chatId, "task-new", "New Repo Plan", "# Plan", CanvasType.SPEC, null, "fresh-repo");

        assertThat(entity.getCanvasType()).isEqualTo(CanvasType.SPEC);
        assertThat(entity.getRepository()).isNull();
        assertThat(entity.getNewRepoName()).isEqualTo("fresh-repo");
    }

    @Test
    void createCanvas_specWithoutRepoForm_throws() {
        UUID chatId = UUID.randomUUID();

        assertThatThrownBy(() ->
                        canvasService.createCanvas(chatId, "task-none", "Plan", "# Plan", CanvasType.SPEC, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec canvas requires a repository name or a new-repository definition");
    }

    @Test
    void createCanvas_specWithBothRepoForms_throws() {
        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        UUID chatId = UUID.randomUUID();

        assertThatThrownBy(() -> canvasService.createCanvas(
                        chatId, "task-both", "Plan", "# Plan", CanvasType.SPEC, ctx.repository(), "fresh-repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spec canvas requires exactly one");
    }

    @Test
    void createCanvas_documentWithRepository_throws() {
        TestDataFactory.TestContext ctx = testDataFactory.createDefaultContext();
        UUID chatId = UUID.randomUUID();

        assertThatThrownBy(() -> canvasService.createCanvas(
                        chatId, "doc-repo", "Doc", "# Notes", CanvasType.DOCUMENT, ctx.repository(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document canvas must not define a repository");
    }

    @Test
    void testValidateMarkdownValid() {
        assertThat(canvasService.validateMarkdown("# Hello\n\nWorld")).isTrue();
    }

    @Test
    void testValidateMarkdownWithCodeBlock() {
        assertThat(canvasService.validateMarkdown("```\ncode\n```")).isTrue();
    }

    @Test
    void testValidateMarkdownUnclosedFence() {
        assertThat(canvasService.validateMarkdown("```\ncode")).isFalse();
    }

    @Test
    void testValidateMarkdownEmpty() {
        assertThat(canvasService.validateMarkdown("")).isFalse();
    }

    @Test
    void testValidateMarkdownNull() {
        assertThat(canvasService.validateMarkdown(null)).isFalse();
    }

    @Test
    void deleteCanvas_softDeletesAndExcludesFromAllOperations() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        ChatEntity chat = testDataFactory.createChat(ctx.team(), ctx.user(), "Delete Chat");
        UUID chatId = chat.getId();
        String documentId = "doc-to-delete";

        canvasService.createCanvas(chatId, documentId, "Doc", "# Content", CanvasType.DOCUMENT, null, null);

        canvasService.deleteCanvas(ctx.user().getId(), chatId, documentId);

        assertThat(canvasService.getCanvas(chatId, documentId)).isNull();
        assertThat(canvasService.getCanvasesForChat(chatId)).isEmpty();
        assertThatThrownBy(() -> canvasService.updateCanvas(chatId, documentId, "# Updated"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Canvas not found");
        assertThatThrownBy(() -> canvasService.createCanvas(
                        chatId, documentId, "New Doc", "# New", CanvasType.DOCUMENT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was deleted and cannot be recreated");
    }

    @Test
    void deleteCanvas_isIdempotent() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        ChatEntity chat = testDataFactory.createChat(ctx.team(), ctx.user(), "Idempotent Chat");
        UUID chatId = chat.getId();
        String documentId = "doc-idempotent";

        canvasService.createCanvas(chatId, documentId, "Doc", "# Content", CanvasType.DOCUMENT, null, null);
        canvasService.deleteCanvas(ctx.user().getId(), chatId, documentId);
        canvasService.deleteCanvas(ctx.user().getId(), chatId, documentId);

        assertThat(canvasService.getCanvas(chatId, documentId)).isNull();
    }

    @Test
    void deleteCanvas_chatNotFound_throwsNotFound() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();

        assertThatThrownBy(() -> canvasService.deleteCanvas(ctx.user().getId(), UUID.randomUUID(), "doc-missing-chat"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteCanvas_documentNotFound_throwsNotFound() {
        TestDataFactory.TestContext ctx = testDataFactory.createUserAndTeam();
        ChatEntity chat = testDataFactory.createChat(ctx.team(), ctx.user(), "No Doc Chat");

        assertThatThrownBy(() -> canvasService.deleteCanvas(ctx.user().getId(), chat.getId(), "doc-does-not-exist"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deleteCanvas_nonMember_throwsForbidden() {
        TestDataFactory.TestContext owner = testDataFactory.createUserAndTeam();
        ChatEntity chat = testDataFactory.createChat(owner.team(), owner.user(), "Owned Chat");
        String documentId = "doc-owner-only";
        canvasService.createCanvas(chat.getId(), documentId, "Doc", "# Content", CanvasType.DOCUMENT, null, null);

        TestDataFactory.TestContext outsider = testDataFactory.createUserAndTeam();

        assertThatThrownBy(() -> canvasService.deleteCanvas(outsider.user().getId(), chat.getId(), documentId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }
}
