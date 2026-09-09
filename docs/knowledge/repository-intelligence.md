# Repository Intelligence Platform Architecture

**Component:** Repository Intelligence & Deep Wiki Engine  
**Status:** Current Implementation  
**Last Updated:** 2026-06-13

---

## 1. Executive Summary

The Repository Intelligence Platform ingests polyglot source code repositories and outputs a multi-dimensional knowledge base. This knowledge base serves both human developers, via a hierarchical Deep Wiki, and autonomous Planning Agents, via Spring AI function tools.

The system strictly separates deterministic structural data from agent-researched semantic data. This separation prevents LLM hallucination and context-window exhaustion, ensuring that autonomous agents operate on a grounded, mathematically verified representation of the codebase.

---

## 2. Business Objectives

### 2.1 Context-Aware Agentic Planning
The Planning Agent must formulate comprehensive, safe implementation plans before code generation. By performing Change-May-Impact analysis, the agent anticipates downstream breakages and surfaces real-world implementation risks to the user upfront.

### 2.2 Automated Architectural Documentation
The system automatically generates and maintains high-level documentation that reflects the current state of the codebase. It groups raw files into understandable business modules and application tiers, generating accurate system-architecture diagrams without hallucinating connections.

### 2.3 Coding Pattern Adherence
The platform identifies the overarching system design and key coding patterns utilized in the repository. This ensures that proposed solutions respect established architectural guidelines rather than introducing anti-patterns.

---

## 3. Core Architecture

The system relies on a single PostgreSQL database leveraging three distinct data tiers. All data is isolated by a `team_id` for multi-tenancy and tracked by an atomic `batch_id` to prevent dirty reads during ingestion.

| Tier | Purpose | Underlying Technology | Key Tables (`ctx_` prefix) |
| --- | --- | --- | --- |
| **Graph** | Deterministic structural mapping. Tracks files, classes, methods, and explicit import/call relationships. | `codebase-memory` (Tree-Sitter ETL) + PostgreSQL | `ctx_nodes`, `ctx_edges` |
| **Document** | Human-and-agent readable architectural summaries. Defines boundaries, business domains, and rules. | Spring AI (LLM) + Hierarchical Markdown | `ctx_dimensions`, `ctx_node_dimensions`, `ctx_architecture_patterns`, `ctx_wiki_pages` |
| **Semantic** | Vague intent resolution. Allows agents to find codebase domains using natural language queries. | `pgvector` HNSW Similarity Search | `ctx_embeddings` |

---

## 4. The Ingestion Pipeline

The primary worker thread, [`IngestionWorker`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/IngestionWorker.java:90), executes the ingestion pipeline asynchronously. It uses phase-level transactions (`PROPAGATION_REQUIRES_NEW`) to ensure that if a failure occurs, all previously completed phases are retained in the database, and the batch is marked as `FAILED`.

### Pipeline Phases

