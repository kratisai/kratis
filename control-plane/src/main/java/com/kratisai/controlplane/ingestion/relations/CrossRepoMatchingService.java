package com.kratisai.controlplane.ingestion.relations;

import com.kratisai.controlplane.config.IngestionProperties;
import com.kratisai.controlplane.ingestion.BatchChatClientFactory;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService.BatchLogger;
import com.kratisai.controlplane.model.Ceiling;
import com.kratisai.controlplane.model.CtxRepoSynopsis;
import com.kratisai.controlplane.model.Direction;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.Nature;
import com.kratisai.controlplane.model.SurfaceDetection;
import com.kratisai.controlplane.model.SurfaceRole;
import com.kratisai.controlplane.repository.IngestionBatchRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CrossRepoMatchingService {
    private static final String STEP = "ACTIVATE_BATCH";
    private static final String SUBJECT_REPO_ALIAS = "A";
    private static final String OTHER_REPO_ALIAS = "B";
    private static final int PROMPT_SAFETY_MARGIN = 512;

    private static final String SYSTEM_PROMPT = """
            Compare the supplied surface pairs, using their descriptions, attributes and synopsis.
            Report only current, implemented relationships that exist between the two repositories as they stand today,
            backed by concrete present-state evidence. Do not propose speculative relationships based on potential
            reuse, hypothetical adaptation, or future enhancements.
            Generic path, host, or port coincidences (such as /api/v1, /health, or localhost:8080) are not
            relationships unless concrete surface evidence and both repositories' synopses and roles confirm an active
            present-day integration.
            Return only supported surface-to-surface matches. Set subjectRepositoryId="A" and otherRepositoryId="B",
            cite ONE single surface alias in subjectSurfaceId (e.g. s1) and ONE single surface alias in otherSurfaceId
            (e.g. o1) — never comma-separated lists — and cite supplied attribute aliases belonging to that exact
            surface (e.g. s1.a1, s1.a2 on Subject; o1.b1, o1.b2 on Other) plus optional synopsis fragment IDs (f1, f2,
            ...) on each side. If multiple surface pairs match, emit a separate match object for each pair. If a surface
            has no attributes, its cited surface alias refers to its description as evidence. Do not invent matches from
            synopsis prose alone without supporting surfaces on both sides; return an empty matches list when no match
            is found.
            Match only interfaces owned by the repositories themselves. Shared use of the same external third-party
            service, API, provider, library or dependency is not a relationship between the repositories; never use it
            as evidence for PEER or SHARES_STATE.
            Relation natures (never return surface roles PROVIDES or REQUIRES as nature):
            - CALLS: synchronous request/response APIs only (HTTP REST, gRPC, RPC), matching one PROVIDES surface to one
              REQUIRES surface; cite ENDPOINT, IDENTIFIER, or REF attributes on both. Never use CALLS for WebSocket, and
              do not use CALLS for DNS zones/records, load balancers, or CDN/ingress routing infrastructure (those are
              DEPLOYS).
            - MESSAGES: asynchronous or bidirectional message and event streams — ALWAYS use MESSAGES (never CALLS) for
              WebSocket endpoints (ws://, wss://, WebSocket), Socket.IO, pub/sub topics, queues, event buses, and domain
              event/message payload contracts exchanged with a WebSocket or messaging server (including when client
              transport is mediated through a shared SDK or library); cite NAME, IDENTIFIER, ENDPOINT, REF, or METHOD
              attributes on both surfaces.
            - BUILDS_AGAINST: one surface PROVIDES a package/artifact and the other REQUIRES that direct dependency,
              with the same package type, namespace, and name in IDENTIFIER (versions may differ).
            - DEPLOYS: deployment, provisioning, or runtime-hosting coupling between the two repositories (IaC
              provisioning, CI artifact publishing, or runtime hosting); cite FILE, IDENTIFIER, TECHNOLOGY, NAME, or REF
              attributes on both surfaces. Sharing a CI platform, workflow template, or deployment tool alone is not
              evidence unless one repository builds, provisions, configures, or hosts the other's deployable artifact.
            - SHARES_STATE: two REQUIRES surfaces sharing the same resource name/reference in cited NAME or REF.
            - PEER: domain-level peer relationship; cite synopsis fragments and domain NAME/REF attributes on both sides
              (never endpoints, package coordinates, or technologies).
            Using synopses to challenge noisy surface roles and set direction:
            - Surface roles (PROVIDES/REQUIRES) are imperfect single-repository estimates: for example, a client sending
              events to a server and the server hosting the WebSocket endpoint may both be labeled PROVIDES, or two
              services communicating via an external cloud broker may both be labeled REQUIRES. On MESSAGES and DEPLOYS,
              do NOT skip a genuine match just because both surfaces share the same PROVIDES or REQUIRES label — use
              both repositories' synopses, roles, and shared ecosystem context (such as a shared internal framework/SDK
              named in the synopses) to connect client event/runtime surfaces to their server counterparts.
            - Set direction to reflect architectural dependency flow: A_TO_B when Subject (A) is the upstream server,
              provider, publisher, or host and Other (B) is the client, consumer, or hosted app; B_TO_A when Other (B)
              is upstream and Subject (A) is downstream; or BIDIRECTIONAL for SHARES_STATE, PEER, or symmetric coupling.
              Cite supporting synopsis fragments (f1, f2, ...) on both sides when using synopsis context to resolve
              direction or bridge SDK-mediated surfaces.""";

    private final BatchChatClientFactory chatClients;
    private final IngestionBatchRepository ingestionBatchRepository;
    private final TransactionTemplate transactionTemplate;
    private final IngestionProperties ingestionProperties;

    public CrossRepoMatchingService(
            BatchChatClientFactory chatClients,
            IngestionBatchRepository ingestionBatchRepository,
            PlatformTransactionManager transactionManager,
            IngestionProperties ingestionProperties) {
        this.chatClients = chatClients;
        this.ingestionBatchRepository = ingestionBatchRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ingestionProperties = ingestionProperties;
    }

    public record SynopsisView(UUID batchId, String repoRole, List<String> knownAs, SynopsisFragments fragments) {
        public static SynopsisView of(CtxRepoSynopsis row) {
            return new SynopsisView(
                    row.getBatch().getId(),
                    row.getRepoRole().name(),
                    row.getKnownAs(),
                    new SynopsisFragments(row.getSynopsis()));
        }
    }

    public record AttributeView(UUID id, String kind, String value, SurfaceDetection detectedBy) {}

    public record SurfaceView(
            UUID id,
            SurfaceRole role,
            String description,
            SurfaceDetection detectedBy,
            List<AttributeView> attributes) {}

    public record RepoView(
            UUID batchId, UUID repositoryId, String repoName, SynopsisView synopsis, List<SurfaceView> surfaces) {}

    public record AcceptedProposal(UUID otherBatchId, InferenceResponse.RelationProposal proposal) {}

    public record InferenceResult(List<AcceptedProposal> accepted, int discarded) {}

    public InferenceResult infer(UUID batchId, RepoView subject, List<RepoView> others, BatchLogger batchLogger) {
        if (subject.surfaces().isEmpty()
                || others.stream().allMatch(other -> other.surfaces().isEmpty())) {
            batchLogger.info(STEP, "No surface pairs to compare; no work omitted.");
            return new InferenceResult(List.of(), 0);
        }

        List<PeerPlan> plans = new ArrayList<>(others.size());
        boolean anyLlmWork = false;
        for (RepoView other : others) {
            if (other.surfaces().isEmpty()) {
                batchLogger.info(STEP, "No surfaces in repository " + other.repositoryId() + "; no pairs omitted.");
                continue;
            }
            List<InferenceResponse.RelationProposal> directCandidates = directBuildMatches(subject, other);
            boolean directValidationFailed = directCandidates.stream()
                    .anyMatch(candidate ->
                            validate(candidate, subject, subject.surfaces(), other, other.surfaces()) != null);
            List<SurfaceView> subjectPromptSurfaces = directValidationFailed
                    ? subject.surfaces()
                    : trimPromptSurfaces(subject.surfaces(), other.surfaces());
            List<SurfaceView> otherPromptSurfaces = directValidationFailed
                    ? other.surfaces()
                    : trimPromptSurfaces(other.surfaces(), subject.surfaces());
            boolean needsLlm = !subjectPromptSurfaces.isEmpty() && !otherPromptSurfaces.isEmpty();
            if (needsLlm) {
                anyLlmWork = true;
            }
            plans.add(new PeerPlan(other, directCandidates, subjectPromptSurfaces, otherPromptSurfaces, needsLlm));
        }

        record ClientBudget(ChatClient chatClient, int maxPromptChars) {}
        ClientBudget clientBudget = anyLlmWork
                ? transactionTemplate.execute(status -> {
                    IngestionBatch batch = loadBatch(batchId);
                    return new ClientBudget(chatClients.chatClientFor(batch), chatClients.maxPromptCharsFor(batch));
                })
                : new ClientBudget(null, 0);
        BeanOutputConverter<InferenceResponse> converter = new BeanOutputConverter<>(InferenceResponse.class);
        String format = converter.getFormat();
        IngestionProperties.Matching matching = ingestionProperties.getMatching();
        Semaphore semaphore = new Semaphore(Math.max(1, matching.getMaxConcurrency()));

        List<Future<PeerOutcome>> futures = new ArrayList<>(plans.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (PeerPlan plan : plans) {
                futures.add(executor.submit(() -> evaluatePeer(
                        batchId,
                        subject,
                        plan,
                        clientBudget.chatClient(),
                        clientBudget.maxPromptChars(),
                        converter,
                        format,
                        matching,
                        semaphore)));
            }

            List<AcceptedProposal> accepted = new ArrayList<>();
            Set<String> acceptedEvidence = new HashSet<>();
            int discarded = 0;
            for (Future<PeerOutcome> future : futures) {
                PeerOutcome outcome = awaitOutcome(future);
                for (PeerLog logEntry : outcome.logs()) {
                    logEntry.writeTo(batchLogger);
                }
                if (outcome.failure() != null) {
                    throw outcome.failure();
                }
                discarded += outcome.discarded();
                for (InferenceResponse.RelationProposal proposal : outcome.accepted()) {
                    if (acceptedEvidence.add(evidenceKey(proposal))) {
                        accepted.add(new AcceptedProposal(outcome.otherBatchId(), proposal));
                    }
                }
            }
            return new InferenceResult(List.copyOf(accepted), discarded);
        }
    }

    private static PeerOutcome awaitOutcome(Future<PeerOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for surface-matching task", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Surface-matching task failed: " + cause.getMessage(), cause);
        }
    }

    private static PeerOutcome evaluatePeer(
            UUID batchId,
            RepoView subject,
            PeerPlan plan,
            ChatClient chatClient,
            int maxPromptChars,
            BeanOutputConverter<InferenceResponse> converter,
            String format,
            IngestionProperties.Matching matching,
            Semaphore semaphore) {
        RepoView other = plan.other();
        List<PeerLog> logs = new ArrayList<>();
        List<InferenceResponse.RelationProposal> accepted = new ArrayList<>();
        Set<String> localEvidence = new HashSet<>();
        Set<String> directBuildPairs = new HashSet<>();
        Set<Nature> acceptedNatures = EnumSet.noneOf(Nature.class);

        int discarded = 0;
        for (InferenceResponse.RelationProposal proposal : plan.directMatches()) {
            String reason = validate(proposal, subject, subject.surfaces(), other, other.surfaces());
            if (reason != null) {
                discarded++;
                logs.add(PeerLog.warn("Deterministic package match candidate rejected ("
                        + reason
                        + "); deferring to LLM comparison."));
                continue;
            }
            if (localEvidence.add(evidenceKey(proposal))) {
                accepted.add(proposal);
                acceptedNatures.add(proposal.nature());
                directBuildPairs.add(surfacePairKey(proposal));
                logs.add(PeerLog.info("Matched a direct package coordinate from " + subject.repositoryId() + " to "
                        + other.repositoryId() + " (" + proposal.subjectSurfaceId() + " -> "
                        + proposal.otherSurfaceId() + ")."));
            }
        }
        if (!plan.needsLlm()) {
            return new PeerOutcome(other.batchId(), accepted, discarded, logs, null);
        }

        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            int fixedOverhead = SYSTEM_PROMPT.length()
                    + format.length()
                    + renderRepoHeader(SUBJECT_REPO_ALIAS, subject).length()
                    + renderRepoHeader(OTHER_REPO_ALIAS, other).length()
                    + PROMPT_SAFETY_MARGIN;
            int availableBudget = maxPromptChars - fixedOverhead;
            if (availableBudget <= 0) {
                logs.add(PeerLog.error(
                        "Matching prompt exceeds configured character limit; comparison omitted and activation aborted for "
                                + subject.repositoryId()
                                + " and "
                                + other.repositoryId()));
                return new PeerOutcome(
                        other.batchId(),
                        List.of(),
                        0,
                        logs,
                        new IllegalStateException(
                                "Surface matching prompt exceeds character limit for batch " + batchId));
            }

            List<List<SurfaceView>> leftGroups;
            List<List<SurfaceView>> rightGroups;
            int totalSurfaceChars = totalRenderedChars("s", "a", plan.subjectSurfaces())
                    + totalRenderedChars("o", "b", plan.otherSurfaces());
            if (totalSurfaceChars <= availableBudget) {
                leftGroups = List.of(plan.subjectSurfaces());
                rightGroups = List.of(plan.otherSurfaces());
            } else {
                int halfBudget = Math.max(1, availableBudget / 2);
                leftGroups = groups(plan.subjectSurfaces(), halfBudget);
                rightGroups = groups(plan.otherSurfaces(), halfBudget);
            }

            for (List<SurfaceView> left : leftGroups) {
                for (List<SurfaceView> right : rightGroups) {
                    AliasTable aliases = AliasTable.build(subject, left, other, right);
                    String userPrompt = buildUserPrompt(subject, left, other, right, aliases, format);
                    if (SYSTEM_PROMPT.length() + userPrompt.length() > maxPromptChars) {
                        logs.add(PeerLog.error(
                                "Matching prompt exceeds configured character limit; comparison omitted and activation aborted for "
                                        + subject.repositoryId()
                                        + " and "
                                        + other.repositoryId()));
                        return new PeerOutcome(
                                other.batchId(),
                                List.of(),
                                discarded,
                                logs,
                                new IllegalStateException(
                                        "Surface matching prompt exceeds character limit for batch " + batchId));
                    }
                    InferenceResponse response =
                            callWithRetry(batchId, chatClient, userPrompt, converter, matching, logs);
                    GroupPassResult firstPass = evaluateGroupResponse(
                            response,
                            aliases,
                            subject,
                            left,
                            other,
                            right,
                            directBuildPairs,
                            localEvidence,
                            acceptedNatures,
                            accepted);
                    int groupAccepted = firstPass.acceptedCount();
                    List<String> unrecoverableRejections = new ArrayList<>();
                    List<String> recoverableRejections = new ArrayList<>();
                    for (RejectedCandidate rejected : firstPass.rejections()) {
                        if (rejected.nature() != null
                                && (acceptedNatures.contains(rejected.nature())
                                        || !isNatureSatisfiable(rejected.nature(), subject, left, other, right))) {
                            unrecoverableRejections.add(rejected.message());
                        } else {
                            recoverableRejections.add(rejected.message());
                        }
                    }
                    if (!recoverableRejections.isEmpty()) {
                        String correctionPrompt =
                                buildCorrectionPrompt(userPrompt, response, recoverableRejections, converter);
                        logs.add(PeerLog.info("Requesting 1 validation-correction pass for "
                                + subject.repositoryId()
                                + " vs "
                                + other.repositoryId()
                                + " ("
                                + recoverableRejections.size()
                                + " recoverable candidate(s))."));
                        InferenceResponse corrected =
                                callWithRetry(batchId, chatClient, correctionPrompt, converter, matching, logs);
                        GroupPassResult secondPass = evaluateGroupResponse(
                                corrected,
                                aliases,
                                subject,
                                left,
                                other,
                                right,
                                directBuildPairs,
                                localEvidence,
                                acceptedNatures,
                                accepted);
                        groupAccepted += secondPass.acceptedCount();
                        for (RejectedCandidate rejected : secondPass.rejections()) {
                            if (rejected.nature() == null || !acceptedNatures.contains(rejected.nature())) {
                                unrecoverableRejections.add(rejected.message());
                            }
                        }
                    }
                    for (String rejection : unrecoverableRejections) {
                        discarded++;
                        logs.add(PeerLog.warn(rejection));
                    }
                    logs.add(PeerLog.info("Compared repository " + subject.repositoryId() + " surfaces "
                            + left.stream().map(SurfaceView::id).toList() + " against repository "
                            + other.repositoryId()
                            + " surfaces "
                            + right.stream().map(SurfaceView::id).toList() + "; accepted " + groupAccepted
                            + "; no work omitted for this group."));
                }
            }
            return new PeerOutcome(other.batchId(), accepted, discarded, logs, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PeerOutcome(
                    other.batchId(),
                    List.of(),
                    discarded,
                    logs,
                    new IllegalStateException("Interrupted while waiting for surface-matching permit", e));
        } catch (RuntimeException e) {
            return new PeerOutcome(other.batchId(), List.of(), discarded, logs, e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private static GroupPassResult evaluateGroupResponse(
            InferenceResponse response,
            AliasTable aliases,
            RepoView subject,
            List<SurfaceView> left,
            RepoView other,
            List<SurfaceView> right,
            Set<String> directBuildPairs,
            Set<String> localEvidence,
            Set<Nature> acceptedNatures,
            List<InferenceResponse.RelationProposal> accepted) {
        int count = 0;
        List<RejectedCandidate> rejections = new ArrayList<>();
        for (InferenceResponse.RelationProposal rawItem : response.matches()) {
            for (InferenceResponse.RelationProposal raw : expandMultiSurfaceProposal(rawItem)) {
                InferenceResponse.RelationProposal aligned = alignToSubject(aliases.resolve(raw), subject, other);
                InferenceResponse.RelationProposal prepared =
                        prepareForValidation(aligned, subject, left, other, right);
                String reason = validate(prepared, subject, left, other, right);
                if (reason != null) {
                    rejections.add(new RejectedCandidate(
                            raw.nature(),
                            "Surface-match candidate nature=" + raw.nature()
                                    + " subjectRepository=" + raw.subjectRepositoryId()
                                    + " subjectSurface=" + raw.subjectSurfaceId()
                                    + " otherRepository=" + raw.otherRepositoryId()
                                    + " otherSurface=" + raw.otherSurfaceId()
                                    + " rejected: " + reason));
                } else {
                    if (prepared.nature() == Nature.BUILDS_AGAINST
                            && directBuildPairs.contains(surfacePairKey(prepared))) {
                        continue;
                    }
                    if (localEvidence.add(evidenceKey(prepared))) {
                        accepted.add(prepared);
                        acceptedNatures.add(prepared.nature());
                        count++;
                    }
                }
            }
        }
        return new GroupPassResult(count, List.copyOf(rejections));
    }

    private static List<InferenceResponse.RelationProposal> expandMultiSurfaceProposal(
            InferenceResponse.RelationProposal raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> subjectTokens = splitSurfaceField(raw.subjectSurfaceId());
        List<String> otherTokens = splitSurfaceField(raw.otherSurfaceId());
        if (subjectTokens.size() <= 1 && otherTokens.size() <= 1) {
            return List.of(raw);
        }
        List<InferenceResponse.RelationProposal> expanded = new ArrayList<>();
        for (String sTok : subjectTokens) {
            for (String oTok : otherTokens) {
                expanded.add(new InferenceResponse.RelationProposal(
                        raw.subjectRepositoryId(),
                        sTok,
                        raw.otherRepositoryId(),
                        oTok,
                        raw.nature(),
                        raw.direction(),
                        raw.confidence(),
                        raw.rationale(),
                        raw.subjectAttributeIds(),
                        raw.otherAttributeIds(),
                        raw.subjectSynopsisIds(),
                        raw.otherSynopsisIds()));
            }
        }
        return expanded;
    }

    private static List<String> splitSurfaceField(String field) {
        if (field == null || field.isBlank()) {
            return List.of(field == null ? "" : field);
        }
        List<String> parts = new ArrayList<>();
        for (String part : field.split("[,\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.isEmpty() ? List.of(field) : parts;
    }

    private static boolean isNatureSatisfiable(
            Nature nature, RepoView subject, List<SurfaceView> left, RepoView other, List<SurfaceView> right) {
        return switch (nature) {
            case BUILDS_AGAINST ->
                !directBuildMatches(
                                new RepoView(subject.batchId(), subject.repositoryId(), subject.repoName(), null, left),
                                new RepoView(other.batchId(), other.repositoryId(), other.repoName(), null, right))
                        .isEmpty();
            case SHARES_STATE -> hasSharedRequiresValue(left, right);
            case PEER ->
                subject.synopsis() != null
                        && other.synopsis() != null
                        && hasAnyAttrInGroup(left, "NAME", "REF")
                        && hasAnyAttrInGroup(right, "NAME", "REF");
            case CALLS -> hasOppositeRolePairWithKinds(left, right, "ENDPOINT", "IDENTIFIER", "REF");
            case MESSAGES ->
                hasAnyAttrInGroup(left, "NAME", "IDENTIFIER", "ENDPOINT", "REF", "METHOD")
                        && hasAnyAttrInGroup(right, "NAME", "IDENTIFIER", "ENDPOINT", "REF", "METHOD");
            case DEPLOYS ->
                hasAnyAttrInGroup(left, "FILE", "IDENTIFIER", "TECHNOLOGY", "NAME", "REF")
                        && hasAnyAttrInGroup(right, "FILE", "IDENTIFIER", "TECHNOLOGY", "NAME", "REF");
        };
    }

    private static boolean hasSharedRequiresValue(List<SurfaceView> left, List<SurfaceView> right) {
        Set<String> leftValues = new HashSet<>();
        for (SurfaceView surface : left) {
            if (surface.role() != SurfaceRole.REQUIRES) {
                continue;
            }
            for (AttributeView attribute : surface.attributes()) {
                if ("NAME".equals(attribute.kind()) || "REF".equals(attribute.kind())) {
                    leftValues.add(attribute.value());
                }
            }
        }
        if (leftValues.isEmpty()) {
            return false;
        }
        for (SurfaceView surface : right) {
            if (surface.role() != SurfaceRole.REQUIRES) {
                continue;
            }
            for (AttributeView attribute : surface.attributes()) {
                if (("NAME".equals(attribute.kind()) || "REF".equals(attribute.kind()))
                        && leftValues.contains(attribute.value())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasOppositeRolePairWithKinds(
            List<SurfaceView> left, List<SurfaceView> right, String... kinds) {
        for (SurfaceView l : left) {
            if (!hasAnyKind(l.attributes(), kinds)) {
                continue;
            }
            for (SurfaceView r : right) {
                boolean opposite = (l.role() == SurfaceRole.PROVIDES && r.role() == SurfaceRole.REQUIRES)
                        || (l.role() == SurfaceRole.REQUIRES && r.role() == SurfaceRole.PROVIDES);
                if (opposite && hasAnyKind(r.attributes(), kinds)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasAnyAttrInGroup(List<SurfaceView> group, String... kinds) {
        for (SurfaceView surface : group) {
            if (surface.role() != SurfaceRole.UNKNOWN
                    && (surface.attributes().isEmpty() || hasAnyKind(surface.attributes(), kinds))) {
                return true;
            }
        }
        return false;
    }

    private static String buildCorrectionPrompt(
            String originalUserPrompt,
            InferenceResponse previousResponse,
            List<String> rejections,
            BeanOutputConverter<InferenceResponse> converter) {
        StringBuilder builder =
                new StringBuilder(originalUserPrompt).append("\nPrevious matches rejected by validation:\n");
        for (String rejection : rejections) {
            builder.append("- ").append(rejection).append('\n');
        }
        builder.append("Previous proposal count=")
                .append(previousResponse.matches().size())
                .append(".\n");
        builder.append(
                "Return the corrected full matches list using ONLY exact supplied surface aliases (s1, o1) and their own scoped attribute aliases (s1.a1, o1.b1). Omit any unsupported match.\n");
        builder.append(converter.getFormat());
        return builder.toString();
    }

    private static InferenceResponse.RelationProposal prepareForValidation(
            InferenceResponse.RelationProposal match,
            RepoView subject,
            List<SurfaceView> left,
            RepoView other,
            List<SurfaceView> right) {
        if (match == null) {
            return null;
        }
        SurfaceView subjectSurface = findSurface(left, uuid(match.subjectSurfaceId()));
        if (subjectSurface != null
                && match.subjectAttributeIds() != null
                && !match.subjectAttributeIds().isEmpty()
                && countValidAttributes(subjectSurface, match.subjectAttributeIds()) == 0) {
            SurfaceView rescued = rescueOwnerSurface(left, match.subjectAttributeIds());
            if (rescued != null) {
                subjectSurface = rescued;
            }
        }
        SurfaceView otherSurface = findSurface(right, uuid(match.otherSurfaceId()));
        if (otherSurface != null
                && match.otherAttributeIds() != null
                && !match.otherAttributeIds().isEmpty()
                && countValidAttributes(otherSurface, match.otherAttributeIds()) == 0) {
            SurfaceView rescued = rescueOwnerSurface(right, match.otherAttributeIds());
            if (rescued != null) {
                otherSurface = rescued;
            }
        }
        String effectiveSubjectSurfaceId =
                subjectSurface != null ? subjectSurface.id().toString() : match.subjectSurfaceId();
        String effectiveOtherSurfaceId =
                otherSurface != null ? otherSurface.id().toString() : match.otherSurfaceId();
        List<String> filteredSubjectAttrs = subjectSurface == null
                ? match.subjectAttributeIds()
                : filterValidAttributes(subjectSurface, match.subjectAttributeIds());
        List<String> filteredOtherAttrs = otherSurface == null
                ? match.otherAttributeIds()
                : filterValidAttributes(otherSurface, match.otherAttributeIds());
        List<String> filteredSubjectFragments = filterValidFragments(subject.synopsis(), match.subjectSynopsisIds());
        List<String> filteredOtherFragments = filterValidFragments(other.synopsis(), match.otherSynopsisIds());
        InferenceResponse.RelationProposal cleaned = new InferenceResponse.RelationProposal(
                match.subjectRepositoryId(),
                effectiveSubjectSurfaceId,
                match.otherRepositoryId(),
                effectiveOtherSurfaceId,
                match.nature(),
                match.direction(),
                match.confidence(),
                match.rationale(),
                filteredSubjectAttrs,
                filteredOtherAttrs,
                filteredSubjectFragments,
                filteredOtherFragments);
        return normalizeDirection(cleaned, left, right);
    }

    private static SurfaceView findSurface(List<SurfaceView> surfaces, UUID surfaceId) {
        if (surfaceId == null) {
            return null;
        }
        return surfaces.stream()
                .filter(s -> Objects.equals(s.id(), surfaceId))
                .findFirst()
                .orElse(null);
    }

    private static long countValidAttributes(SurfaceView surface, List<String> ids) {
        return ids.stream().filter(id -> attributeId(surface, id) != null).count();
    }

    private static SurfaceView rescueOwnerSurface(List<SurfaceView> surfaces, List<String> attributeIds) {
        if (attributeIds == null || attributeIds.isEmpty()) {
            return null;
        }
        SurfaceView uniqueOwner = null;
        for (String id : attributeIds) {
            UUID parsed = uuid(id);
            if (parsed == null) {
                continue;
            }
            for (SurfaceView candidate : surfaces) {
                if (attributeId(candidate, id) != null) {
                    if (uniqueOwner != null && !uniqueOwner.id().equals(candidate.id())) {
                        return null;
                    }
                    uniqueOwner = candidate;
                }
            }
        }
        return uniqueOwner;
    }

    private static List<String> filterValidAttributes(SurfaceView surface, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return ids;
        }
        List<String> valid =
                ids.stream().filter(id -> attributeId(surface, id) != null).toList();
        return valid.isEmpty() ? ids : valid;
    }

    private static List<String> filterValidFragments(SynopsisView synopsis, List<String> ids) {
        if (ids == null || ids.isEmpty() || synopsis == null) {
            return ids;
        }
        return ids.stream()
                .filter(id -> id != null
                        && id.matches("f[1-9][0-9]*")
                        && synopsis.fragments().text(id) != null)
                .toList();
    }

    private static InferenceResponse callWithRetry(
            UUID batchId,
            ChatClient chatClient,
            String userPrompt,
            BeanOutputConverter<InferenceResponse> converter,
            IngestionProperties.Matching matching,
            List<PeerLog> logs) {
        int maxRetries = Math.max(0, matching.getMaxRetries());
        long backoffMs = Math.max(0L, matching.getInitialBackoffMs());
        IllegalStateException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String content = Objects.requireNonNull(chatClient, "chatClient is required")
                        .prompt()
                        .system(SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .content();
                if (content == null || content.isBlank()) {
                    throw new IllegalStateException("Surface matching returned no content for batch " + batchId);
                }
                InferenceResponse response = converter.convert(content);
                if (response == null || response.matches() == null) {
                    throw new IllegalStateException(
                            "Surface matching returned an invalid response for batch " + batchId);
                }
                return response;
            } catch (IllegalStateException e) {
                lastFailure = e;
            } catch (RuntimeException e) {
                lastFailure = new IllegalStateException(
                        "Surface matching returned an invalid response for batch " + batchId + ": " + e.getMessage(),
                        e);
            }
            if (attempt < maxRetries) {
                logs.add(PeerLog.warn("Retrying surface-matching call after attempt "
                        + (attempt + 1)
                        + " failed: "
                        + lastFailure.getMessage()));
                if (backoffMs > 0) {
                    try {
                        Thread.sleep(backoffMs * (1L << attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted during surface-matching retry backoff", ie);
                    }
                }
            }
        }
        throw Objects.requireNonNull(lastFailure);
    }

    private static InferenceResponse.RelationProposal alignToSubject(
            InferenceResponse.RelationProposal proposal, RepoView subject, RepoView other) {
        if (proposal == null) {
            return null;
        }
        String subjectId = subject.repositoryId().toString();
        String otherId = other.repositoryId().toString();
        if (!Objects.equals(subjectId, otherId)
                && Objects.equals(otherId, proposal.subjectRepositoryId())
                && Objects.equals(subjectId, proposal.otherRepositoryId())) {
            Direction reversed = proposal.direction() == null
                    ? null
                    : switch (proposal.direction()) {
                        case A_TO_B -> Direction.B_TO_A;
                        case B_TO_A -> Direction.A_TO_B;
                        case BIDIRECTIONAL -> Direction.BIDIRECTIONAL;
                    };
            return new InferenceResponse.RelationProposal(
                    proposal.otherRepositoryId(),
                    proposal.otherSurfaceId(),
                    proposal.subjectRepositoryId(),
                    proposal.subjectSurfaceId(),
                    proposal.nature(),
                    reversed,
                    proposal.confidence(),
                    proposal.rationale(),
                    proposal.otherAttributeIds(),
                    proposal.subjectAttributeIds(),
                    proposal.otherSynopsisIds(),
                    proposal.subjectSynopsisIds());
        }
        return proposal;
    }

    static List<SurfaceView> trimPromptSurfaces(List<SurfaceView> surfaces, List<SurfaceView> peerSurfaces) {
        Set<String> peerCoordinates = new HashSet<>();
        for (SurfaceView peer : peerSurfaces) {
            for (AttributeView attribute : peer.attributes()) {
                if ("IDENTIFIER".equals(attribute.kind())) {
                    String coordinate = packageCoordinate(attribute.value());
                    if (coordinate != null) {
                        peerCoordinates.add(coordinate);
                    }
                }
            }
        }
        List<SurfaceView> trimmed = new ArrayList<>(surfaces.size());
        for (SurfaceView surface : surfaces) {
            if (surface.detectedBy() == SurfaceDetection.SYFT && surface.role() == SurfaceRole.REQUIRES) {
                List<AttributeView> kept = surface.attributes().stream()
                        .filter(attribute -> {
                            if (!"IDENTIFIER".equals(attribute.kind())) {
                                return true;
                            }
                            String coordinate = packageCoordinate(attribute.value());
                            return coordinate == null || peerCoordinates.contains(coordinate);
                        })
                        .toList();
                if (!kept.isEmpty()) {
                    trimmed.add(new SurfaceView(
                            surface.id(), surface.role(), surface.description(), surface.detectedBy(), kept));
                }
            } else {
                trimmed.add(surface);
            }
        }
        return List.copyOf(trimmed);
    }

    static List<List<SurfaceView>> groups(List<SurfaceView> surfaces, int maxGroupChars) {
        if (surfaces.isEmpty()) {
            return List.of();
        }
        List<List<SurfaceView>> result = new ArrayList<>();
        List<SurfaceView> current = new ArrayList<>();
        int size = 0;
        int surfaceIndex = 1;
        int attributeIndex = 1;
        for (SurfaceView surface : surfaces) {
            int chars = renderSurface("s" + surfaceIndex++, "a", attributeIndex, surface)
                    .length();
            attributeIndex += surface.attributes().size();
            if (chars > maxGroupChars) {
                throw new IllegalStateException(
                        "Surface " + surface.id() + " exceeds matching input limit; comparison omitted");
            }
            if (!current.isEmpty() && size + chars > maxGroupChars) {
                result.add(List.copyOf(current));
                current = new ArrayList<>();
                size = 0;
            }
            current.add(surface);
            size += chars;
        }
        if (!current.isEmpty()) {
            result.add(List.copyOf(current));
        }
        return List.copyOf(result);
    }

    static String validate(
            InferenceResponse.RelationProposal match,
            RepoView subject,
            List<SurfaceView> left,
            RepoView other,
            List<SurfaceView> right) {
        if (match == null
                || !Objects.equals(uuid(match.subjectRepositoryId()), subject.repositoryId())
                || !Objects.equals(uuid(match.otherRepositoryId()), other.repositoryId())
                || Objects.equals(subject.repositoryId(), other.repositoryId())) {
            return "repository IDs do not identify the supplied pair";
        }
        UUID subjectSurfaceId = uuid(match.subjectSurfaceId());
        UUID otherSurfaceId = uuid(match.otherSurfaceId());
        if (subjectSurfaceId == null || otherSurfaceId == null) {
            return "surface IDs are not valid UUIDs";
        }
        SurfaceView subjectSurface = left.stream()
                .filter(s -> Objects.equals(s.id(), subjectSurfaceId))
                .findFirst()
                .orElse(null);
        SurfaceView otherSurface = right.stream()
                .filter(s -> Objects.equals(s.id(), otherSurfaceId))
                .findFirst()
                .orElse(null);
        if (subjectSurface == null || otherSurface == null) {
            return "surface IDs do not identify the supplied group";
        }
        if (match.nature() == null
                || match.direction() == null
                || match.confidence() == null
                || match.rationale() == null
                || match.rationale().isBlank()) {
            return "nature, direction, confidence and rationale are required";
        }
        if (!validEvidence(subjectSurface, subject.synopsis(), match.subjectAttributeIds(), match.subjectSynopsisIds())
                || !validEvidence(
                        otherSurface, other.synopsis(), match.otherAttributeIds(), match.otherSynopsisIds())) {
            return "evidence is missing or does not belong to the supplied surface or synopsis";
        }
        return validateNatureEvidence(match, subjectSurface, otherSurface);
    }

    private static boolean validEvidence(
            SurfaceView surface, SynopsisView synopsis, List<String> attributes, List<String> fragments) {
        if (attributes == null
                || fragments == null
                || (attributes.isEmpty()
                        && (!surface.attributes().isEmpty()
                                || surface.description() == null
                                || surface.description().isBlank()))) {
            return false;
        }
        return attributes.stream().allMatch(id -> attributeId(surface, id) != null)
                && fragments.stream()
                        .allMatch(id -> id != null
                                && id.matches("f[1-9][0-9]*")
                                && synopsis != null
                                && synopsis.fragments().text(id) != null);
    }

    private static String validateNatureEvidence(
            InferenceResponse.RelationProposal match, SurfaceView subject, SurfaceView other) {
        List<AttributeView> subjectEvidence = citedAttributes(subject, match.subjectAttributeIds());
        List<AttributeView> otherEvidence = citedAttributes(other, match.otherAttributeIds());
        return switch (match.nature()) {
            case CALLS, BUILDS_AGAINST ->
                validateDirectedEvidence(match, subject, other, subjectEvidence, otherEvidence);
            case MESSAGES -> validateMessagesEvidence(subject, other, subjectEvidence, otherEvidence);
            case DEPLOYS -> validateDeploysEvidence(subject, other, subjectEvidence, otherEvidence);
            case SHARES_STATE -> validateSharedStateEvidence(match, subject, other, subjectEvidence, otherEvidence);
            case PEER -> validatePeerEvidence(match, subject, other, subjectEvidence, otherEvidence);
        };
    }

    private static String validateMessagesEvidence(
            SurfaceView subject,
            SurfaceView other,
            List<AttributeView> subjectEvidence,
            List<AttributeView> otherEvidence) {
        if (subject.role() == SurfaceRole.UNKNOWN || other.role() == SurfaceRole.UNKNOWN) {
            return "MESSAGES requires classified surfaces";
        }
        return hasAnyKind(subjectEvidence, "NAME", "IDENTIFIER", "ENDPOINT", "REF", "METHOD")
                        && hasAnyKind(otherEvidence, "NAME", "IDENTIFIER", "ENDPOINT", "REF", "METHOD")
                ? null
                : "MESSAGES requires cited message or endpoint identity evidence on both surfaces";
    }

    private static String validateDeploysEvidence(
            SurfaceView subject,
            SurfaceView other,
            List<AttributeView> subjectEvidence,
            List<AttributeView> otherEvidence) {
        if (subject.role() == SurfaceRole.UNKNOWN || other.role() == SurfaceRole.UNKNOWN) {
            return "DEPLOYS requires classified surfaces";
        }
        return hasAnyKind(subjectEvidence, "FILE", "IDENTIFIER", "TECHNOLOGY", "NAME", "REF")
                        && hasAnyKind(otherEvidence, "FILE", "IDENTIFIER", "TECHNOLOGY", "NAME", "REF")
                ? null
                : "DEPLOYS requires cited deployment or artifact evidence on both surfaces";
    }

    private static String validateDirectedEvidence(
            InferenceResponse.RelationProposal match,
            SurfaceView subject,
            SurfaceView other,
            List<AttributeView> subjectEvidence,
            List<AttributeView> otherEvidence) {
        boolean subjectProvides = subject.role() == SurfaceRole.PROVIDES;
        boolean subjectRequires = subject.role() == SurfaceRole.REQUIRES;
        boolean otherProvides = other.role() == SurfaceRole.PROVIDES;
        boolean otherRequires = other.role() == SurfaceRole.REQUIRES;
        if (!(subjectProvides && otherRequires || subjectRequires && otherProvides)) {
            return match.nature() + " requires one PROVIDES surface and one REQUIRES surface";
        }
        return switch (match.nature()) {
            case CALLS ->
                hasAnyKind(subjectEvidence, "ENDPOINT", "IDENTIFIER", "REF")
                                && hasAnyKind(otherEvidence, "ENDPOINT", "IDENTIFIER", "REF")
                        ? null
                        : "CALLS requires cited endpoint or interface identity evidence on both surfaces";
            case BUILDS_AGAINST ->
                sharesPackageCoordinate(subjectEvidence, otherEvidence)
                        ? null
                        : "BUILDS_AGAINST requires the provided and required package coordinates to match";
            case MESSAGES, DEPLOYS, SHARES_STATE, PEER -> "Invalid directed relation nature";
        };
    }

    static List<InferenceResponse.RelationProposal> directBuildMatches(RepoView subject, RepoView other) {
        List<InferenceResponse.RelationProposal> matches = new ArrayList<>();
        for (SurfaceView subjectSurface : subject.surfaces()) {
            for (SurfaceView otherSurface : other.surfaces()) {
                SurfaceView provider = subjectSurface.role() == SurfaceRole.PROVIDES
                        ? subjectSurface
                        : otherSurface.role() == SurfaceRole.PROVIDES ? otherSurface : null;
                SurfaceView consumer = subjectSurface.role() == SurfaceRole.REQUIRES
                        ? subjectSurface
                        : otherSurface.role() == SurfaceRole.REQUIRES ? otherSurface : null;
                if (provider == null || consumer == null || provider.id().equals(consumer.id())) {
                    continue;
                }
                for (AttributeView provided : provider.attributes()) {
                    if (!provided.kind().equals("IDENTIFIER")) {
                        continue;
                    }
                    String coordinate = packageCoordinate(provided.value());
                    if (coordinate == null) {
                        continue;
                    }
                    for (AttributeView required : consumer.attributes()) {
                        if (!required.kind().equals("IDENTIFIER")
                                || !coordinate.equals(packageCoordinate(required.value()))) {
                            continue;
                        }
                        boolean providerIsSubject = provider.id().equals(subjectSurface.id());
                        SurfaceView subjectSurfaceForMatch = providerIsSubject ? provider : consumer;
                        SurfaceView otherSurfaceForMatch = providerIsSubject ? consumer : provider;
                        matches.add(new InferenceResponse.RelationProposal(
                                subject.repositoryId().toString(),
                                subjectSurfaceForMatch.id().toString(),
                                other.repositoryId().toString(),
                                otherSurfaceForMatch.id().toString(),
                                Nature.BUILDS_AGAINST,
                                providerIsSubject ? Direction.A_TO_B : Direction.B_TO_A,
                                Ceiling.CONFIRMED,
                                "The direct dependency package coordinate " + required.value()
                                        + " matches the package identity " + provided.value() + ".",
                                List.of(
                                        subjectSurfaceForMatch.id().equals(provider.id())
                                                ? String.valueOf(provided.id())
                                                : String.valueOf(required.id())),
                                List.of(
                                        subjectSurfaceForMatch.id().equals(provider.id())
                                                ? String.valueOf(required.id())
                                                : String.valueOf(provided.id())),
                                List.of(),
                                List.of()));
                    }
                }
            }
        }
        return matches;
    }

    private static String surfacePairKey(InferenceResponse.RelationProposal proposal) {
        return proposal.nature() + "|" + proposal.subjectSurfaceId() + "|" + proposal.otherSurfaceId();
    }

    private static String evidenceKey(InferenceResponse.RelationProposal proposal) {
        return proposal.nature() + "|" + proposal.subjectSurfaceId() + "|" + proposal.otherSurfaceId() + "|"
                + String.join(
                        ",", proposal.subjectAttributeIds().stream().sorted().toList()) + "|"
                + String.join(
                        ",", proposal.otherAttributeIds().stream().sorted().toList());
    }

    static InferenceResponse.RelationProposal normalizeDirection(
            InferenceResponse.RelationProposal match,
            List<SurfaceView> subjectSurfaces,
            List<SurfaceView> otherSurfaces) {
        if (match.nature() == null) {
            return match;
        }
        if (match.nature() == Nature.PEER || match.nature() == Nature.SHARES_STATE) {
            return withDirection(match, Direction.BIDIRECTIONAL);
        }
        UUID subjectId = uuid(match.subjectSurfaceId());
        UUID otherId = uuid(match.otherSurfaceId());
        SurfaceView subject = subjectSurfaces.stream()
                .filter(surface -> surface.id().equals(subjectId))
                .findFirst()
                .orElse(null);
        SurfaceView other = otherSurfaces.stream()
                .filter(surface -> surface.id().equals(otherId))
                .findFirst()
                .orElse(null);
        if (subject == null || other == null) {
            return match;
        }
        if (match.nature() == Nature.MESSAGES || match.nature() == Nature.DEPLOYS) {
            if (subject.role() == other.role()) {
                return match.direction() == null ? withDirection(match, Direction.BIDIRECTIONAL) : match;
            }
            if (hasSynopsisChallenge(match) && match.direction() != null) {
                return match;
            }
            Direction expected = subject.role() == SurfaceRole.PROVIDES ? Direction.A_TO_B : Direction.B_TO_A;
            return withDirection(match, expected);
        }
        Direction expected = subject.role() == SurfaceRole.PROVIDES ? Direction.A_TO_B : Direction.B_TO_A;
        return withDirection(match, expected);
    }

    private static boolean hasSynopsisChallenge(InferenceResponse.RelationProposal match) {
        return match.subjectSynopsisIds() != null
                && !match.subjectSynopsisIds().isEmpty()
                && match.otherSynopsisIds() != null
                && !match.otherSynopsisIds().isEmpty();
    }

    private static InferenceResponse.RelationProposal withDirection(
            InferenceResponse.RelationProposal match, Direction direction) {
        if (match.direction() == direction) {
            return match;
        }
        return new InferenceResponse.RelationProposal(
                match.subjectRepositoryId(),
                match.subjectSurfaceId(),
                match.otherRepositoryId(),
                match.otherSurfaceId(),
                match.nature(),
                direction,
                match.confidence(),
                match.rationale(),
                match.subjectAttributeIds(),
                match.otherAttributeIds(),
                match.subjectSynopsisIds(),
                match.otherSynopsisIds());
    }

    private static String validateSharedStateEvidence(
            InferenceResponse.RelationProposal match,
            SurfaceView subject,
            SurfaceView other,
            List<AttributeView> subjectEvidence,
            List<AttributeView> otherEvidence) {
        if (match.direction() != Direction.BIDIRECTIONAL) {
            return "SHARES_STATE must be bidirectional";
        }
        if (subject.role() != SurfaceRole.REQUIRES || other.role() != SurfaceRole.REQUIRES) {
            return "SHARES_STATE requires two resource-consuming surfaces";
        }
        return sharesValueOfKinds(subjectEvidence, otherEvidence, "NAME", "REF")
                ? null
                : "SHARES_STATE requires a shared cited resource name or reference";
    }

    private static String validatePeerEvidence(
            InferenceResponse.RelationProposal match,
            SurfaceView subject,
            SurfaceView other,
            List<AttributeView> subjectEvidence,
            List<AttributeView> otherEvidence) {
        if (match.direction() != Direction.BIDIRECTIONAL) {
            return "PEER must be bidirectional";
        }
        if (subject.role() == SurfaceRole.UNKNOWN || other.role() == SurfaceRole.UNKNOWN) {
            return "PEER requires classified surfaces";
        }
        if (match.subjectSynopsisIds().isEmpty() || match.otherSynopsisIds().isEmpty()) {
            return "PEER requires synopsis evidence for both repositories";
        }
        if (hasAnyKind(subjectEvidence, "ENDPOINT", "IDENTIFIER", "TECHNOLOGY")
                || hasAnyKind(otherEvidence, "ENDPOINT", "IDENTIFIER", "TECHNOLOGY")) {
            return "PEER cannot be supported by technical endpoints, package coordinates, or technologies";
        }
        return hasAnyKind(subjectEvidence, "NAME", "REF") && hasAnyKind(otherEvidence, "NAME", "REF")
                ? null
                : "PEER requires cited domain or product evidence on both surfaces";
    }

    private static List<AttributeView> citedAttributes(SurfaceView surface, List<String> ids) {
        Set<UUID> selected = ids.stream()
                .map(CrossRepoMatchingService::uuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return surface.attributes().stream()
                .filter(attribute -> selected.contains(attribute.id()))
                .toList();
    }

    private static boolean hasAnyKind(List<AttributeView> attributes, String... kinds) {
        Set<String> accepted = Set.of(kinds);
        return attributes.stream().anyMatch(attribute -> accepted.contains(attribute.kind()));
    }

    private static boolean sharesValueOfKinds(List<AttributeView> left, List<AttributeView> right, String... kinds) {
        Set<String> accepted = Set.of(kinds);
        Set<String> leftValues = left.stream()
                .filter(attribute -> accepted.contains(attribute.kind()))
                .map(AttributeView::value)
                .collect(Collectors.toSet());
        return right.stream()
                .filter(attribute -> accepted.contains(attribute.kind()))
                .anyMatch(attribute -> leftValues.contains(attribute.value()));
    }

    private static boolean sharesPackageCoordinate(List<AttributeView> left, List<AttributeView> right) {
        Set<String> leftCoordinates = left.stream()
                .filter(attribute -> attribute.kind().equals("IDENTIFIER"))
                .map(AttributeView::value)
                .map(CrossRepoMatchingService::packageCoordinate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return right.stream()
                .filter(attribute -> attribute.kind().equals("IDENTIFIER"))
                .map(AttributeView::value)
                .map(CrossRepoMatchingService::packageCoordinate)
                .filter(Objects::nonNull)
                .anyMatch(leftCoordinates::contains);
    }

    private static String packageCoordinate(String identifier) {
        if (identifier == null || !identifier.startsWith("pkg:")) {
            return null;
        }
        int end = identifier.length();
        int query = identifier.indexOf('?');
        int subpath = identifier.indexOf('#');
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (subpath >= 0) {
            end = Math.min(end, subpath);
        }
        String coordinate = identifier.substring(0, end);
        int version = coordinate.lastIndexOf('@');
        if (version > coordinate.lastIndexOf('/')) {
            coordinate = coordinate.substring(0, version);
        }
        return coordinate;
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID attributeId(SurfaceView surface, String id) {
        UUID parsed = uuid(id);
        if (parsed == null) {
            return null;
        }
        return surface.attributes().stream()
                .filter(attribute -> Objects.equals(attribute.id(), parsed))
                .findFirst()
                .map(AttributeView::id)
                .orElse(null);
    }

    private static String buildUserPrompt(
            RepoView subject,
            List<SurfaceView> left,
            RepoView other,
            List<SurfaceView> right,
            AliasTable aliases,
            String format) {
        return "Subject "
                + renderRepo(SUBJECT_REPO_ALIAS, subject, left, aliases)
                + "\nOther "
                + renderRepo(OTHER_REPO_ALIAS, other, right, aliases)
                + "\n"
                + format;
    }

    private static String renderRepoHeader(String repoAlias, RepoView repo) {
        String idLabel = SUBJECT_REPO_ALIAS.equals(repoAlias) ? "subjectRepositoryId" : "otherRepositoryId";
        String synopsis = repo.synopsis() == null
                ? "unavailable"
                : "repoRole=" + repo.synopsis().repoRole() + " knownAs="
                        + repo.synopsis().knownAs() + " fragments="
                        + repo.synopsis().fragments().render();
        return idLabel + "=" + repoAlias + " name=" + repo.repoName() + " synopsis=" + synopsis + "\n";
    }

    private static String renderRepo(String repoAlias, RepoView repo, List<SurfaceView> surfaces, AliasTable aliases) {
        StringBuilder builder = new StringBuilder(renderRepoHeader(repoAlias, repo));
        for (SurfaceView surface : surfaces) {
            builder.append(aliases.renderSurface(surface));
        }
        return builder.toString();
    }

    private static int totalRenderedChars(String surfacePrefix, String attrPrefix, List<SurfaceView> surfaces) {
        int total = 0;
        int sIdx = 1;
        int aIdx = 1;
        for (SurfaceView surface : surfaces) {
            total += renderSurface(surfacePrefix + sIdx++, attrPrefix, aIdx, surface)
                    .length();
            aIdx += surface.attributes().size();
        }
        return total;
    }

    static String renderSurface(String surfaceAlias, String attributePrefix, int startAttrIndex, SurfaceView surface) {
        StringBuilder builder = new StringBuilder("- ")
                .append(surfaceAlias)
                .append(' ')
                .append(surface.role())
                .append(": ")
                .append(surface.description())
                .append('\n');
        if (!surface.attributes().isEmpty()) {
            builder.append("  ");
            int idx = startAttrIndex;
            for (int i = 0; i < surface.attributes().size(); i++) {
                if (i > 0) {
                    builder.append(" | ");
                }
                AttributeView attribute = surface.attributes().get(i);
                builder.append(surfaceAlias)
                        .append('.')
                        .append(attributePrefix)
                        .append(idx++)
                        .append(' ')
                        .append(attribute.kind())
                        .append(' ')
                        .append(attribute.value());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private IngestionBatch loadBatch(UUID batchId) {
        return ingestionBatchRepository
                .findByIdWithRepositoryAndTeam(batchId)
                .orElseThrow(() -> new IllegalStateException("Ingestion batch not found: " + batchId));
    }

    static final class AliasTable {
        private final Map<String, String> repoAliases;
        private final Map<String, String> surfaceAliases;
        private final Map<String, String> attributeAliases;
        private final Map<UUID, String> surfaceAliasByUuid;
        private final Map<UUID, List<String>> attributeAliasesBySurface;

        private AliasTable(
                Map<String, String> repoAliases,
                Map<String, String> surfaceAliases,
                Map<String, String> attributeAliases,
                Map<UUID, String> surfaceAliasByUuid,
                Map<UUID, List<String>> attributeAliasesBySurface) {
            this.repoAliases = repoAliases;
            this.surfaceAliases = surfaceAliases;
            this.attributeAliases = attributeAliases;
            this.surfaceAliasByUuid = surfaceAliasByUuid;
            this.attributeAliasesBySurface = attributeAliasesBySurface;
        }

        static AliasTable build(RepoView subject, List<SurfaceView> left, RepoView other, List<SurfaceView> right) {
            Map<String, String> repos = new HashMap<>();
            repos.put(SUBJECT_REPO_ALIAS, subject.repositoryId().toString());
            repos.put(OTHER_REPO_ALIAS, other.repositoryId().toString());
            repos.put(subject.repositoryId().toString(), subject.repositoryId().toString());
            repos.put(other.repositoryId().toString(), other.repositoryId().toString());
            if (!subject.repoName().equals(other.repoName())) {
                repos.put(subject.repoName(), subject.repositoryId().toString());
                repos.put(other.repoName(), other.repositoryId().toString());
            }
            Map<String, String> surfaces = new HashMap<>();
            Map<String, String> attributes = new HashMap<>();
            Map<UUID, String> surfaceByUuid = new HashMap<>();
            Map<UUID, List<String>> attrsBySurface = new HashMap<>();
            populate("s", "a", left, surfaces, attributes, surfaceByUuid, attrsBySurface);
            populate("o", "b", right, surfaces, attributes, surfaceByUuid, attrsBySurface);
            return new AliasTable(Map.copyOf(repos), surfaces, attributes, surfaceByUuid, attrsBySurface);
        }

        private static void populate(
                String surfacePrefix,
                String attrPrefix,
                List<SurfaceView> list,
                Map<String, String> surfaces,
                Map<String, String> attributes,
                Map<UUID, String> surfaceByUuid,
                Map<UUID, List<String>> attrsBySurface) {
            int sIdx = 1;
            int aIdx = 1;
            for (SurfaceView surface : list) {
                String sAlias = surfacePrefix + sIdx++;
                surfaces.put(sAlias, surface.id().toString());
                surfaceByUuid.put(surface.id(), sAlias);
                List<String> attrAliases = new ArrayList<>(surface.attributes().size());
                for (AttributeView attribute : surface.attributes()) {
                    String plainAlias = attrPrefix + aIdx++;
                    String scopedAlias = sAlias + "." + plainAlias;
                    attributes.put(plainAlias, String.valueOf(attribute.id()));
                    attributes.put(scopedAlias, String.valueOf(attribute.id()));
                    attrAliases.add(scopedAlias);
                }
                attrsBySurface.put(surface.id(), List.copyOf(attrAliases));
            }
        }

        String renderSurface(SurfaceView surface) {
            StringBuilder builder = new StringBuilder("- ")
                    .append(surfaceAliasByUuid.get(surface.id()))
                    .append(' ')
                    .append(surface.role())
                    .append(": ")
                    .append(surface.description())
                    .append('\n');
            if (!surface.attributes().isEmpty()) {
                List<String> aliases = attributeAliasesBySurface.getOrDefault(surface.id(), List.of());
                builder.append("  ");
                for (int i = 0; i < surface.attributes().size(); i++) {
                    if (i > 0) {
                        builder.append(" | ");
                    }
                    AttributeView attribute = surface.attributes().get(i);
                    builder.append(aliases.get(i))
                            .append(' ')
                            .append(attribute.kind())
                            .append(' ')
                            .append(attribute.value());
                }
                builder.append('\n');
            }
            return builder.toString();
        }

        InferenceResponse.RelationProposal resolve(InferenceResponse.RelationProposal raw) {
            return new InferenceResponse.RelationProposal(
                    resolveToken(raw.subjectRepositoryId(), repoAliases),
                    resolveToken(raw.subjectSurfaceId(), surfaceAliases),
                    resolveToken(raw.otherRepositoryId(), repoAliases),
                    resolveToken(raw.otherSurfaceId(), surfaceAliases),
                    raw.nature(),
                    raw.direction(),
                    raw.confidence(),
                    raw.rationale(),
                    resolveList(raw.subjectAttributeIds(), attributeAliases),
                    resolveList(raw.otherAttributeIds(), attributeAliases),
                    splitTokens(raw.subjectSynopsisIds()),
                    splitTokens(raw.otherSynopsisIds()));
        }

        private static String resolveToken(String token, Map<String, String> mapping) {
            if (token == null || token.isBlank()) {
                return token;
            }
            String trimmed = token.trim();
            return mapping.getOrDefault(trimmed, "unresolved-alias:" + trimmed);
        }

        private static List<String> resolveList(List<String> tokens, Map<String, String> mapping) {
            if (tokens == null) {
                return null;
            }
            if (tokens.isEmpty()) {
                return List.of();
            }
            Set<String> resolved = new LinkedHashSet<>();
            for (String token : tokens) {
                if (token == null) {
                    return tokens;
                }
                for (String part : token.split("[,\\s]+")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        resolved.add(mapping.getOrDefault(trimmed, "unresolved-alias:" + trimmed));
                    }
                }
            }
            return List.copyOf(resolved);
        }

        private static List<String> splitTokens(List<String> tokens) {
            if (tokens == null || tokens.isEmpty()) {
                return tokens;
            }
            Set<String> split = new LinkedHashSet<>();
            for (String token : tokens) {
                if (token == null) {
                    return tokens;
                }
                for (String part : token.split("[,\\s]+")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        split.add(trimmed);
                    }
                }
            }
            return List.copyOf(split);
        }
    }

    private record RejectedCandidate(Nature nature, String message) {}

    private record GroupPassResult(int acceptedCount, List<RejectedCandidate> rejections) {}

    private record PeerPlan(
            RepoView other,
            List<InferenceResponse.RelationProposal> directMatches,
            List<SurfaceView> subjectSurfaces,
            List<SurfaceView> otherSurfaces,
            boolean needsLlm) {}

    private enum LogLevel {
        INFO,
        WARN,
        ERROR
    }

    private record PeerLog(LogLevel level, String message) {
        static PeerLog info(String message) {
            return new PeerLog(LogLevel.INFO, message);
        }

        static PeerLog warn(String message) {
            return new PeerLog(LogLevel.WARN, message);
        }

        static PeerLog error(String message) {
            return new PeerLog(LogLevel.ERROR, message);
        }

        void writeTo(BatchLogger logger) {
            switch (level) {
                case INFO -> logger.info(STEP, message);
                case WARN -> logger.warn(STEP, message);
                case ERROR -> logger.error(STEP, message);
            }
        }
    }

    private record PeerOutcome(
            UUID otherBatchId,
            List<InferenceResponse.RelationProposal> accepted,
            int discarded,
            List<PeerLog> logs,
            RuntimeException failure) {}
}
