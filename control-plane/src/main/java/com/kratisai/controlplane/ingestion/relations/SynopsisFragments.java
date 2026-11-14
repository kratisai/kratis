package com.kratisai.controlplane.ingestion.relations;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits a synopsis into numbered sentence fragments the inference call can cite by id. */
public final class SynopsisFragments {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");

    private final List<String> fragments;

    public SynopsisFragments(String synopsis) {
        List<String> found = new ArrayList<>();
        if (synopsis != null && !synopsis.isBlank()) {
            for (String section : synopsis.split("\\n\\s*\\n")) {
                String body = section.strip().replaceFirst("^(Domain|Interfaces|Deployment)\\s*:", "");
                for (String sentence : SENTENCE_END.split(body.strip())) {
                    String trimmed = sentence.strip();
                    if (!trimmed.isEmpty()) {
                        found.add(trimmed);
                    }
                }
            }
        }
        this.fragments = List.copyOf(found);
    }

    /** Fragments carry 1-based ids (f1, f2, ...); unknown ids resolve to null. */
    public String text(String id) {
        try {
            int index = Integer.parseInt(id.strip().substring(1)) - 1;
            return index >= 0 && index < fragments.size() ? fragments.get(index) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public int size() {
        return fragments.size();
    }

    public List<String> asList() {
        return fragments;
    }

    /** Renders the numbered fragment list for the inference prompt with stable f-ids. */
    public String render() {
        var lines = new ArrayList<String>();
        for (int i = 0; i < fragments.size(); i++) {
            lines.add("f" + (i + 1) + ": " + fragments.get(i));
        }
        return String.join("\n", lines);
    }
}