1. **`CLONE`**: Clones the repository from the remote location using [`GitCloneService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/GitCloneService.java:50). Supports GitHub App tokens, SSH keys, and standard authentication. Cleans up existing directories to prevent disk leakage.
2. **`PREPARE_AST`**: Parses repository structure using [`CodebaseMemoryParserService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/CodebaseMemoryParserService.java:1). Uses `codebase-memory` to rapidly parse the AST of 60+ languages and output a SQLite database, which is then ETL'd into PostgreSQL `ctx_nodes` and `ctx_edges`.
3. **`PREPARE_LINKS`**: Resolves structural and cross-repository symbol dependencies using [`DependencyLinkerService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/DependencyLinkerService.java:1).
4. **`PREPARE_DIMENSIONS`**: Discovers unified dimensions (Domains, Archetypes, Cross-Cutting) via LLM using [`DimensionDiscoveryService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/DimensionDiscoveryService.java:72).
5. **`RESEARCH_GRAPH`**: Calculates subgraph PageRank for dimension nodes using [`SubgraphRankingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/SubgraphRankingService.java:43).
6. **`RESEARCH_DIMENSIONS`**: Researches dimensions and generates synopses concurrently using virtual threads via [`DimensionResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/DimensionResearchService.java:40).
7. **`RESEARCH_PATTERNS`**: Deduces system-wide architecture patterns from dimension hubs using [`PatternResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/PatternResearchService.java:71).
8. **`GENERATE_WIKI`**: Generates repository Wiki documentation using an agentic ReAct loop via [`WikiGenerationService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/WikiGenerationService.java:67).
9. **`GENERATE_INDEX`**: Generates vector embeddings for wiki pages using [`SemanticIndexingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/SemanticIndexingService.java:44).
10. **`SUCCESS`**: Finalizes the ingestion batch and atomically deactivates older batches for the same repository. Repository API responses expose the latest batch's `ingestionStatus` directly.

---

## 5. Dimension Discovery & Subgraph Ranking

The platform uses a hybrid approach to identify codebase structure: the LLM defines semantic boundaries, and the graph is used for efficient label propagation and localized ranking.

### 5.1 Unified LLM Dimension Identification
[`DimensionDiscoveryService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/DimensionDiscoveryService.java:72) builds an annotated file tree (file paths combined with single-line semantic footprints, such as class inheritance or primary function signatures) and prompts the LLM to identify dimensions in three categories:
1. **Business Domains (`DOMAIN`)**: Core capabilities (e.g., "Booking Management").
2. **Technical Archetypes (`ARCHETYPE`)**: Primary technical layers (e.g., "Controllers", "Repositories").
3. **Cross-Cutting/Support (`CROSS_CUTTING`)**: Infrastructure, CI/CD, logging, testing.

For each dimension, the LLM returns a name and 3-5 candidate glob patterns (e.g., `**/booking/**`, `*Booking*.java`).

### 5.2 Deterministic Tagging & Graph Propagation
Instead of prompting the LLM to tag thousands of files individually, the system employs a two-step process:
1. **Fast Glob Pass**: The system applies the LLM-generated globs first, instantly and deterministically tagging the majority of files for each dimension.
2. **Graph Propagation**: For untagged files, the system uses the graph to find which dimensions they are most strongly connected to. Breadth-First Search (BFS) traversal is constrained to a maximum of 2-3 hops to prevent label bleeding to unrelated modules.

### 5.3 Subgraph Ranking
[`SubgraphRankingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/parse/SubgraphRankingService.java:43) extracts a subgraph for each identified dimension, containing only the files tagged with that dimension. It then runs PageRank exclusively on this subgraph, storing the `rankScore` in the [`CtxNodeDimension`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxNodeDimension.java:8) join entity. This ensures that a global logging utility will not dominate the "Booking Management" hub list, as it was classified as "Cross-Cutting" and excluded from the business subgraph.

---

## 6. Dimension Research & Pattern Deduction

### 6.1 Dimension Research
[`DimensionResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/DimensionResearchService.java:40) uses Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) to concurrently research each dimension. A [`ResearchDimensionWorker`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/ResearchDimensionWorker.java:1) prompts the LLM to generate a rich synopsis (2-3 paragraphs) explaining the role, scope, and key patterns of that dimension, saving it to the [`CtxDimension`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxDimension.java:23) entity.

### 6.2 Pattern Deduction
[`PatternResearchService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/research/PatternResearchService.java:71) analyzes `ARCHETYPE` dimensions and their top-ranked exemplar files. It uses an LLM with tool calling (specifically [`ReadFileTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ReadFileTool.java:25)) to read the source code of these exemplar files and deduce system-wide architecture patterns (e.g., "Hexagonal Architecture in Booking", "CQRS in Identity"). The results are saved to the [`CtxArchitecturePattern`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxArchitecturePattern.java:1) entity.

---

## 7. Wiki Generation & Semantic Indexing

### 7.1 Agentic Wiki Generation

