package com.kratisai.controlplane.ingestion.parse;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads only the graph rows needed for bounded dimension propagation.
 *
 * <p>Entity hydration is deliberately avoided here. The propagation working set is the current
 * frontier, not the whole repository graph, so this query must stay proportional to frontier size.
 */
@Repository
public class GraphFrontierQuery {

    private static final String FIND_FILE_NODES_SQL = """
            SELECT id, path
            FROM ctx_nodes
            WHERE batch_id = :batchId
              AND node_type = 'FILE'
            """;

    private static final String FIND_INCIDENT_EDGES_SQL = """
            SELECT DISTINCT source_node_id AS from_id, target_node_id AS to_id
            FROM ctx_edges
            WHERE batch_id = :batchId
              AND source_node_id IN (:nodeIds)

            UNION ALL

            SELECT DISTINCT target_node_id AS from_id, source_node_id AS to_id
            FROM ctx_edges
            WHERE batch_id = :batchId
              AND target_node_id IN (:nodeIds)
            """;

    private static final String INSERT_NODE_DIMENSIONS_SQL = """
            INSERT INTO ctx_node_dimensions (node_id, dimension_id, rank_score)
            VALUES (?, ?, 0.0)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public GraphFrontierQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<FileNode> findFileNodes(UUID batchId) {
        return jdbc.query(
                FIND_FILE_NODES_SQL,
                new MapSqlParameterSource("batchId", batchId),
                (resultSet, rowNum) ->
                        new FileNode(resultSet.getObject("id", UUID.class), resultSet.getString("path")));
    }

    public List<EdgeRef> findIncidentEdges(UUID batchId, List<UUID> nodeIds) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource("batchId", batchId).addValue("nodeIds", nodeIds);
        return jdbc.query(
                FIND_INCIDENT_EDGES_SQL,
                parameters,
                (resultSet, rowNum) -> new EdgeRef(
                        resultSet.getObject("from_id", UUID.class), resultSet.getObject("to_id", UUID.class)));
    }

    public void insertNodeDimensions(List<NodeDimension> mappings) {
        if (mappings.isEmpty()) {
            return;
        }
        jdbc.getJdbcTemplate().batchUpdate(INSERT_NODE_DIMENSIONS_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                NodeDimension mapping = mappings.get(index);
                statement.setObject(1, mapping.nodeId());
                statement.setObject(2, mapping.dimensionId());
            }

            @Override
            public int getBatchSize() {
                return mappings.size();
            }
        });
    }

    public record FileNode(UUID id, String path) {
        public FileNode {
            Objects.requireNonNull(id, "id");
        }
    }

    public record EdgeRef(UUID fromId, UUID toId) {
        public EdgeRef {
            Objects.requireNonNull(fromId, "fromId");
            Objects.requireNonNull(toId, "toId");
        }
    }

    public record NodeDimension(UUID nodeId, UUID dimensionId) {
        public NodeDimension {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(dimensionId, "dimensionId");
        }
    }
}
