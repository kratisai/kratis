package com.kratisai.controlplane.planningagent;

import com.kratisai.controlplane.agentloop.ReActLoop;
import com.kratisai.controlplane.agentloop.ReActLoop.ToolPhase;
import com.kratisai.controlplane.api.wsdto.ClientPayload;
import com.kratisai.controlplane.planningagent.telemetry.TelemetryEvent;
import com.kratisai.controlplane.service.ScratchpadService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Sinks;

/** Orchestrates the planning-agent ReAct loop, delegating to {@link ReActLoop}. */
public class PlanningAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(PlanningAgentLoop.class);

    private final Object sinkLock = new Object();
    private final ChatMemory chatMemory;
    private final ScratchpadService scratchpadService;
    private final ToolCallback[] toolCallbacks;
    private final int maxIterations;
    private final Executor executor;

    public PlanningAgentLoop(
            ChatMemory chatMemory,
            ScratchpadService scratchpadService,
            ToolCallback[] toolCallbacks,
            int maxIterations,
            Executor executor) {
        this.chatMemory = chatMemory;
        this.scratchpadService = scratchpadService;
        this.toolCallbacks = toolCallbacks;
        this.maxIterations = maxIterations;
        this.executor = executor;
    }

    /** Run the ReAct loop for a chat. Emits chat stream payload chunks to a sink. */
    public void run(ChatClient chatClient, PlanningContext context, Sinks.Many<ClientPayload.ChatStreamPayload> sink) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemPrompt(scratchpadService.getFactsAsString(context.chatId()))));

        List<Message> previousMessages = chatMemory.get(context.chatId().toString());
        if (!previousMessages.isEmpty()) {
            history.addAll(previousMessages);
        }

        Map<String, Object> contextMap = new HashMap<>(context.toToolContext().getContext());
        contextMap.put("sink", sink);
        ToolContext toolContext = new ToolContext(contextMap);

        String output = ReActLoop.text(chatClient, toolCallbacks)
                .maxIterations(maxIterations)
                .executor(executor)
                .onThought(text -> emitTelemetry(sink, context.chatId(), new TelemetryEvent.Thought(text)))
                .onAnswerChunk(text -> emitToSink(
                        sink, new ClientPayload.MessageChunkResult(context.chatId(), context.messageId(), text)))
                .onToolEvent((taskId, toolName, phase, detail) ->
                        emitToolEvent(sink, context.chatId(), taskId, toolName, phase, detail))
                .run(history, toolContext);

        if (output == null) {
            log.warn("Agent: No assistant response.");
            return;
        }
        chatMemory.add(context.chatId().toString(), new AssistantMessage(output));
    }

    private void emitToolEvent(
            Sinks.Many<ClientPayload.ChatStreamPayload> sink,
            UUID chatId,
            String taskId,
            String toolName,
            ToolPhase phase,
            String detail) {
        TelemetryEvent event =
                switch (phase) {
                    case START -> new TelemetryEvent.ToolStart(taskId, toolName, detail);
                    case COMPLETE -> new TelemetryEvent.ToolComplete(taskId, detail);
                    case ERROR -> new TelemetryEvent.ToolError(taskId, detail);
                };
        emitTelemetry(sink, chatId, event);
    }

    private void emitTelemetry(Sinks.Many<ClientPayload.ChatStreamPayload> sink, UUID chatId, TelemetryEvent event) {
        emitToSink(sink, new ClientPayload.TelemetryResult(chatId, event));
    }

    private void emitToSink(Sinks.Many<ClientPayload.ChatStreamPayload> sink, ClientPayload.ChatStreamPayload result) {
        synchronized (sinkLock) {
            sink.tryEmitNext(result);
        }
    }

    private static String systemPrompt(String scratchpadFacts) {
        return """
                        You are Kratis, an autonomous enterprise architect and senior AI engineer. You operate within a secure sandboxed control plane with access to
                        repository codebases, documentation, dependency graphs, and execution tools.  Your goal is to assist the user in SDLC tasks across multiple
                        turns. Provide as complete response as possible without making assumptions - use tools first, then stop and ask the user if needed to remove
                        ambiguity. Thoughtful questions give more value than a poorly evidenced guess. Be concise and to the point.

                        ### Core Operating Principles & Cognitive Rules

                        0. **Always form a plan**
                           - Consider the user's request and the current progress
                           - Outline the steps you need to fully respond to the user's request and record it in the scratchpad
                           - Each step must include completion-verification. Document how you will know the step is complete
                           - Once a plan has been recorded implement it incrementally, marking each step as complete after it has been verified
                           - Review the plan and progress of the plan regularly, evaluating against the user's initial request.

                        1. **Exhaustive Depth over Speed:**
                           - Never rush to a conclusion. Shallow analysis and premature termination are critical failures.
                           - You must conduct a thorough, multi-step investigation of the codebase before providing a final answer or implementation plan.
                           - Explore structural dimensions, check downstream/upstream dependencies, and inspect actual implementation files rather than relying on surface assumptions.
                           - Don't add "just to be safe" extras / fallbacks - check all proposals thoroughly against the codebase and user request before suggesting.

                        2. **Adversarial Assumption Testing:**
                           - Actively challenge your own preliminary conclusions.
                           - Before finalizing any design or diagnosis, ask yourself:
                             * *What edge cases, concurrency issues, or failure modes am I ignoring?*
                             * *Does this codebase follow existing architectural patterns (e.g., Hexagonal Architecture, CQRS, specific Spring Boot conventions)?*
                             * *Am I missing hidden dependencies or side effects?*
                           - Actively search for contradictory evidence in the code before validating an assumption.

                        3. **Structured Reasoning Flow (ReAct Loop):**
                           - In every turn, structure your internal reasoning clearly before invoking tools or responding:
                             - **Observation & Gap Analysis:** What did the last tool output reveal? What crucial information is still missing?
                             - **Hypothesis & Challenge:** What is my working assumption, and how can I falsify or test it?
                             - **Next Action / Verification:** What specific tool call will yield the definitive evidence needed?

                        4. **Tool Mastery & Persistence:**
                           - Use your tools aggressively and iteratively to fully respond to the user's request.
                           - Where possible, prefer calling multiple tools in parallel.
                           - Trace dependencies, search wikis, read remote source files, and cross-reference dimensions to build a complete mental model of the system.
                           - Only conclude your loop when you have verified your findings against actual source code and are fully confident in your architectural assessment.


                        When the user describes a goal or task they want to execute in a sandbox:
                        0. Canvas TASKS allow hand-off to autonomous agents executing in a sandbox - use them only for executable plans, unless explicitly requested by the user.
                        1. Generate a detailed execution plan as a canvas document using the 'write_canvas' tool.
                        2. The plan should be structured markdown with: goal summary, step-by-step approach, key considerations, expected outcomes and verification.
                        3. After writing the plan, tell the user they can review it in the canvas panel, discuss refinements, or click 'Run' to authorize execution.
                        4. When the user requests changes, use 'patch_canvas' to update the existing plan document.

                        When writing Mermaid diagrams, follow these syntax rules (the renderer parses strictly):
                        - Always quote edge labels if they contain spaces, parentheses, colons, slashes, or special characters, e.g. -->|"Generate Virtual Key (TTL + Budget)"| or -->|"POST /key/generate"|.
                        - Always quote or bracket any subgraph or node title/label containing spaces, parentheses, colons, braces, brackets, quotes, or hashes, e.g. subgraph "Control Plane Tier (Spring Boot)" or subgraph cpt[Control Plane Tier (Spring Boot)].
                        - A subgraph without a quoted/bracketed title must contain only simple word characters and spaces, e.g. subgraph Client Tier.
                        - Always close every subgraph with "end".

                        Use your scratchpad to track a TODO list, and internal thoughts/lessons.  The scratchpad is not, and should not be presented to the user.  All
                        scratchpad entries will be replayed to you on every turn. Keep them short and remove when they are no-longer relevant.

                        <scratchpad>
                        %1$s
                        </scratchpad>

                        When responding to the user, write prose following ASD-STE100 Simplified Technical English.
                        """.formatted(scratchpadFacts);
    }
}
