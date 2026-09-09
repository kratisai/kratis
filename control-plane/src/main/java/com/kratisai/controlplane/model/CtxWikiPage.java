package com.kratisai.controlplane.model;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ctx_wiki_pages")
public class CtxWikiPage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "batch_id",
            nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ctx_wiki_pages_batch"))
    private IngestionBatch batch;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "repo_name", nullable = false, length = 255)
    private String repoName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "parent_page_id",
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_ctx_wiki_pages_parent"))
    private CtxWikiPage parentPage;

    @Column(name = "page_slug", nullable = false, length = 255)
    private String pageSlug;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "order_index")
    private Integer orderIndex = 0;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @OneToMany(mappedBy = "page", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<CtxEmbedding> embeddings = new ArrayList<>();

    public CtxWikiPage() {}

    public CtxWikiPage(
            IngestionBatch batch,
            UUID teamId,
            String repoName,
            CtxWikiPage parentPage,
            String pageSlug,
            String title,
            Integer orderIndex,
            String content) {
        this.batch = batch;
        this.teamId = teamId;
        this.repoName = repoName;
        this.parentPage = parentPage;
        this.pageSlug = pageSlug;
        this.title = title;
        this.orderIndex = orderIndex;
        this.content = content;
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

    public CtxWikiPage getParentPage() {
        return parentPage;
    }

    public void setParentPage(CtxWikiPage parentPage) {
        this.parentPage = parentPage;
    }

    public String getPageSlug() {
        return pageSlug;
    }

    public void setPageSlug(String pageSlug) {
        this.pageSlug = pageSlug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(Integer orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
