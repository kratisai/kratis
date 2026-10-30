package com.kratisai.controlplane.ingestion.parse;

import static org.apache.commons.lang3.StringUtils.isEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kratisai.controlplane.config.ParserProperties;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CodebaseMemoryParserService {

    private static final Logger logger = LoggerFactory.getLogger(CodebaseMemoryParserService.class);

    // Cap on how much of the binary's captured output is embedded in the error message
    private static final int MAX_CAPTURED_OUTPUT_CHARS = 4000;

    // All node labels to import from codebase-memory SQLite
    private static final Set<String> IMPORT_NODE_LABELS =
            Set.of("File", "Class", "Struct", "Method", "Field", "Interface", "Function", "Module", "Variable");
    // Containment and documentation references; neither is a code dependency
    private static final Set<String> EXCLUDED_EDGE_TYPES =
            Set.of("CONTAINS_FILE", "CONTAINS_FOLDER", "CONTAINS_PACKAGE", "HAS_BRANCH", "MENTIONS", "REFERENCES_FILE");

    private static final String PROJECT_NAME_PREFIX = "kratis-";

    private final Semaphore executionGuard = new Semaphore(1);
    private final CtxNodeRepository ctxNodeRepository;
    private final CtxEdgeRepository ctxEdgeRepository;
    private final ProcessExecutor processExecutor;
    private final ObjectMapper objectMapper;
    private final String binaryPath;
    private final String dbTempDir;
    private final int workers;
    private final int memBudgetMb;

    public CodebaseMemoryParserService(
            CtxNodeRepository ctxNodeRepository,
            CtxEdgeRepository ctxEdgeRepository,
            ProcessExecutor processExecutor,
            ObjectMapper objectMapper,
            ParserProperties parserProperties) {
        this.ctxNodeRepository = ctxNodeRepository;
        this.ctxEdgeRepository = ctxEdgeRepository;
        this.processExecutor = processExecutor;
        this.objectMapper = objectMapper;
        this.binaryPath = parserProperties.getBinaryPath();
        this.dbTempDir = parserProperties.getTempDir();
        this.workers = parserProperties.getWorkers();
        this.memBudgetMb = parserProperties.getMemBudgetMb();
        if (isEmpty(dbTempDir)) {
            throw new IllegalArgumentException("dbTempDir must not be empty");
        }
    }

    public void prepareAst(IngestionBatch batch, Path cloneDirectory) {
        logger.info("Starting codebase-memory parsing for batch {} in directory {}", batch.getId(), cloneDirectory);

        try {
            executionGuard.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for codebase-memory execution guard", e);
        }

        try {
            String absBinaryPath = Path.of(binaryPath).toAbsolutePath().toString();
            Path cacheDir = resolveCacheDir(dbTempDir);

            String projectName = projectName(batch);
            Path sqlitePath = cacheDir.resolve(projectName + ".db");
            try {
                Files.deleteIfExists(sqlitePath);
            } catch (IOException e) {
                logger.warn("Failed to delete stale SQLite database at {}: {}", sqlitePath, e.getMessage());
            }

            List<String> command = List.of(
                    absBinaryPath,
                    "cli",
                    "index_repository",
                    "--repo-path",
                    cloneDirectory.toAbsolutePath().toString(),
                    "--name",
                    projectName);

            Map<String, String> env = new HashMap<>();
            env.put("CBM_CACHE_DIR", cacheDir.toString());
            env.put("CBM_WORKERS", String.valueOf(workers));
            env.put("CBM_MEM_BUDGET_MB", String.valueOf(memBudgetMb));
            ProcessExecutor.ProcessResult result = processExecutor.execute(command, cloneDirectory.toFile(), env);

            if (result.exitCode() != 0) {
                throw codebaseMemoryExitFailure(result.exitCode(), result.output());
            }
            if (!sqlitePath.toFile().exists()) {
                throw new RuntimeException("SQLite database not found at: " + sqlitePath);
            }
            etlFromSqlite(batch, sqlitePath, projectName);
            logger.info("Successfully parsed repository with codebase-memory for batch {}", batch.getId());

        } catch (IOException | InterruptedException e) {
            logger.error("codebase-memory parser execution failed with a hard error.", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("codebase-memory parsing failed: " + e.getMessage(), e);
        } finally {
            executionGuard.release();
        }
    }

    static Path cacheDirFor(String configuredDir) {
        Path path = Path.of(configuredDir);
        if ("/tmp".equals(configuredDir) || "/var/tmp".equals(configuredDir)) {
            path = path.resolve("kratis-cbm-cache");
        }
        return path;
    }

    private Path resolveCacheDir(String configuredDir) {
        Path path = cacheDirFor(configuredDir);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            logger.warn("Failed to create cache directory at {}: {}", path, e.getMessage());
        }
        return path;
    }

    private static String projectName(IngestionBatch batch) {
        return PROJECT_NAME_PREFIX + batch.getId();
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

    private void etlFromSqlite(IngestionBatch batch, Path sqlitePath, String projectName) {
        String jdbcUrl = "jdbc:sqlite:" + sqlitePath.toAbsolutePath();

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            Map<Integer, CtxNode> nodeIdMap = readNodes(conn, batch, projectName);
            List<CtxEdge> edges = readEdges(conn, batch, nodeIdMap, projectName);

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

    private Map<Integer, CtxNode> readNodes(Connection conn, IngestionBatch batch, String projectName)
            throws SQLException {
        String sql = "SELECT id, label, name, qualified_name, file_path, start_line, end_line, properties "
                + "FROM nodes WHERE project = ?";

        Map<Integer, CtxNode> nodeIdMap = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, projectName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String label = rs.getString("label");
                    String name = rs.getString("name");
                    String filePath = rs.getString("file_path");
                    String properties = rs.getString("properties");

                    if (label == null || !IMPORT_NODE_LABELS.contains(label)) {
                        continue;
                    }

                    // Skip external synthetic builtins (e.g., <python-builtins>)
                    if (filePath != null && filePath.startsWith("<")) {
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
            case "class", "struct" -> NodeType.CLASS;
            case "method" -> NodeType.METHOD;
            case "field" -> NodeType.FIELD;
            case "project" -> NodeType.PROJECT;
            default -> throw new IllegalStateException("Unexpected value: " + label.toLowerCase());
        };
    }

    private List<CtxEdge> readEdges(
            Connection conn, IngestionBatch batch, Map<Integer, CtxNode> nodeIdMap, String projectName)
            throws SQLException {
        List<CtxEdge> edges = new ArrayList<>();
        Map<String, Integer> unmappedTypes = new TreeMap<>();

        String sql = "SELECT id, source_id, target_id, type, properties FROM edges WHERE project = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, projectName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int sourceId = rs.getInt("source_id");
                    int targetId = rs.getInt("target_id");
                    String type = rs.getString("type");

                    if (type == null || EXCLUDED_EDGE_TYPES.contains(type)) {
                        continue;
                    }

                    CtxNode sourceNode = nodeIdMap.get(sourceId);
                    CtxNode targetNode = nodeIdMap.get(targetId);

                    if (sourceNode != null && targetNode != null) {
                        RelationType relationType = mapEdgeType(type, unmappedTypes);

                        edges.add(new CtxEdge(
                                batch, batch.getRepository().getTeam().getId(), sourceNode, targetNode, relationType));
                    }
                }
            }
        }
        if (!unmappedTypes.isEmpty()) {
            logger.warn("Unmapped codebase-memory edge types imported as IMPORTS, by count: {}", unmappedTypes);
        }
        return edges;
    }

    private static RelationType mapEdgeType(String codebaseMemoryType, Map<String, Integer> unmappedTypes) {
        return switch (codebaseMemoryType.toUpperCase()) {
            case "CALLS", "CALL_REFERENCE", "HTTP_CALLS", "ASYNC_CALLS", "SPAWNS" -> RelationType.CALLS;
            case "USAGE", "IMPORTS", "DEPENDS_ON" -> RelationType.IMPORTS;
            case "INHERITS", "EXTENDS", "INHERITS_FROM" -> RelationType.INHERITS;
            case "IMPLEMENTS", "OVERRIDE", "USES_TYPE" -> RelationType.IMPLEMENTS;
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
                unmappedTypes.merge(codebaseMemoryType, 1, Integer::sum);
                yield RelationType.IMPORTS;
            }
        };
    }
}
