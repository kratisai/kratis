package com.kratisai.controlplane.ingestion.surfaces;

import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
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
import org.springframework.stereotype.Service;

@Service
public class SurfaceExtractionService {
    public record Result(int persisted, int cmmCount, int syftCount, int unparsedFiles) {
        public String summary() {
            String base = "persisted " + persisted + " surfaces (CMM: " + cmmCount + ", Syft: " + syftCount + ")";
            return unparsedFiles > 0 ? base + ", " + unparsedFiles + " unparsed files" : base;
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

    public Result extract(IngestionBatch batch, Path cloneDirectory, IngestionBatchLogService.BatchLogger batchLogger) {
        Path sqlitePath = parserService.sqlitePathFor(batch);
        if (!Files.isRegularFile(sqlitePath)) {
            throw new IllegalStateException("Parser database missing at " + sqlitePath + " for batch " + batch.getId()
                    + "; EXTRACT_SURFACES must run before the parser database is removed");
        }
        GraphSurfaceAdapter.Result cmmResult = graphSurfaceAdapter.extract(
                batch, sqlitePath, CodebaseMemoryParserService.projectName(batch), batchLogger);
        List<CtxSurface> syftSurfaces = syftSurfaceAdapter.extract(batch, cloneDirectory, batchLogger);
        List<CtxSurface> extracted = new ArrayList<>(cmmResult.surfaces().size() + syftSurfaces.size());
        extracted.addAll(cmmResult.surfaces());
        extracted.addAll(syftSurfaces);
        persist(extracted);
        return new Result(
                extracted.size(), cmmResult.surfaces().size(), syftSurfaces.size(), cmmResult.unparsedFiles());
    }

    void persist(List<CtxSurface> extracted) {
        ctxSurfaceRepository.saveAll(extracted);
        List<CtxSurfaceAttribute> rows = extracted.stream()
                .flatMap(surface -> surface.getAttributeRows().stream())
                .toList();
        ctxSurfaceAttributeRepository.saveAll(rows);
    }
}
