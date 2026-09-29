package com.kratisai.controlplane.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ctx_embeddings")
public class CtxEmbedding {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private IngestionBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id", nullable = false)
    private CtxWikiPage page;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "dim_size", nullable = false)
    private int dimSize;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector")
    private float[] embedding;

    protected CtxEmbedding() {}

    public CtxEmbedding(IngestionBatch batch, Team team, CtxWikiPage page, String chunkText, float[] embedding) {
        this.batch = batch;
        this.team = team;
        this.page = page;
        this.chunkText = chunkText;
        this.dimSize = embedding != null ? embedding.length : 0;
        this.embedding = embedding;
    }

    public UUID getId() {
        return id;
    }

    public IngestionBatch getBatch() {
        return batch;
    }

    public Team getTeam() {
        return team;
    }

    public CtxWikiPage getPage() {
        return page;
    }

    public String getChunkText() {
        return chunkText;
    }

    public int getDimSize() {
        return dimSize;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
