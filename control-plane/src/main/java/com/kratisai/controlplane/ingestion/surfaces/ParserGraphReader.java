package com.kratisai.controlplane.ingestion.surfaces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the parser SQLite graph. Parser labels and property keys stop here and in the translator. */
class ParserGraphReader {

    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() {};
    private static final String MISSED_PROJECT_SUFFIX = "::missed";

    private final ObjectMapper objectMapper;

    ParserGraphReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ParserGraph read(Path sqlitePath, String projectName, SurfaceCounters counters) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath())) {
            Map<Long, ParserGraph.Node> nodes = loadNodes(conn, projectName);
            List<ParserGraph.Edge> edges = loadEdges(conn, projectName);
            countCoverage(conn, projectName, counters);
            return new ParserGraph(nodes, edges);
        } catch (SQLException e) {
            throw new IllegalStateException("Parser graph read failed for " + projectName + ": " + e.getMessage(), e);
        }
    }

    private void countCoverage(Connection conn, String projectName, SurfaceCounters counters) throws SQLException {
        counters.add("coverage.indexed_files", countFiles(conn, projectName));
        counters.add("coverage.unparsed_files", countFiles(conn, projectName + MISSED_PROJECT_SUFFIX));
    }

    private int countFiles(Connection conn, String project) throws SQLException {
        try (PreparedStatement stmt =
                conn.prepareStatement("SELECT COUNT(*) FROM nodes WHERE project = ? AND label = 'File'")) {
            stmt.setString(1, project);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private Map<Long, ParserGraph.Node> loadNodes(Connection conn, String projectName) throws SQLException {
        Map<Long, ParserGraph.Node> nodes = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, label, name, file_path, start_line, properties FROM nodes WHERE project = ?")) {
            stmt.setString(1, projectName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    nodes.put(
                            id,
                            new ParserGraph.Node(
                                    id,
                                    nullToEmpty(rs.getString("label")),
                                    nullToEmpty(rs.getString("name")),
                                    nullToEmpty(rs.getString("file_path")),
                                    rs.getInt("start_line"),
                                    parseProperties(rs.getString("properties"))));
                }
            }
        }
        return nodes;
    }

    private List<ParserGraph.Edge> loadEdges(Connection conn, String projectName) throws SQLException {
        List<ParserGraph.Edge> edges = new ArrayList<>();
        try (PreparedStatement stmt =
                conn.prepareStatement("SELECT source_id, target_id, type, properties FROM edges WHERE project = ?")) {
            stmt.setString(1, projectName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    edges.add(new ParserGraph.Edge(
                            rs.getLong("source_id"),
                            rs.getLong("target_id"),
                            nullToEmpty(rs.getString("type")),
                            parseProperties(rs.getString("properties"))));
                }
            }
        }
        return edges;
    }

    private Map<String, Object> parseProperties(String json) {
        if (blank(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, PROPERTIES);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
