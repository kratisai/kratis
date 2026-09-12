package com.kratisai.controlplane.ingestion.parse;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.model.CtxEdge;
import com.kratisai.controlplane.model.CtxNode;
import com.kratisai.controlplane.model.IngestionBatch;
import com.kratisai.controlplane.model.NodeType;
import com.kratisai.controlplane.model.RelationType;
import com.kratisai.controlplane.repository.CtxEdgeRepository;
import com.kratisai.controlplane.repository.CtxNodeRepository;
import com.kratisai.controlplane.service.ProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CodebaseMemoryParserService {

    private static final Logger logger = LoggerFactory.getLogger(CodebaseMemoryParserService.class);

    // Cap on how much of the binary's captured output is embedded in the error message
    private static final int MAX_CAPTURED_OUTPUT_CHARS = 4000;

    // All node labels to import from codebase-memory SQLite
    private static final Set<String> IMPORT_NODE_LABELS =
            Set.of("File", "Class", "Method", "Field", "Interface", "Function", "Module", "Variable");
    // Structural edges to exclude (containment relationships)
    private static final Set<String> EXCLUDED_EDGE_TYPES =
            Set.of("CONTAINS_FILE", "CONTAINS_FOLDER", "CONTAINS_PACKAGE");

    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final ProcessExecutor processExecutor;
    private final ObjectMapper objectMapper;
    private final String binaryPath;
    private final String dbTempDir;

    public CodebaseMemoryParserService(
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            ProcessExecutor processExecutor,
            ObjectMapper objectMapper,
            @Value("${kratis.parser.binary-path}") String binaryPath,
            @Value("${kratis.parser.temp-dir}") String dbTempDir) {
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.processExecutor = processExecutor;
        this.objectMapper = objectMapper;
        this.binaryPath = binaryPath;
        this.dbTempDir = dbTempDir;
        if (isEmpty(dbTempDir)) {
            throw new IllegalArgumentException("dbTempDir must not be empty");
        }
    }

    public void prepareAst(IngestionBatch batch, Path cloneDirectory) {
        logger.info("Starting codebase-memory parsing for batch {} in directory {}", batch.getId(), cloneDirectory);

        try {
            String absBinaryPath = Path.of(binaryPath).toAbsolutePath().toString();

            // Delete any existing stale SQLite cache database for this project path
            String projectName = deriveProjectName(cloneDirectory);
            Path sqlitePath = Path.of(dbTempDir).resolve(projectName + ".db");
            try {
                java.nio.file.Files.deleteIfExists(sqlitePath);
            } catch (IOException e) {
                logger.warn("Failed to delete stale SQLite database at {}: {}", sqlitePath, e.getMessage());
            }

            // Execute codebase-memory index
            List<String> command = List.of(
                    absBinaryPath,
                    "cli",
                    "index_repository",
                    "{\"repo_path\":\"" + cloneDirectory.toAbsolutePath() + "\"}");

            Map<String, String> env = new HashMap<>();
            env.put("CBM_CACHE_DIR", dbTempDir);
            ProcessExecutor.ProcessResult result = processExecutor.execute(command, cloneDirectory.toFile(), env);

            if (result.exitCode() != 0) {
                throw codebaseMemoryExitFailure(result.exitCode(), result.output());
            }
            if (!sqlitePath.toFile().exists()) {
                throw new RuntimeException("SQLite database not found at: " + sqlitePath);
            }
            // ETL from SQLite to Postgres
            etlFromSqlite(batch, sqlitePath);
            logger.info("Successfully parsed repository with codebase-memory for batch {}", batch.getId());

        } catch (IOException | InterruptedException e) {
            logger.error("codebase-memory parser execution failed with a hard error.", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("codebase-memory parsing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Derive project name from clone directory path.
     * codebase-memory derives project name by replacing path separators with hyphens.
     * e.g., /tmp/sample-repo -> tmp-sample-repo
     */
    private String deriveProjectName(Path cloneDirectory) {
        String absolutePath = cloneDirectory.toAbsolutePath().toString();
        return absolutePath.replaceAll("^/+", "").replace("/", "-").replace("\\", "-");
    }

    /** Build a failure message carrying the exit code and the binary's captured output. */
    private static RuntimeException codebaseMemoryExitFailure(int exitCode, byte[] output) {
        String message = "codebase-memory exited with code " + exitCode;
        if (output != null && output.length > 0) {
            String captured = new String(output, StandardCharsets.UTF_8).trim();
            if (!captured.isEmpty()) {
                message += ": " + truncateOutput(captured);
            }
        }
        return new RuntimeException(message);
    }

    private static String truncateOutput(String value) {
        if (value.length() <= MAX_CAPTURED_OUTPUT_CHARS) {
            return value;
        }
        return value.substring(0, MAX_CAPTURED_OUTPUT_CHARS) + "\n... (output truncated)";
    }

    private void etlFromSqlite(IngestionBatch batch, Path sqlitePath) {
        String jdbcUrl = "jdbc:sqlite:" + sqlitePath.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            // Read nodes
            Map<Integer, CtxNode> nodeIdMap = readNodes(conn, batch);

            // Read edges
            List<CtxEdge> edges = readEdges(conn, batch, nodeIdMap);

            // Save to Postgres
            if (!nodeIdMap.isEmpty()) {
                ctxNodeRepository.saveAll(nodeIdMap.values());
            }
            if (!edges.isEmpty()) {
                ctxEdgeRepository.saveAll(edges);
            }

            logger.info(
                    "ETL complete: imported {} nodes and {} edges from codebase-memory SQLite",
                    nodeIdMap.size(),
                    edges.size());

        } catch (SQLException e) {
            logger.error("Failed to read SQLite database: {}", sqlitePath, e);
            throw new RuntimeException("SQLite ETL failed: " + e.getMessage(), e);
        }
    }

    private Map<Integer, CtxNode> readNodes(Connection conn, IngestionBatch batch) throws SQLException {
        // Read all nodes - we need all of them for edge resolution even if we don't save all
        String sql =
                "SELECT id, label, name, qualified_name, file_path, start_line, end_line, properties " + "FROM nodes";

        Map<Integer, CtxNode> nodeIdMap = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String label = rs.getString("label");
                String name = rs.getString("name");
                String filePath = rs.getString("file_path");
                String properties = rs.getString("properties");

                // Only save nodes with labels we care about
                if (label == null || !IMPORT_NODE_LABELS.contains(label)) {
                    continue;
                }

                NodeType nodeType = mapNodeType(label);

                CtxNode node = new CtxNode(
                        batch,
                        batch.getRepository().getTeam().getId(),
                        batch.getRepository().getName(),
                        nodeType,
                        filePath != null ? filePath : "");

                node.setSymbolName(name);

                String metadata = "{}";
                if (properties != null && !properties.isBlank()) {
                    try {
                        JsonNode jsonNode = objectMapper.readTree(properties);
                        metadata = objectMapper.writeValueAsString(jsonNode);
                    } catch (Exception e) {
                        logger.debug(
                                "Properties for node {} is not valid JSON, wrapping as raw string: {}",
                                id,
                                e.getMessage());
                        try {
                            metadata = "{\"raw\": " + objectMapper.writeValueAsString(properties) + "}";
                        } catch (Exception ex) {
                            logger.error("Failed to serialize properties for node {}", id, ex);
                        }
                    }
                }
                node.setMetadata(metadata);

                nodeIdMap.put(id, node);
            }
        }
        return nodeIdMap;
    }

    private NodeType mapNodeType(@NotNull String label) {
        return switch (label.toLowerCase()) {
            case "file" -> NodeType.FILE;
            case "module" -> NodeType.MODULE;
            case "interface" -> NodeType.INTERFACE;
            case "function" -> NodeType.FUNCTION;
            case "variable" -> NodeType.VARIABLE;
            case "class" -> NodeType.CLASS;
            case "method" -> NodeType.METHOD;
            case "field" -> NodeType.FIELD;
            case "project" -> NodeType.PROJECT;
            default -> throw new IllegalStateException("Unexpected value: " + label.toLowerCase());
        };
    }

    private List<CtxEdge> readEdges(Connection conn, IngestionBatch batch, Map<Integer, CtxNode> nodeIdMap)
            throws SQLException {
        List<CtxEdge> edges = new ArrayList<>();

        // Import all edges except structural containment relationships
        String sql = "SELECT id, source_id, target_id, type, properties FROM edges";

        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                int sourceId = rs.getInt("source_id");
                int targetId = rs.getInt("target_id");
                String type = rs.getString("type");

                // Skip structural containment edges
                if (EXCLUDED_EDGE_TYPES.contains(type)) {
                    continue;
                }

                CtxNode sourceNode = nodeIdMap.get(sourceId);
                CtxNode targetNode = nodeIdMap.get(targetId);

                if (sourceNode != null && targetNode != null && type != null) {
                    // Map codebase-memory edge types to Kratis relation types
                    RelationType relationType = mapEdgeType(type);

                    CtxEdge edge = new CtxEdge(
                            batch, batch.getRepository().getTeam().getId(), sourceNode, targetNode, relationType);

                    edges.add(edge);
                }
            }
        }
        return edges;
    }

    /**
     * Map codebase-memory edge types to Kratis relation types.
     */
    private RelationType mapEdgeType(String codebaseMemoryType) {
        if (codebaseMemoryType == null) {
            return RelationType.IMPORTS;
        }
        return switch (codebaseMemoryType.toUpperCase()) {
            case "CALLS", "HTTP_CALLS", "ASYNC_CALLS" -> RelationType.CALLS;
            case "USAGE", "IMPORTS", "DEPENDS_ON" -> RelationType.IMPORTS;
            case "INHERITS", "EXTENDS", "INHERITS_FROM" -> RelationType.INHERITS;
            case "IMPLEMENTS", "OVERRIDES", "USES_TYPE" -> RelationType.IMPLEMENTS;
            case "MEMBER_OF", "DEFINES_METHOD", "DEFINES", "CONTAINS" -> RelationType.DEFINES;
            case "HANDLES" -> RelationType.HANDLES;
            case "THROWS", "RAISES" -> RelationType.THROWS;
            case "CONFIGURES" -> RelationType.CONFIGURES;
            case "WRITES" -> RelationType.WRITES;
            case "TESTS", "TESTS_FILE" -> RelationType.TESTS;
            case "FILE_CHANGES_WITH" -> RelationType.FILE_CHANGES_WITH;
            case "DECORATES", "DECORATED_BY" -> RelationType.DECORATES;
            case "SEMANTICALLY_RELATED", "SIMILAR_TO" -> RelationType.SEMANTICALLY_SIMILAR;
            default -> {
                logger.warn("Unknown edge type from codebase-memory: '{}', defaulting to IMPORTS", codebaseMemoryType);
                yield RelationType.IMPORTS;
            }
        };
    }
}
