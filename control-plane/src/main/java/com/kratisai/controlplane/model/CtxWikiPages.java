package com.kratisai.controlplane.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * In-memory hierarchy helpers over wiki pages fetched with the deterministic batch ordering
 * (orderIndex ASC, title ASC, pageSlug ASC). A batch holds only ~20 pages, so sibling groups are
 * derived here instead of through per-parent repository queries.
 */
public final class CtxWikiPages {

    private CtxWikiPages() {}

    public static List<CtxWikiPage> topLevelPages(List<CtxWikiPage> pages) {
        return pages.stream().filter(page -> page.getParentPage() == null).toList();
    }

    public static List<CtxWikiPage> childPages(List<CtxWikiPage> pages, UUID parentId) {
        Objects.requireNonNull(parentId, "parentId is required");
        return pages.stream()
                .filter(page -> page.getParentPage() != null
                        && parentId.equals(page.getParentPage().getId()))
                .toList();
    }

    public static boolean hasChildren(List<CtxWikiPage> pages, CtxWikiPage page) {
        Objects.requireNonNull(page, "page is required");
        return page.getId() != null
                && pages.stream()
                        .anyMatch(candidate -> candidate.getParentPage() != null
                                && page.getId().equals(candidate.getParentPage().getId()));
    }

    public static int maxSiblingOrderIndex(List<CtxWikiPage> pages, CtxWikiPage parentPage) {
        Stream<CtxWikiPage> siblings =
                parentPage == null ? topLevelPages(pages).stream() : childPages(pages, parentPage.getId()).stream();
        return siblings.mapToInt(page -> page.getOrderIndex() != null ? page.getOrderIndex() : 0)
                .max()
                .orElse(0);
    }
}