[`WikiGenerationService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/WikiGenerationService.java:67) executes an agentic ReAct loop. Before starting the ReAct loop, the service uses the `scc` (Sloc, Cloc and Code) CLI tool to calculate repository complexity metrics (lines of code and file count). The system enforces a hard dependency on `scc` and will throw a fatal exception if the binary is missing or fails. These metrics are combined into a weighted composite score (60% lines of code, 40% file count) to dynamically determine the target wiki page count (ranging from 2 pages for simple codebases to 20 pages for highly complex repositories), guiding the LLM generation scope.

It is provided with pre-fetched high-level context (Architectural Patterns and Code Dimensions) and has access to the following tools:

- [`ReadFileTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ReadFileTool.java:25): Reads source code.
- [`ReadWikiPageTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/ReadWikiPageTool.java:22): Reads existing wiki pages for context.
- [`WriteWikiPageTool`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/WriteWikiPageTool.java:38): Creates or updates wiki pages with a unique `pageSlug`, title, and markdown content.

The LLM iteratively builds a hierarchical wiki, constrained by a maximum turn limit (circuit breaker) to prevent infinite loops.

### 7.2 Semantic Indexing
[`SemanticIndexingService`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/write/SemanticIndexingService.java:44) chunks the generated wiki pages using `TokenTextSplitter` (default ~800 tokens per chunk). It then generates vector embeddings using the team's configured embedding model and saves them to the [`CtxEmbedding`](control-plane/src/main/java/com/kratisai/controlplane/model/CtxEmbedding.java:1) table, enabling HNSW similarity search for vague intent resolution.

---

## 8. State Management & Concurrency

- **Deterministic Progression**: The core pipeline is strictly linear, with phase-level transactions ensuring partial progress is saved.
- **Concurrent Execution**: During the `RESEARCH_DIMENSIONS` phase, Java 21 Virtual Threads process every dimension's LLM prompt in parallel, bounded by a configurable semaphore (`ingestionProperties.getDimensionResearch().getMaxConcurrency()`), without deadlocking over shared state.
- **Queue Recovery**: [`IngestionQueueManager`](control-plane/src/main/java/com/kratisai/controlplane/ingestion/IngestionQueueManager.java:77) listens for `ApplicationReadyEvent`. On startup, it clears data for orphaned `PROCESSING` batches, resets them to `QUEUED`, and resubmits them to the async worker, ensuring resilience against abrupt shutdowns.

---

## 9. Planning Agent Tools

The hydrated Context Layer is exposed to the planning agent as Spring AI `@Tool`
function beans, invoked by the ReAct loop. Agents never write SQL; every tool is
scoped to the requesting team.

| Tool class | Tools |
| --- | --- |
| `RepositoryTool` | `list_repositories`, `read_remote_file`, `get_dependencies`, `search_files` |
| `DimensionTool` | `list_dimensions`, `get_dimension`, `list_architecture_patterns` |
| `WikiTool` | `list_wiki_pages`, `read_wiki_page`, `search_wiki` |
| `WebSearchTool` | `web_search` |
| `ScratchpadTool` | `save_to_scratchpad`, `delete_from_scratchpad` |
| `CanvasTool` | `list_canvas_documents`, `retrieve_from_canvas`, `write_canvas`, `patch_canvas` |

`get_dependencies` traverses the Graph tier to trace the blast radius of a proposed
change, filtering critical edges (`CALLS`, `IMPLEMENTS`, `HTTP_CALLS`).

---

## 10. Domain Model Summary

| Entity | Description |
| --- | --- |
| `CtxNode` | Represents a file, class, or method in the codebase. |
| `CtxEdge` | Represents a relationship between nodes (e.g., `IMPORTS`, `CALLS`, `EXTENDS`). |
| `CtxDimension` | Represents a discovered dimension (`DOMAIN`, `ARCHETYPE`, `CROSS_CUTTING`) with a name, synopsis, and glob patterns. |
| `CtxNodeDimension` | Join entity mapping a `CtxNode` to a `CtxDimension`, including a `rankScore` from subgraph PageRank. |
| `CtxArchitecturePattern` | Represents a deduced system-wide architecture pattern with a description and exemplar file paths. |
| `CtxWikiPage` | Represents a generated wiki page with a `pageSlug`, title, content, and optional parent page. |
| `CtxEmbedding` | Represents a vector embedding of a wiki page chunk for semantic search. |
