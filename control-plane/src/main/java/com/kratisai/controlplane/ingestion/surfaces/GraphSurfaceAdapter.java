package com.kratisai.controlplane.ingestion.surfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.ingestion.IngestionBatchLogService;
import com.kratisai.controlplane.model.CtxSurface;
import com.kratisai.controlplane.model.IngestionBatch;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class GraphSurfaceAdapter {
    private final ParserGraphReader reader;
    private final GraphSurfaceTranslator translator;

    GraphSurfaceAdapter(ObjectMapper objectMapper) {
        this.reader = new ParserGraphReader(objectMapper);
        this.translator = new GraphSurfaceTranslator();
    }

    Result extract(
            IngestionBatch batch,
            Path sqlitePath,
            String projectName,
            IngestionBatchLogService.BatchLogger batchLogger) {
        ParserGraph graph = reader.read(sqlitePath, projectName);
        if (!graph.unparsedFiles().isEmpty() && batchLogger != null) {
            batchLogger.debug(
                    "EXTRACT_SURFACES",
                    "Unparsed files (" + graph.unparsedFiles().size() + "): "
                            + String.join(", ", graph.unparsedFiles()));
        }
        List<CtxSurface> surfaces = translator.translate(batch, graph, batchLogger);
        return new Result(surfaces, graph.unparsedFiles().size());
    }

    record Result(List<CtxSurface> surfaces, int unparsedFiles) {}
}
