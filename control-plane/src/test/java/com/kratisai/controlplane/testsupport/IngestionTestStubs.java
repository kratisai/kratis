package com.kratisai.controlplane.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.kratisai.controlplane.ingestion.parse.CodebaseMemoryParserService;
import com.kratisai.controlplane.ingestion.surfaces.SurfaceExtractionService;
import com.kratisai.controlplane.model.CtxEdge;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import java.util.List;
import java.util.UUID;

/**
 * Stubs {@link CodebaseMemoryParserService} and {@link SurfaceExtractionService} in ingestion
 * tests whose focus is orchestration, queueing, or LLM/embedding attribution rather than the
 * native {@code codebase-memory-mcp} / {@code syft} binaries.
 */
public final class IngestionTestStubs {

    private IngestionTestStubs() {}

    /** Stubs AST parsing with synthetic Java nodes/edges and returns an empty surface result. */
    public static void stubFastParserAndSurfaces(
            CodebaseMemoryParserService parserService,
            SurfaceExtractionService surfaceExtractionService,
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository) {
        stubFastParserAndSurfaces(parserService, surfaceExtractionService, ctxNodeRepository, ctxEdgeRepository, 0L);
    }

    /**
     * Stubs AST parsing with an optional simulated parse delay (in ms) so concurrency tests can
     * observe deterministic queue transitions without running native parser binaries.
     */
    public static void stubFastParserAndSurfaces(
            CodebaseMemoryParserService parserService,
            SurfaceExtractionService surfaceExtractionService,
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            long simulatedDelayMs) {
        doAnswer(invocation -> {
                    if (simulatedDelayMs > 0L) {
                        Thread.sleep(simulatedDelayMs);
                    }
                    IngestionBatch batch = invocation.getArgument(0);
                    UUID teamId = batch.getRepository().getTeam().getId();
                    String repoName = batch.getRepository().getName();

                    CtxNode controllerFile = new CtxNode(batch, teamId, repoName, NodeType.FILE, "UserController.java");
                    CtxNode controllerClass =
                            new CtxNode(batch, teamId, repoName, NodeType.CLASS, "UserController.java");
                    controllerClass.setSymbolName("UserController");
                    CtxNode serviceFile = new CtxNode(batch, teamId, repoName, NodeType.FILE, "UserService.java");
                    CtxNode serviceClass = new CtxNode(batch, teamId, repoName, NodeType.CLASS, "UserService.java");
                    serviceClass.setSymbolName("UserService");

                    ctxNodeRepository.saveAll(List.of(controllerFile, controllerClass, serviceFile, serviceClass));
                    ctxEdgeRepository.save(
                            new CtxEdge(batch, teamId, controllerClass, serviceClass, RelationType.CALLS));
                    return null;
                })
                .when(parserService)
                .prepareAst(any(), any());

        doReturn(new SurfaceExtractionService.Result(0, 0, 0, 0))
                .when(surfaceExtractionService)
                .extract(any(), any(), any());
    }
}
