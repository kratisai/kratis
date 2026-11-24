package com.kratisai.controlplane.ingestion.write;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

@Component
public class WikiPageLinkExtractor {

    private static final String TEAM_PAGE_PREFIX = "/wiki/team/";
    private static final String REPO_PAGE_PREFIX = "/wiki/repo/";
    private static final Pattern INTERNAL_SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private final Parser parser = Parser.builder().build();

    /** Links found in one wiki page: sibling team pages and repository wiki targets, in document order. */
    public record WikiLinks(Set<String> teamPageSlugs, Set<String> repoPaths) {

        public WikiLinks {
            teamPageSlugs = unmodifiableOrdered(teamPageSlugs);
            repoPaths = unmodifiableOrdered(repoPaths);
        }

        private static Set<String> unmodifiableOrdered(Set<String> values) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(values));
        }
    }

    /**
     * Relative page links, as used inside a single repository wiki. Dotted names such as
     * {@code README.md} and repository routes are deliberately excluded.
     */
    public Set<String> internalSlugs(String markdownContent) {
        Set<String> slugs = new LinkedHashSet<>();
        if (markdownContent == null || markdownContent.isBlank()) return slugs;
        parser.parse(markdownContent).accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                String target = stripLocator(link.getDestination());
                if (target != null && !target.startsWith("/")) addSlug(slugs, target);
                visitChildren(link);
            }
        });
        return slugs;
    }

    /** Team wiki links: pages addressed by route or relative slug, plus repository wiki targets. */
    public WikiLinks extractWikiLinks(String markdownContent) {
        Set<String> teamSlugs = new LinkedHashSet<>();
        Set<String> repoPaths = new LinkedHashSet<>();
        if (markdownContent == null || markdownContent.isBlank()) {
            return new WikiLinks(teamSlugs, repoPaths);
        }
        parser.parse(markdownContent).accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                classify(link.getDestination(), teamSlugs, repoPaths);
                visitChildren(link);
            }
        });
        return new WikiLinks(teamSlugs, repoPaths);
    }

    private static void classify(String destination, Set<String> teamSlugs, Set<String> repoPaths) {
        String target = stripLocator(destination);
        if (target == null) return;
        if (target.startsWith(REPO_PAGE_PREFIX)) {
            String repoPath = trimSlashes(target.substring(REPO_PAGE_PREFIX.length()));
            if (!repoPath.isEmpty()) repoPaths.add(repoPath);
            return;
        }
        if (target.startsWith(TEAM_PAGE_PREFIX)) {
            addSlug(teamSlugs, target.substring(TEAM_PAGE_PREFIX.length()));
            return;
        }
        if (target.startsWith("/") || target.startsWith("//")) return;
        addSlug(teamSlugs, target);
    }

    private static void addSlug(Set<String> slugs, String candidate) {
        if (INTERNAL_SLUG.matcher(candidate).matches()) slugs.add(candidate);
    }

    /** Removes the {@code #fragment} and {@code ?query} parts of a link destination. */
    private static String stripLocator(String destination) {
        if (destination == null || destination.isBlank()) return null;
        int cut = destination.length();
        int fragment = destination.indexOf('#');
        if (fragment >= 0) cut = fragment;
        int query = destination.indexOf('?');
        if (query >= 0) cut = Math.min(cut, query);
        return cut == 0 ? null : destination.substring(0, cut);
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') start++;
        while (end > start && value.charAt(end - 1) == '/') end--;
        return value.substring(start, end);
    }
}
