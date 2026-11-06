# Mermaid grammar contract

This directory defines the supported Mermaid subset and canonical output. It is independent of Mermaid's full grammar.

- `grammar.json` has `version`, `headers`, `productions`, `slots`, `reserved`, `patterns`, and `bounds`.
- `rules.json` lists stable rule IDs, descriptions, and emitted error IDs. The IDs and error IDs are currently identical.
- `cases.json` is the shared conformance corpus. Its expected results are reviewed data, not generated during tests.

Implementations must apply the grammar, semantic checks, text policies, and bounds. They must pass every case exactly. The web implementation is `web/src/lib/mermaid/index.ts`; the control-plane implementation is `control-plane/src/main/java/com/kratisai/controlplane/validation/mermaid/`. The web conformance test also parses every accepted output with Mermaid.

## Data conventions

`headers` maps the result type to accepted header tokens. Flowchart also accepts one space and an optional direction; the direction is accepted case-insensitively and canonicalized to uppercase. `graph` returns type `flowchart` and renders as `flowchart`. Other headers must match exactly.

`productions` contains EBNF summaries per type. `common` and `c4` provide shared productions. Each statement occupies one trimmed line. Blank lines are ignored. Quoted and scanner-based tokens allow spaces or tabs where the scanner accepts them. The semantic constraints below and conformance cases complement these summaries.

`slots` maps named text positions to `quoted`, `bare`, or `c4raw`. A quoted slot retains syntax quotes. A bare slot removes one unambiguous outer authoring quote pair. Both encoded policies decode Mermaid decimal and `#quot;` entities, normalize line breaks, and encode unsafe Unicode code points. `c4raw` retains raw quoted text and rejects entity-shaped and HTML-like forms.

`reserved` contains lowercase identifiers. Compare flowchart identifiers case-sensitively, also rejecting Mermaid's `classDef` and `linkStyle` directives; compare other identifiers case-insensitively. `erAttribute` applies to ER attribute types and names. C4 and data-chart identifiers have empty reserved sets. `patterns` are whole-token regular expressions in a common Java and JavaScript subset. Compile without flags. Use a full-token match. The patterns use ASCII ranges, not Unicode character classes.

`bounds.maxInputUtf16Units` counts UTF-16 code units, including line endings. `maxDepth` applies independently to nested flow subgraphs, sequence blocks, and C4 boundaries. Journey score bounds are inclusive. CRLF becomes LF. Tabs and LF are allowed. Lone CR, other C0 controls, and DEL are rejected.

Each case has a unique `name`, a `type`, and a `source`. A successful case has `ok: true` and exact `renderText`. A failed case has `ok: false` and an emitted `rule` error ID. For failures, `type` identifies the intended diagram or is `unknown`; it is not a recognizer result. `source` is normally a string. The null case checks non-string API input. Compare output strings exactly, including newlines and Unicode. Human error text is not part of conformance.

When several checks could fail, the first check reached by the scanner determines the error ID. `syntax.statement` covers syntax and semantic checks without a more specific ID. Unexpected implementation exceptions must not be reported as contract failures. Accepted output preserves source separately and emits canonical text with LF line endings.

## Semantic constraints

Each production occupies one trimmed line. Blank lines are ignored. Quoted values contain non-whitespace text without a literal double quote. Bare values are nonempty and either unwrapped with balanced internal quotes or enclosed by one unambiguous outer quote pair. The recognizer trims slots and wrapper content. `#quot;` is data, not wrapper syntax. Encode angle-bracket text as entities. Reject named entities rather than decoding them.

- Flowchart subgraphs must close. Their IDs cannot duplicate another subgraph or declared node ID. Subgraph IDs can be edge endpoints outside their own subgraph. Quoted titles without an explicit ID are accepted for legacy source. Flowchart identifiers contain ASCII letters, digits and underscores. Only exact lowercase flow keywords are reserved.
- Sequence supports `autonumber`. Blocks must close and branches must match their enclosing block. An activation must refer to a participant introduced by a declaration, message, or note. Deactivation depth cannot become negative. Inline `+` activates the target and inline `-` deactivates the source. Duplicate participant declarations are rejected.
- Class bodies support fields, methods, visibility markers, `~generics~`, `<<annotations>>` on a declaration or inside a body, and `note for ID "text"`. Relation arrows are `<|--`, `*--`, `o--`, `-->`, `..>`, `..|>`, `<|..`, `<..`, `o..`, `..o` and `--`. Angle brackets are encoded. State note targets must already exist through an alias or transition. Transition labels accept optional spaces around the colon. Left and right block notes require nonempty text and close with `end note`; they render as single-line notes.
- ER attributes occur only inside entity bodies and cannot repeat a key. Attribute types can include numeric parameters and array suffixes. Bare relation labels are quoted in canonical output; ambiguous semicolon-separated statements are rejected. C4 diagrams accept one encoded `title` statement. C4 boundaries must have a nonempty body and close. C4 shape and boundary IDs are unique; relations may refer forward to shapes, but not to boundaries or missing IDs.
- Pie values cannot be negative and a pie requires a slice. Quadrant axes and all four captions must precede points and occur once. An XY axis needs at least one category and every series must have the same number of values. Its optional y-axis range must increase.
- Journey tasks must follow a section and use an integer score from 1 through 5. A role cannot contain a literal or encoded comma. A journey requires at least one task.

## Verify conformance

Run `npm test -- tests/lib/mermaid` in `web/` and the Java
`MermaidSubsetConformanceTest` in `control-plane/`. The web tests parse and render every
accepted case with Mermaid in jsdom and check visible text. Neither suite is opt-in, so
cross-implementation drift fails the build.

## Add a case

1. Add a uniquely named input and its expected result to `cases.json`.
2. Add or update a production, slot, bound, or rule if the behavior changes.
3. Test all implementations and run the web conformance and Mermaid parser tests.
4. Review the expected canonical output and error ID. Do not regenerate existing expectations without review.
