# Mermaid grammar contract

This directory defines the supported Mermaid subset and canonical output. It is independent of Mermaid's full grammar.

- `grammar.json` has `version`, `headers`, `productions`, `slots`, `reserved`, `patterns`, and `bounds`.
- `rules.json` lists stable rule IDs, descriptions, and emitted error IDs. The IDs and error IDs are currently identical.
- `cases.json` is the shared conformance corpus. Its expected results are reviewed data, not generated during tests.

Implementations must apply the grammar, semantic checks, text policies, and bounds. They must pass every case exactly. The web implementation is `web/src/lib/mermaid/index.ts`; the control-plane implementation is `control-plane/src/main/java/com/kratisai/controlplane/validation/mermaid/`. The web conformance test also parses every accepted output with Mermaid.

## Contract layers

Kratis diagrams are a custom Mermaid-inspired subset, not full Mermaid. There are two layers:

- `grammar.json`, `rules.json`, and `cases.json` are the machine contract. They define what the recognizers accept and the canonical `renderText` used by the web renderer.
- `MermaidGrammar.GUIDANCE` is prompt policy. It is deliberately simpler than the compatibility rules in the recognizer. It tells the LLM to write quoted labels, one supported statement form, and no unsupported Mermaid directives.

An intentional difference between prompt guidance and machine acceptance must be documented in this README, justified, and exercised by `cases.json`. Guidance must not advertise a form that the machine contract rejects, and the machine contract must not accept a form that is unsafe to render. If the prompt is stricter than the machine contract, the rationale is to keep LLM rules simple while preserving backwards-compatible input recognition.

Examples of intentional layering:

- The prompt requires quoted display text and labels. Some legacy bare-label forms are still accepted by the recognizer and then canonicalized to quoted `renderText`, but the unquoted forms are not advertised to the LLM.
- Inline edge labels must be quoted in both the prompt and machine contract. Bare inline edge labels are rejected. The close marker must match the opening marker (solid, dotted, or thick).
- The prompt does not invite unsupported Mermaid directives; the recognizer ignores a few styling directives, but rejects init/front matter/click/link/href forms.


## Data conventions

`headers` maps the result type to accepted header tokens. Flowchart also accepts one space and an optional direction; the direction is accepted case-insensitively and canonicalized to uppercase. `graph` returns type `flowchart` and renders as `flowchart`. Other headers must match exactly.

`productions` contains EBNF summaries per type. `common` and `c4` provide shared productions. Each statement occupies one trimmed line. Blank lines are ignored. `%%` comments and `style`, `classDef`, and `linkStyle` directives are ignored. Quoted and scanner-based tokens allow ASCII spaces or tabs where the scanner accepts them; Unicode whitespace is text, not syntax. The semantic constraints below and conformance cases complement these summaries.

`slots` maps named text positions to `quoted`, `bare`, or `c4raw`. A quoted slot retains syntax quotes. A bare slot removes one unambiguous outer authoring quote pair. Both encoded policies decode Mermaid decimal and `#quot;` entities, normalize line breaks, and encode unsafe Unicode code points. `c4raw` retains raw quoted text and rejects entity-shaped and HTML-like forms.

Validate canonical `renderText` before rendering and before persisting. Persist the original `source`; do not replace it: the LLM may later search-and-patch against the exact text it produced.

`reserved` contains lowercase identifiers. Compare flowchart identifiers case-sensitively, also rejecting Mermaid's `classDef` and `linkStyle` directives; compare other identifiers case-insensitively. `erAttribute` applies to ER attribute types and names. C4 and data-chart identifiers have empty reserved sets. `patterns` are whole-token regular expressions in a common Java and JavaScript subset. Compile without flags. Use a full-token match. The patterns use ASCII ranges, not Unicode character classes.

`bounds.maxInputUtf16Units` counts UTF-16 code units, including line endings. `maxDepth` applies independently to nested flow subgraphs, sequence blocks, C4 boundaries, and state composites. Journey score bounds are inclusive. CRLF becomes LF. Tabs and LF are allowed. Lone CR, other C0 controls, and DEL are rejected.

