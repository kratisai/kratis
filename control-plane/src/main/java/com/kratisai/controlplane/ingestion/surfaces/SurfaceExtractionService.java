package com.kratisai.controlplane.ingestion.surfaces;

import com.kratisai.controlplane.ingestion.parse.CodebaseMemoryParserService;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.CtxSurfaceAttribute;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.repository.CtxSurfaceAttributeRepository;
import com.kratisai.controlplane.repository.CtxSurfaceRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SurfaceExtractionService {
    public record Result(int persisted, Map<String, Integer> counters) {
        public String summary() {
            String details = counters.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            return "Persisted " + persisted + " surfaces. " + details;
        }
    }

    private final CodebaseMemoryParserService parserService;
    private final GraphSurfaceAdapter graphSurfaceAdapter;
    private final SyftSurfaceAdapter syftSurfaceAdapter;
    private final CtxSurfaceRepository ctxSurfaceRepository;
    private final CtxSurfaceAttributeRepository ctxSurfaceAttributeRepository;

    public SurfaceExtractionService(
            CodebaseMemoryParserService parserService,
            GraphSurfaceAdapter graphSurfaceAdapter,
            SyftSurfaceAdapter syftSurfaceAdapter,
            CtxSurfaceRepository ctxSurfaceRepository,
            CtxSurfaceAttributeRepository ctxSurfaceAttributeRepository) {
        this.parserService = parserService;
        this.graphSurfaceAdapter = graphSurfaceAdapter;
        this.syftSurfaceAdapter = syftSurfaceAdapter;
        this.ctxSurfaceRepository = ctxSurfaceRepository;
        this.ctxSurfaceAttributeRepository = ctxSurfaceAttributeRepository;
    }

    public Result extract(IngestionBatch batch, Path cloneDirectory) {
        Path sqlitePath = parserService.sqlitePathFor(batch);
        if (!Files.isRegularFile(sqlitePath)) {
            throw new IllegalStateException("Parser database missing at " + sqlitePath + " for batch " + batch.getId()
                    + "; EXTRACT_SURFACES must run before the parser database is removed");
        }
        SurfaceCounters counters = new SurfaceCounters();
        List<CtxSurface> extracted = new ArrayList<>(graphSurfaceAdapter.extract(
                batch, sqlitePath, CodebaseMemoryParserService.projectName(batch), counters));
        extracted.addAll(syftSurfaceAdapter.extract(batch, cloneDirectory, counters));
        persist(extracted);
        return new Result(extracted.size(), counters.snapshot());
    }

    void persist(List<CtxSurface> extracted) {
        ctxSurfaceRepository.saveAll(extracted);
        List<CtxSurfaceAttribute> rows = extracted.stream()
                .flatMap(surface -> surface.getAttributeRows().stream())
                .toList();
        ctxSurfaceAttributeRepository.saveAll(rows);
    }
}
