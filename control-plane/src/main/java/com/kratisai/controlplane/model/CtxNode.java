package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ctx_nodes")
public class CtxNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ctx_nodes_batch"))
    private IngestionBatch batch;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "repo_name", nullable = false, length = 255)
    private String repoName;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", length = 50)
    private NodeType nodeType;

    @Column(name = "symbol_name", columnDefinition = "TEXT")
    private String symbolName;

    @Column(columnDefinition = "TEXT")
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "archetype_group")
    private String archetypeGroup;

    public CtxNode() {}

    public CtxNode(IngestionBatch batch, UUID teamId, String repoName, NodeType nodeType, String path) {
        this.batch = batch;
        this.teamId = teamId;
        this.repoName = repoName;
        this.nodeType = nodeType;
        this.path = path;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public IngestionBatch getBatch() {
        return batch;
    }

    public void setBatch(IngestionBatch batch) {
        this.batch = batch;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public void setNodeType(NodeType nodeType) {
        this.nodeType = nodeType;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getSymbolName() {
        return symbolName;
    }

    public void setSymbolName(String symbolName) {
        this.symbolName = symbolName;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getSourceCodeRef() {
        return getPath() + ":" + getSymbolName();
    }

    public String getArchetypeGroup() {
        return archetypeGroup;
    }

    public void setArchetypeGroup(String archetypeGroup) {
        this.archetypeGroup = archetypeGroup;
    }

    @Override
    public String toString() {
        return "CtxNode{"
                + "path='"
                + path
                + '\''
                + ", symbolName='"
                + symbolName
                + '\''
                + ", nodeType='"
                + nodeType
                + '\''
                + ", archetypeGroup='="
                + archetypeGroup
                + '\''
                + '}';
    }
}
