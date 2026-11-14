package com.kratisai.controlplane.ingestion.surfaces;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    List<CtxSurface> extract(IngestionBatch batch, Path sqlitePath, String projectName, SurfaceCounters counters) {
        return translator.translate(batch, reader.read(sqlitePath, projectName, counters), counters);
    }
}
