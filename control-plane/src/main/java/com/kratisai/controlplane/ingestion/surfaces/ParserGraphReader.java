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

    ParserGraph read(Path sqlitePath, String projectName) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath.toAbsolutePath())) {
            Map<Long, ParserGraph.Node> nodes = loadNodes(conn, projectName);
            List<ParserGraph.Edge> edges = loadEdges(conn, projectName);
            List<String> unparsedFiles = loadUnparsedFiles(conn, projectName);
            return new ParserGraph(nodes, edges, unparsedFiles);
        } catch (SQLException e) {
            throw new IllegalStateException("Parser graph read failed for " + projectName + ": " + e.getMessage(), e);
        }
    }

    private List<String> loadUnparsedFiles(Connection conn, String projectName) throws SQLException {
        List<String> files = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT file_path, name FROM nodes WHERE project = ? AND label = 'File' ORDER BY id")) {
            stmt.setString(1, projectName + MISSED_PROJECT_SUFFIX);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String filePath = rs.getString("file_path");
                    String name = rs.getString("name");
                    String label = filePath != null && !filePath.isBlank() ? filePath : name;
                    if (label != null && !label.isBlank()) {
                        files.add(label);
                    }
                }
            }
        }
        return files;
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
