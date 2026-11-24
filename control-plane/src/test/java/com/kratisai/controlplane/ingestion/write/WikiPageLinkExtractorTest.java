package com.kratisai.controlplane.ingestion.write;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WikiPageLinkExtractorTest {

    private final WikiPageLinkExtractor extractor = new WikiPageLinkExtractor();

    @Test
    void extractsRelativeWikiSlugs() {
        String markdown = """
                # Overview

                See [Architecture](architecture), [Testing](testing-guide), and [API](api-v2).
                """;

        assertThat(extractor.internalSlugs(markdown)).containsExactly("architecture", "testing-guide", "api-v2");
    }

    @Test
    void ignoresExternalAnchorsAbsolutePathsAndFileReferences() {
        String markdown = """
                [External](https://example.com/page)
                [Anchor](#overview)
                [Absolute](/wiki/repo/team/repo)
                [Mail](mailto:test@example.com)
                [Spaces](not a slug)
                [Slash](nested/page)
                [Readme](README.md)
                [Source](Application.java)
                [Manifest](package.json)
                """;

        assertThat(extractor.internalSlugs(markdown)).isEmpty();
    }

    @Test
    void resolvesFragmentAndQueryLinksToTheirTargetPage() {
        String markdown = """
                [Fragment](architecture#top)
                [Query](testing?view=full)
                """;

        assertThat(extractor.internalSlugs(markdown)).containsExactly("architecture", "testing");
    }

    @Test
    void ignoresLinksInsideCodeAndDeduplicatesSlugs() {
        String markdown = """
                [Architecture](architecture)
                [Architecture again](architecture)

                ```markdown
                [Fake](fake-page)
                ```
                """;

        assertThat(extractor.internalSlugs(markdown)).containsExactly("architecture");
    }

    @Test
    void returnsEmptyForNullOrBlankContent() {
        assertThat(extractor.internalSlugs(null)).isEmpty();
        assertThat(extractor.internalSlugs("  ")).isEmpty();
        assertThat(extractor.extractWikiLinks(null).teamPageSlugs()).isEmpty();
    }

    @Test
    void extractWikiLinksSeparatesTeamPagesFromRepositoryPaths() {
        String markdown = """
                [Team page](/wiki/team/orders-cluster)
                [Relative page](shared-platform)
                [Repo root](/wiki/repo/orders-api)
                [Repo page](/wiki/repo/orders-api/overview#section)
                [Namespaced repo](/wiki/repo/glark-newco/BookingService/tiles)
                [External](https://example.com/x)
                """;

        WikiPageLinkExtractor.WikiLinks links = extractor.extractWikiLinks(markdown);

        assertThat(links.teamPageSlugs()).containsExactly("orders-cluster", "shared-platform");
        assertThat(links.repoPaths())
                .containsExactly("orders-api", "orders-api/overview", "glark-newco/BookingService/tiles");
    }

    @Test
    void extractWikiLinksIgnoresRoutesThatAreNotWikiPages() {
        String markdown = """
                [Settings](/settings)
                [Deep](/wiki/other/thing)
                [Repo only](/wiki/repo/)
                """;

        WikiPageLinkExtractor.WikiLinks links = extractor.extractWikiLinks(markdown);

        assertThat(links.teamPageSlugs()).isEmpty();
        assertThat(links.repoPaths()).isEmpty();
    }
}