Each case has a unique `name`, a `type`, and a `source`. A successful case has `ok: true` and exact `renderText`. A failed case has `ok: false` and an emitted `rule` error ID. For failures, `type` identifies the intended diagram or is `unknown`; it is not a recognizer result. `source` is normally a string. The null case checks non-string API input. Compare output strings exactly, including newlines and Unicode. Human error text is not part of conformance.

When several checks could fail, the first check reached by the scanner determines the error ID. `syntax.statement` covers syntax and semantic checks without a more specific ID. Unexpected implementation exceptions must not be reported as contract failures. Accepted output preserves source separately and emits canonical text with LF line endings.

## Semantic constraints

Each production occupies one trimmed line. Blank lines are ignored. Quoted values contain non-whitespace text without a literal double quote. Bare values are nonempty and either unwrapped with balanced internal quotes or enclosed by one unambiguous outer quote pair. The recognizer trims slots and wrapper content. `#quot;` is data, not wrapper syntax. Encode angle-bracket text as entities. Reject named entities rather than decoding them.

- Flowchart subgraphs must close. Their IDs cannot duplicate another subgraph or declared node ID. Subgraph IDs can be edge endpoints outside their own subgraph. Quoted titles without an explicit ID are accepted for legacy source. Flowchart identifiers contain ASCII letters, digits and underscores. Only exact lowercase flow keywords are reserved. `direction` statements and `%%` comments are accepted. `style`, `classDef`, and `linkStyle` lines are ignored. Inline edge labels must be quoted; bare inline labels are rejected. Pipe edge labels and bracketed subgraph titles may be bare and are canonicalized to quoted labels.
- Sequence supports `autonumber` and `rect` blocks. Participant and message names may contain words separated by spaces. Aliases use `participant ID as "Display Name"`. Blocks must close and branches must match their enclosing block. An activation must refer to a participant introduced by a declaration, message, or note. Deactivation depth cannot become negative. Inline `+` activates the target and inline `-` deactivates the source. Duplicate participant declarations are rejected.
- Class IDs may contain dots. Bodies support fields, methods, visibility markers, `~generics~`, `<<annotations>>` on a declaration or inside a body, and `note for ID "text"`. Members may also be declared as `ID : member`. Relation arrows are `<|--`, `*--`, `o--`, `-->`, `..>`, `..|>`, `<|..`, `<..`, `o..`, `..o`, `--|>`, `--*`, `--o`, `<--`, and `--`. Relation labels accept optional spaces around the colon. `direction` statements are accepted. Angle brackets are encoded.
- State diagrams accept `state ID`, `state "Label" as ID`, composite `state ID { ... }`, `state ID : description`, `ID : description`, and `direction` statements. State IDs may start with a digit. State names containing spaces are canonicalized to an alias and a safe ID. Quote marks are not accepted in transition or description state names; they are only accepted in declarations, aliases, and note targets. Transition and description arrows between state names are quote-aware. State note targets must already exist through an alias or transition. Transition labels accept optional spaces around the colon. Left and right block notes require nonempty text and close with `end note`; they render as single-line notes.
- ER attributes occur only inside entity bodies and cannot repeat a key. Attribute types can include numeric parameters and array suffixes. Bare relation labels are quoted in canonical output; ambiguous semicolon-separated statements are rejected. C4 diagrams accept one encoded `title` statement. C4 boundaries must have a nonempty body and close. C4 shape and boundary IDs are unique; relations may refer forward to shapes, but not to boundaries or missing IDs. C4 supports the `Db` and `Queue` element variants.
- Pie values cannot be negative and a pie requires a slice. Quadrant axes and all four captions must precede points and occur once. An XY axis needs at least one category and every series must have the same number of values. Its optional y-axis range must increase.
- Journey tasks must follow a section and use an integer score from 1 through 5. The role is optional. A role cannot contain a literal or encoded comma. A journey requires at least one task.

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
