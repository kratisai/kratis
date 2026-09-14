package com.kratisai.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.kratisai.controlplane.api.restdto.InstallationInfoDto;
import com.kratisai.controlplane.api.wsdto.MessageRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelEntityCoverageTest {

    @Test
    void teamCoverage() {
        Team team = new Team("Team", "Description");
        team.setId(UUID.randomUUID());
        team.setName("Updated");
        team.setDescription("Updated desc");
        team.setTavilyApiKey("key");
        team.setIngestionProvider(new ModelProvider());
        team.setIngestionModel("gpt-4o");
        team.setEmbeddingProvider(new ModelProvider());
        team.setEmbeddingModel("text-embedding-3-small");
        team.setDefault(true);
        team.setCreatedAt(Instant.EPOCH);
        team.setUpdatedAt(Instant.EPOCH);
        team.setMembers(new ArrayList<>());
        team.setRepositories(new ArrayList<>());
        team.setModelProviders(new ArrayList<>());

        assertThat(team.getId()).isNotNull();
        assertThat(team.getName()).isEqualTo("Updated");
        assertThat(team.getDescription()).isEqualTo("Updated desc");
        assertThat(team.getTavilyApiKey()).isEqualTo("key");
        assertThat(team.getIngestionProvider()).isNotNull();
        assertThat(team.getIngestionModel()).isEqualTo("gpt-4o");
        assertThat(team.getEmbeddingProvider()).isNotNull();
        assertThat(team.getEmbeddingModel()).isEqualTo("text-embedding-3-small");
        assertThat(team.isDefault()).isTrue();
        assertThat(team.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(team.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(team.getMembers()).isEmpty();
        assertThat(team.getRepositories()).isEmpty();
        assertThat(team.getModelProviders()).isEmpty();

        TeamMember member = new TeamMember();
        team.addMember(member);
        assertThat(team.getMembers()).containsExactly(member);
        assertThat(member.getTeam()).isEqualTo(team);

        team.removeMember(member);
        assertThat(team.getMembers()).isEmpty();
        assertThat(member.getTeam()).isNull();

        Repository repository = new Repository();
        team.addRepository(repository);
        assertThat(team.getRepositories()).containsExactly(repository);
        assertThat(repository.getTeam()).isEqualTo(team);

        team.removeRepository(repository);
        assertThat(team.getRepositories()).isEmpty();
        assertThat(repository.getTeam()).isNull();

        ModelProvider provider = new ModelProvider();
        team.addModelProvider(provider);
        assertThat(team.getModelProviders()).containsExactly(provider);
        assertThat(provider.getTeam()).isEqualTo(team);

        team.removeModelProvider(provider);
        assertThat(team.getModelProviders()).isEmpty();
        assertThat(provider.getTeam()).isNull();

        Team defaultTeam = new Team("Default", "Default team", true);
        assertThat(defaultTeam.isDefault()).isTrue();
    }

    @Test
    void teamMemberCoverage() {
        TeamMember member = new TeamMember();
        member.setId(UUID.randomUUID());
        User user = new User();
        Team team = new Team();
        member.setUser(user);
        member.setTeam(team);
        member.setRole("admin");

        assertThat(member.getId()).isNotNull();
        assertThat(member.getUser()).isEqualTo(user);
        assertThat(member.getTeam()).isEqualTo(team);
        assertThat(member.getRole()).isEqualTo("admin");

        TeamMember constructed = new TeamMember(user, team, "member");
        assertThat(constructed.getRole()).isEqualTo("member");
    }

    @Test
    void userCoverage() {
        User user = new User("a@b.com", "hash", "Alice");
        user.setId(UUID.randomUUID());
        user.setEmail("c@d.com");
        user.setPasswordHash("new-hash");
        user.setDisplayName("Bob");
        user.setCreatedAt(Instant.EPOCH);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getEmail()).isEqualTo("c@d.com");
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getDisplayName()).isEqualTo("Bob");
        assertThat(user.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void repositoryCoverage() {
        Repository repo = new Repository();
        repo.setId(UUID.randomUUID());
        repo.setName("repo");
        repo.setUrl("https://github.com/org/repo");
        repo.setBranch("main");
        repo.setRepositoryType(RepositoryType.GITHUB);
        repo.setCredential(new RepoCredential());
        Team team = new Team();
        repo.setTeam(team);
        repo.setCreatedAt(Instant.EPOCH);
        repo.setUpdatedAt(Instant.EPOCH);
        repo.setIngestionBatches(new ArrayList<>());

        assertThat(repo.getId()).isNotNull();
        assertThat(repo.getName()).isEqualTo("repo");
        assertThat(repo.getUrl()).isEqualTo("https://github.com/org/repo");
        assertThat(repo.getBranch()).isEqualTo("main");
        assertThat(repo.getRepositoryType()).isEqualTo(RepositoryType.GITHUB);
        assertThat(repo.getCredential()).isNotNull();
        assertThat(repo.getTeam()).isEqualTo(team);
        assertThat(repo.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(repo.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(repo.getIngestionBatches()).isEmpty();

        Repository constructed = new Repository("name", "url", "branch", RepositoryType.GITLAB);
        assertThat(constructed.getRepositoryType()).isEqualTo(RepositoryType.GITLAB);
    }

    @Test
    void refreshTokenCoverage() {
        User user = new User();
        Instant expiry = Instant.now().plusSeconds(60);
        RefreshToken token = new RefreshToken("token-value", user, expiry);
        token.setId(UUID.randomUUID());
        token.setToken("updated");
        token.setUser(user);
        token.setExpiresAt(expiry);
        token.setCreatedAt(Instant.EPOCH);

        assertThat(token.getId()).isNotNull();
        assertThat(token.getToken()).isEqualTo("updated");
        assertThat(token.getUser()).isEqualTo(user);
        assertThat(token.getExpiresAt()).isEqualTo(expiry);
        assertThat(token.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(token.isExpired()).isFalse();

        RefreshToken expired = new RefreshToken("x", user, Instant.now().minusSeconds(1));
        assertThat(expired.isExpired()).isTrue();
    }

    @Test
    void chatEntityCoverage() {
        ChatEntity chat = new ChatEntity(new Team(), new User(), "Title");
        chat.setId(UUID.randomUUID());
        Team team = new Team();
        User user = new User();
        chat.setTeam(team);
        chat.setUser(user);
        chat.setTitle("Updated");
        chat.setCreatedAt(Instant.EPOCH);
        chat.setUpdatedAt(Instant.EPOCH);
        chat.setArchivedAt(Instant.EPOCH);

        assertThat(chat.getId()).isNotNull();
        assertThat(chat.getTeam()).isEqualTo(team);
        assertThat(chat.getUser()).isEqualTo(user);
        assertThat(chat.getTitle()).isEqualTo("Updated");
        assertThat(chat.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(chat.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(chat.getArchivedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void chatMemoryEntityCoverage() {
        UUID chatId = UUID.randomUUID();
        ChatMemoryEntity memory = new ChatMemoryEntity(chatId, 1, MessageRole.USER, "hello", "[]", "[]", "{}");
        memory.setId(42L);
        memory.setChatId(chatId);
        memory.setMessageIndex(2);
        memory.setMessageType(MessageRole.ASSISTANT);
        memory.setMessageText("world");
        memory.setToolCalls("calls");
        memory.setToolCallResponses("responses");
        memory.setMetadata("meta");
        memory.setCreatedAt(Instant.EPOCH);

        assertThat(memory.getId()).isEqualTo(42L);
        assertThat(memory.getChatId()).isEqualTo(chatId);
        assertThat(memory.getMessageIndex()).isEqualTo(2);
        assertThat(memory.getMessageType()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(memory.getMessageText()).isEqualTo("world");
        assertThat(memory.getToolCalls()).isEqualTo("calls");
        assertThat(memory.getToolCallResponses()).isEqualTo("responses");
        assertThat(memory.getMetadata()).isEqualTo("meta");
        assertThat(memory.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void scratchpadEntityCoverage() {
        UUID chatId = UUID.randomUUID();
        ScratchpadEntity scratch = new ScratchpadEntity(chatId, "fact");
        scratch.setId(1L);
        scratch.setChatId(chatId);
        scratch.setFact("updated");
        scratch.setCreatedAt(Instant.EPOCH);

        assertThat(scratch.getId()).isEqualTo(1L);
        assertThat(scratch.getChatId()).isEqualTo(chatId);
        assertThat(scratch.getFact()).isEqualTo("updated");
        assertThat(scratch.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void canvasEntityCoverage() {
        UUID chatId = UUID.randomUUID();
        CanvasEntity canvas = new CanvasEntity(chatId, "doc-1", "Title", "Content");
        canvas.setId(1L);
        canvas.setChatId(chatId);
        canvas.setDocumentId("doc-2");
        canvas.setTitle("Updated");
        canvas.setContent("Updated content");
        canvas.setCreatedAt(Instant.EPOCH);
        canvas.setUpdatedAt(Instant.EPOCH);
        canvas.setDeletedAt(Instant.EPOCH);

        assertThat(canvas.getId()).isEqualTo(1L);
        assertThat(canvas.getChatId()).isEqualTo(chatId);
        assertThat(canvas.getDocumentId()).isEqualTo("doc-2");
        assertThat(canvas.getTitle()).isEqualTo("Updated");
        assertThat(canvas.getContent()).isEqualTo("Updated content");
        assertThat(canvas.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(canvas.getUpdatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(canvas.getDeletedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void modelProviderCoverage() {
        ModelProvider provider = new ModelProvider("OpenAI", ProviderType.OPENAI, "key", "https://api.openai.com");
        provider.setId(UUID.randomUUID());
        provider.setDisplayName("Updated");
        provider.setProviderType(ProviderType.GOOGLE);
        provider.setApiKey("new-key");
        provider.setBaseUrl("https://new");
        provider.setActive(false);
        provider.setModels(List.of(new ProviderModel("gpt-4o", ModelKind.CHAT)));
        provider.setTeam(new Team());
        provider.setCreatedAt(Instant.EPOCH);
        provider.setUpdatedAt(Instant.EPOCH);

        assertThat(provider.getId()).isNotNull();
        assertThat(provider.getDisplayName()).isEqualTo("Updated");
        assertThat(provider.getProviderType()).isEqualTo(ProviderType.GOOGLE);
        assertThat(provider.getApiKey()).isEqualTo("new-key");
        assertThat(provider.getBaseUrl()).isEqualTo("https://new");
        assertThat(provider.isActive()).isFalse();
        assertThat(provider.getModels()).hasSize(1);
        assertThat(provider.getModelNames()).containsExactly("gpt-4o");
        assertThat(provider.getTeam()).isNotNull();
        assertThat(provider.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(provider.getUpdatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void providerModelCoverage() {
        ProviderModel model = new ProviderModel("gpt-4o", ModelKind.CHAT);
        model.setModelName("claude");
        model.setBaseModel("claude-3-5-sonnet");
        model.setKind(ModelKind.EMBEDDING);

        assertThat(model.getModelName()).isEqualTo("claude");
        assertThat(model.getBaseModel()).isEqualTo("claude-3-5-sonnet");
        assertThat(model.getKind()).isEqualTo(ModelKind.EMBEDDING);
        assertThat(model).isEqualTo(new ProviderModel("claude", "claude-3-5-sonnet", ModelKind.EMBEDDING));
        assertThat(model.hashCode())
                .isEqualTo(new ProviderModel("claude", "claude-3-5-sonnet", ModelKind.EMBEDDING).hashCode());
    }

    @Test
    void ingestionBatchCoverage() {
        Repository repo = new Repository();
        IngestionBatch batch = new IngestionBatch(repo);
        batch.setId(UUID.randomUUID());
        batch.setRepository(repo);
        batch.setCommitHash("abc123");
        batch.setStatus(IngestionStatus.PROCESSING);
        batch.setActive(true);
        batch.setStartedAt(Instant.EPOCH);
        batch.setCompletedAt(Instant.EPOCH);
        batch.setErrorMessage("error");
        batch.setTotalToolCalls(10L);
        batch.setNodes(new ArrayList<>());
        batch.setEdges(new ArrayList<>());
        batch.setLogs(new ArrayList<>());
        batch.setWikiPages(new ArrayList<>());
        batch.setUsage(new LlmUsage());

        assertThat(batch.getId()).isNotNull();
        assertThat(batch.getRepository()).isEqualTo(repo);
        assertThat(batch.getCommitHash()).isEqualTo("abc123");
        assertThat(batch.getStatus()).isEqualTo(IngestionStatus.PROCESSING);
        assertThat(batch.isActive()).isTrue();
        assertThat(batch.getStartedAt()).isEqualTo(Instant.EPOCH);
        assertThat(batch.getCompletedAt()).isEqualTo(Instant.EPOCH);
        assertThat(batch.getErrorMessage()).isEqualTo("error");
        assertThat(batch.getTotalToolCalls()).isEqualTo(10L);
        assertThat(batch.getNodes()).isEmpty();
        assertThat(batch.getEdges()).isEmpty();
        assertThat(batch.getLogs()).isEmpty();
        assertThat(batch.getWikiPages()).isEmpty();
        assertThat(batch.getUsage()).isNotNull();
    }

    @Test
    void ingestionBatchLogCoverage() {
        IngestionBatch batch = new IngestionBatch();
        IngestionBatchLog log = new IngestionBatchLog(batch, UUID.randomUUID(), "INFO", "step", "message");
        log.setId(UUID.randomUUID());
        log.setBatch(batch);
        log.setTeamId(UUID.randomUUID());
        log.setLevel("WARN");
        log.setStep("other");
        log.setMessage("updated");
        log.setCreatedAt(Instant.EPOCH);

        assertThat(log.getId()).isNotNull();
        assertThat(log.getBatch()).isEqualTo(batch);
        assertThat(log.getTeamId()).isNotNull();
        assertThat(log.getLevel()).isEqualTo("WARN");
        assertThat(log.getStep()).isEqualTo("other");
        assertThat(log.getMessage()).isEqualTo("updated");
        assertThat(log.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void ctxWikiPageCoverage() {
        IngestionBatch batch = new IngestionBatch();
        CtxWikiPage parent = new CtxWikiPage();
        CtxWikiPage page = new CtxWikiPage(batch, UUID.randomUUID(), "repo", parent, "slug", "Title", 1, "Content");
        page.setId(UUID.randomUUID());
        page.setBatch(batch);
        page.setTeamId(UUID.randomUUID());
        page.setRepoName("updated-repo");
        page.setParentPage(parent);
        page.setPageSlug("updated-slug");
        page.setTitle("Updated");
        page.setOrderIndex(2);
        page.setContent("Updated content");

        assertThat(page.getId()).isNotNull();
        assertThat(page.getBatch()).isEqualTo(batch);
        assertThat(page.getTeamId()).isNotNull();
        assertThat(page.getRepoName()).isEqualTo("updated-repo");
        assertThat(page.getParentPage()).isEqualTo(parent);
        assertThat(page.getPageSlug()).isEqualTo("updated-slug");
        assertThat(page.getTitle()).isEqualTo("Updated");
        assertThat(page.getOrderIndex()).isEqualTo(2);
        assertThat(page.getContent()).isEqualTo("Updated content");
    }

    @Test
    void ctxEdgeCoverage() {
        IngestionBatch batch = new IngestionBatch();
        CtxNode source = new CtxNode();
        CtxNode target = new CtxNode();
        CtxEdge edge = new CtxEdge(batch, UUID.randomUUID(), source, target, RelationType.CALLS);
        edge.setId(UUID.randomUUID());
        edge.setBatch(batch);
        edge.setTeamId(UUID.randomUUID());
        edge.setSourceNode(source);
        edge.setTargetNode(target);
        edge.setRelationType(RelationType.IMPORTS);

        assertThat(edge.getId()).isNotNull();
        assertThat(edge.getBatch()).isEqualTo(batch);
        assertThat(edge.getTeamId()).isNotNull();
        assertThat(edge.getSourceNode()).isEqualTo(source);
        assertThat(edge.getTargetNode()).isEqualTo(target);
        assertThat(edge.getRelationType()).isEqualTo(RelationType.IMPORTS);
        assertThat(edge.toString()).contains("CtxEdge");
    }

    @Test
    void ctxNodeCoverage() {
        IngestionBatch batch = new IngestionBatch();
        CtxNode node = new CtxNode(batch, UUID.randomUUID(), "repo", NodeType.CLASS, "path");
        node.setId(UUID.randomUUID());
        node.setBatch(batch);
        node.setTeamId(UUID.randomUUID());
        node.setRepoName("updated");
        node.setNodeType(NodeType.METHOD);
        node.setSymbolName("symbol");
        node.setPath("updated/path");
        node.setMetadata("{}");
        node.setArchetypeGroup("group");

        assertThat(node.getId()).isNotNull();
        assertThat(node.getBatch()).isEqualTo(batch);
        assertThat(node.getTeamId()).isNotNull();
        assertThat(node.getRepoName()).isEqualTo("updated");
        assertThat(node.getNodeType()).isEqualTo(NodeType.METHOD);
        assertThat(node.getSymbolName()).isEqualTo("symbol");
        assertThat(node.getPath()).isEqualTo("updated/path");
        assertThat(node.getMetadata()).isEqualTo("{}");
        assertThat(node.getArchetypeGroup()).isEqualTo("group");
        assertThat(node.getSourceCodeRef()).isEqualTo("updated/path:symbol");
        assertThat(node.toString()).contains("CtxNode");
    }

    @Test
    void ctxDimensionCoverage() {
        IngestionBatch batch = new IngestionBatch();
        CtxDimension dimension = new CtxDimension(
                batch, UUID.randomUUID(), DimensionCategory.DOMAIN, "Auth", "Synopsis", List.of("*.java"));
        dimension.setId(UUID.randomUUID());
        dimension.setBatch(batch);
        dimension.setTeamId(UUID.randomUUID());
        dimension.setCategory(DimensionCategory.CROSS_CUTTING);
        dimension.setName("Security");
        dimension.setSynopsis("Updated");
        dimension.setGlobPatterns(List.of("**"));
        dimension.setCreatedAt(Instant.EPOCH);

        assertThat(dimension.getId()).isNotNull();
        assertThat(dimension.getBatch()).isEqualTo(batch);
        assertThat(dimension.getTeamId()).isNotNull();
        assertThat(dimension.getCategory()).isEqualTo(DimensionCategory.CROSS_CUTTING);
        assertThat(dimension.getName()).isEqualTo("Security");
        assertThat(dimension.getSynopsis()).isEqualTo("Updated");
        assertThat(dimension.getGlobPatterns()).containsExactly("**");
        assertThat(dimension.getCreatedAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void ctxNodeDimensionCoverage() {
        CtxNode node = new CtxNode();
        CtxDimension dimension = new CtxDimension();
        CtxNodeDimension nodeDimension = new CtxNodeDimension(node, dimension, 0.95);
        nodeDimension.setId(UUID.randomUUID());
        nodeDimension.setNode(node);
        nodeDimension.setDimension(dimension);
        nodeDimension.setRankScore(0.99);

        assertThat(nodeDimension.getId()).isNotNull();
        assertThat(nodeDimension.getNode()).isEqualTo(node);
        assertThat(nodeDimension.getDimension()).isEqualTo(dimension);
        assertThat(nodeDimension.getRankScore()).isEqualTo(0.99);
    }

    @Test
    void ctxArchitecturePatternCoverage() {
        IngestionBatch batch = new IngestionBatch();
        CtxArchitecturePattern pattern =
                new CtxArchitecturePattern(batch, UUID.randomUUID(), "Layered", "Description", List.of(new CtxNode()));
        pattern.setId(UUID.randomUUID());
        pattern.setBatch(batch);
        pattern.setTeamId(UUID.randomUUID());
        pattern.setName("Hexagonal");
        pattern.setDescription("Updated");
        pattern.setExemplarNodes(new ArrayList<>());
        pattern.setCreatedAt(Instant.EPOCH);

        assertThat(pattern.getId()).isNotNull();
        assertThat(pattern.getBatch()).isEqualTo(batch);
        assertThat(pattern.getTeamId()).isNotNull();
        assertThat(pattern.getName()).isEqualTo("Hexagonal");
        assertThat(pattern.getDescription()).isEqualTo("Updated");
        assertThat(pattern.getExemplarNodes()).isEmpty();
        assertThat(pattern.getCreatedAt()).isEqualTo(Instant.EPOCH);

        CtxArchitecturePattern nullExemplars =
                new CtxArchitecturePattern(batch, UUID.randomUUID(), "Name", "Desc", null);
        assertThat(nullExemplars.getExemplarNodes()).isEmpty();
    }

    @Test
    void ctxEmbeddingCoverage() {
        IngestionBatch batch = new IngestionBatch();
        Team team = new Team();
        CtxWikiPage page = new CtxWikiPage();
        float[] embedding = {0.1f, 0.2f};
        CtxEmbedding emb = new CtxEmbedding(batch, team, page, "chunk", embedding);

        assertThat(emb.getId()).isNotNull();
        assertThat(emb.getBatch()).isEqualTo(batch);
        assertThat(emb.getTeam()).isEqualTo(team);
        assertThat(emb.getPage()).isEqualTo(page);
        assertThat(emb.getChunkText()).isEqualTo("chunk");
        assertThat(emb.getDimSize()).isEqualTo(2);
        assertThat(emb.getEmbedding()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void sandboxExecutionCoverage() {
        SandboxExecution execution = new SandboxExecution();
        execution.setId(UUID.randomUUID());
        ExecutionEnvironment env = new ExecutionEnvironment();
        execution.setEnvironment(env);
        ChatEntity chat = new ChatEntity();
        execution.setChat(chat);
        execution.setExitCode(0);
        execution.setStatus(SandboxExecutionStatus.RUNNING);
        execution.setStartedAt(Instant.EPOCH);
        execution.setCompletedAt(Instant.EPOCH);
        Repository repo = new Repository();
        execution.setRepository(repo);
        execution.setHarness(AgentHarness.CODEX);
        execution.setTaskPrompt("prompt");
        execution.setTotalTokens(100L);
        execution.setPromptTokens(80L);
        execution.setCompletionTokens(20L);
        execution.setTotalSpend(0.05);
        execution.setUsageLastUpdatedAt(Instant.EPOCH);
        execution.setVirtualKey("key");
        ModelProvider provider = new ModelProvider();
        execution.setModelProvider(provider);
        execution.setModelName("gpt-4o");

        assertThat(execution.getId()).isNotNull();
        assertThat(execution.getEnvironment()).isEqualTo(env);
        assertThat(execution.getChat()).isEqualTo(chat);
        assertThat(execution.getExitCode()).isEqualTo(0);
        assertThat(execution.getStatus()).isEqualTo(SandboxExecutionStatus.RUNNING);
        assertThat(execution.getStartedAt()).isEqualTo(Instant.EPOCH);
        assertThat(execution.getCompletedAt()).isEqualTo(Instant.EPOCH);
        assertThat(execution.getRepository()).isEqualTo(repo);
        assertThat(execution.getHarness()).isEqualTo(AgentHarness.CODEX);
        assertThat(execution.getTaskPrompt()).isEqualTo("prompt");
        assertThat(execution.getTotalTokens()).isEqualTo(100L);
        assertThat(execution.getPromptTokens()).isEqualTo(80L);
        assertThat(execution.getCompletionTokens()).isEqualTo(20L);
        assertThat(execution.getTotalSpend()).isEqualTo(0.05);
        assertThat(execution.getUsageLastUpdatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(execution.getVirtualKey()).isEqualTo("key");
        assertThat(execution.getModelProvider()).isEqualTo(provider);
        assertThat(execution.getModelName()).isEqualTo("gpt-4o");
    }

    @Test
    void sandboxPermissionRuleCoverage() {
        SandboxPermissionRule rule = new SandboxPermissionRule();
        rule.setId(UUID.randomUUID());
        Team team = new Team();
        rule.setTeam(team);
        rule.setCommandRoot("npm test");
        rule.setRuleType(SandboxPermissionRuleType.EXACT);
        rule.setAction(SandboxPermissionAction.ALLOW);
        User user = new User();
        rule.setCreatedBy(user);
        rule.setCreatedAt(Instant.EPOCH);

        assertThat(rule.getId()).isNotNull();
        assertThat(rule.getTeam()).isEqualTo(team);
        assertThat(rule.getCommandRoot()).isEqualTo("npm test");
        assertThat(rule.getRuleType()).isEqualTo(SandboxPermissionRuleType.EXACT);
        assertThat(rule.getAction()).isEqualTo(SandboxPermissionAction.ALLOW);
        assertThat(rule.getCreatedBy()).isEqualTo(user);
        assertThat(rule.getCreatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(rule.matches("npm test")).isTrue();
        assertThat(rule.matches("npm run test")).isFalse();
        assertThat(rule.matches(null)).isFalse();

        rule.setRuleType(SandboxPermissionRuleType.PREFIX_WILD);
        rule.setCommandRoot("npm *");
        rule.setAction(SandboxPermissionAction.DENY);
        assertThat(rule.getAction()).isEqualTo(SandboxPermissionAction.DENY);
        assertThat(rule.matches("npm install")).isTrue();
        assertThat(rule.matches("npx test")).isFalse();

        rule.onCreate();
        assertThat(rule.getCreatedAt()).isNotNull();

        assertThat(SandboxPermissionAction.values())
                .containsExactly(SandboxPermissionAction.ALLOW, SandboxPermissionAction.DENY);
        assertThat(SandboxPermissionAction.valueOf("ALLOW")).isEqualTo(SandboxPermissionAction.ALLOW);
    }

    @Test
    void providerTypeCoverage() {
        assertThat(ProviderType.values()).contains(ProviderType.OPENAI, ProviderType.GOOGLE);
        assertThat(ProviderType.valueOf("OPENAI")).isEqualTo(ProviderType.OPENAI);
    }

    @Test
    void dimensionCategoryCoverage() {
        assertThat(DimensionCategory.values()).contains(DimensionCategory.DOMAIN, DimensionCategory.ARCHETYPE);
        assertThat(DimensionCategory.valueOf("DOMAIN")).isEqualTo(DimensionCategory.DOMAIN);
    }

    @Test
    void providerMetadataCoverage() {
        ProviderMetadata metadata = ProviderMetadata.of("""
                {"installationId":"123","gitlabUrl":"https://gitlab.com","bitbucketWorkspace":"acme",\
                "azureBaseUrl":"https://dev.azure.com/org","azureProject":"proj","provider":"github"}
                """);

        assertThat(metadata.getInstallationId()).hasValue("123");
        assertThat(metadata.getGitLabUrl()).hasValue("https://gitlab.com");
        assertThat(metadata.getBitbucketWorkspace()).hasValue("acme");
        assertThat(metadata.getAzureBaseUrl()).hasValue("https://dev.azure.com/org");
        assertThat(metadata.getAzureProject()).hasValue("proj");
        assertThat(metadata.getProvider()).hasValue("github");
        assertThat(metadata.getProviderType()).hasValue(RepositoryType.GITHUB);
        assertThat(metadata.isGitHubApp()).isTrue();

        assertThat(ProviderMetadata.mapProviderType("gitlab")).isEqualTo(RepositoryType.GITLAB);
        assertThat(ProviderMetadata.mapProviderType("bitbucket")).isEqualTo(RepositoryType.BITBUCKET);
        assertThat(ProviderMetadata.mapProviderType("azure")).isEqualTo(RepositoryType.AZURE);
        assertThat(ProviderMetadata.mapProviderType("unknown")).isNull();
        assertThat(ProviderMetadata.mapProviderType(null)).isNull();

        ProviderMetadata empty = ProviderMetadata.of(null);
        assertThat(empty.getProvider()).isEmpty();
    }

    @Test
    void chatUsageSessionCoverage() {
        ChatUsageSession session = new ChatUsageSession();
        UUID id = UUID.randomUUID();
        session.setId(id);
        ChatEntity chat = new ChatEntity();
        session.setChat(chat);
        Instant started = Instant.now().minusSeconds(60);
        Instant ended = Instant.now();
        session.setStartedAt(started);
        session.setEndedAt(ended);
        session.setModel("gpt-4o");

        LlmUsage usage = new LlmUsage();
        usage.setTotalTokens(100L);
        usage.setPromptTokens(60L);
        usage.setCompletionTokens(40L);
        usage.setTotalSpend(0.01);
        usage.setVirtualKey("sk-key");
        session.setUsage(usage);

        assertThat(session.getId()).isEqualTo(id);
        assertThat(session.getChat()).isEqualTo(chat);
        assertThat(session.getStartedAt()).isEqualTo(started);
        assertThat(session.getEndedAt()).isEqualTo(ended);
        assertThat(session.isEnded()).isTrue();
        assertThat(session.getModel()).isEqualTo("gpt-4o");
        assertThat(session.getModels()).containsExactly("gpt-4o");
        assertThat(session.getUsage().getTotalTokens()).isEqualTo(100L);
        assertThat(session.getUsage().getVirtualKey()).isEqualTo("sk-key");

        session.setModels(List.of("claude-3-5-sonnet"));
        assertThat(session.getModel()).isEqualTo("claude-3-5-sonnet");
        assertThat(session.getModels()).containsExactly("claude-3-5-sonnet");

        session.setModels(List.of());
        assertThat(session.getModel()).isNull();
        assertThat(session.getModels()).isEmpty();

        ChatUsageSession sessionWithConstructor = new ChatUsageSession(chat, "sk-vkey", "model-x");
        assertThat(sessionWithConstructor.getChat()).isEqualTo(chat);
        assertThat(sessionWithConstructor.getUsage().getVirtualKey()).isEqualTo("sk-vkey");
        assertThat(sessionWithConstructor.getModel()).isEqualTo("model-x");
        assertThat(sessionWithConstructor.isEnded()).isFalse();

        sessionWithConstructor.onCreate();
        assertThat(sessionWithConstructor.getStartedAt()).isNotNull();
    }

    @Test
    void kratisInstallationCoverage() {
        UUID installId = UUID.randomUUID();
        KratisInstallation installation = new KratisInstallation(installId);
        assertThat(installation.getSingletonKey()).isEqualTo(KratisInstallation.SINGLETON_KEY);
        assertThat(installation.getInstallId()).isEqualTo(installId);
        assertThat(installation.getCreatedAt()).isNotNull();

        installation.setId(UUID.randomUUID());
        installation.setInstallId(UUID.randomUUID());
        installation.setCreatedAt(Instant.EPOCH);
        assertThat(installation.getId()).isNotNull();
        assertThat(installation.getInstallId()).isNotEqualTo(installId);
        assertThat(installation.getCreatedAt()).isEqualTo(Instant.EPOCH);

        KratisInstallation noArg = new KratisInstallation();
        assertThat(noArg.getSingletonKey()).isEqualTo(KratisInstallation.SINGLETON_KEY);
        assertThat(noArg.getInstallId()).isNull();

        InstallationInfoDto dto = new InstallationInfoDto(installId, "1.0.0");
        assertThat(dto.installId()).isEqualTo(installId);
        assertThat(dto.version()).isEqualTo("1.0.0");
    }
}
