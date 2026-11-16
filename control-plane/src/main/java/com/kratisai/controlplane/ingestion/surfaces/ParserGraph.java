package com.kratisai.controlplane.ingestion.surfaces;

import java.util.List;
import java.util.Map;

/** One parser database read: every node and edge for a project, plus any unparsed file paths. */
record ParserGraph(Map<Long, Node> nodes, List<Edge> edges, List<String> unparsedFiles) {

    record Node(long id, String label, String name, String filePath, int startLine, Map<String, Object> props) {}

    record Edge(long sourceId, long targetId, String type, Map<String, Object> props) {}
}
