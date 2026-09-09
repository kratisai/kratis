import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { CanvasDocument } from "@/types/canvas-types";

import { CanvasTabs } from "@/components/canvas/canvas-tabs";
import * as useModelProvidersModule from "@/hooks/use-model-providers";
import * as canvasApi from "@/lib/canvas-api";
import * as executionApi from "@/lib/execution-api";
import { useAuthStore } from "@/store/auth-store";
import { useCanvasStore } from "@/store/canvas-store";
import { useChatStore } from "@/store/chat-store";
import { useExecutionStore } from "@/store/execution-store";
import { useUIStore } from "@/store/ui-store";

const navigateMock = vi.fn();

vi.mock("@tanstack/react-router", () => ({
  useNavigate: vi.fn(() => navigateMock),
}));

// Polyfills for Radix UI Select in jsdom
beforeEach(() => {
  if (typeof Element.prototype.hasPointerCapture !== 'function') {
    Element.prototype.hasPointerCapture = () => false;
  }
  if (typeof Element.prototype.scrollIntoView !== 'function') {
    Element.prototype.scrollIntoView = () => {};
  }
});

vi.mock("@/hooks/use-model-providers", () => ({
  useModelProviders: vi.fn(() => ({
    data: [
      {
        displayName: "OpenAI",
        id: "prov-1",
        models: [{ kind: "CHAT" as const, modelName: "gpt-4" }],
      },
    ],
  })),
}));

vi.mock("@/hooks/use-providers", () => ({
  useProviders: vi.fn(() => ({ data: [{ id: "prov-1", name: "Test Provider" }] })),
}));

vi.mock("@/hooks/use-environments", () => ({
  useEnvironments: vi.fn(() => ({ data: [{ id: "env-1", name: "Test Env", status: "CONNECTED", type: "CONNECTOR" }] })),
}));

vi.mock("@/hooks/use-harnesses", () => ({
  useHarnesses: vi.fn(() => ({ data: [{ name: "OpenCode", value: "OPENCODE" }] })),
}));

vi.mock("@/hooks/use-credentials", () => ({
  useCredentials: vi.fn(() => ({ data: [{ id: "cred-1", name: "Test Credential", type: "PAT" }] })),
}));

vi.mock("@/hooks/use-executions", () => ({
  useChatExecutions: vi.fn(() => ({
    data: [],
    refetch: vi.fn(),
  })),
}));

vi.mock("@/lib/execution-api", () => ({
  createSandboxExecution: vi.fn(() => Promise.resolve({ id: "exec-1" })),
}));

vi.mock("@/lib/canvas-api", () => ({
  deleteCanvasDocument: vi.fn(() => Promise.resolve()),
}));

vi.mock("sonner", () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}));

function executableDoc(overrides: Partial<CanvasDocument> = {}): CanvasDocument {
  return {
    canvasType: "SPEC",
    chatId: "session-1",
    content: "",
    documentId: "doc-1",
    isNewRepo: false,
    title: "Doc 1",
    version: 1,
    ...overrides,
  };
}

describe("CanvasTabs", () => {
  beforeEach(() => {
    useAuthStore.setState({ currentTeamId: "team-1", isAuthenticated: true });
    useCanvasStore.setState({ canvases: {} });
    useChatStore.setState({ currentChatId: "session-1" });
    useExecutionStore.setState({
      logs: {},
      terminalOpen: false,
    });
    useUIStore.setState({
      selectedModelName: "gpt-4",
      selectedProviderId: "prov-1",
    });
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: [
        {
          displayName: "OpenAI",
          id: "prov-1",
          models: [{ kind: "CHAT" as const, modelName: "gpt-4" }],
        },
      ],
    } as never);
    navigateMock.mockClear();
    vi.clearAllMocks();
  });

  it("renders nothing when no canvases exist", () => {
    const { container } = render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    expect(container.firstChild).toBeNull();
  });

  it("renders the active document title in the header", () => {
    useCanvasStore.setState({
      canvases: {
        "session-1": [executableDoc(), executableDoc({ documentId: "doc-2", title: "Doc 2" })],
      },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    expect(screen.getByText("Doc 1")).toBeInTheDocument();
    // Doc switching lives in the stage-nav dropdown; other docs are not listed here
    expect(screen.queryByText("Doc 2")).not.toBeInTheDocument();
  });

  it("renders the header transparent without tab chrome", () => {
    useCanvasStore.setState({
      canvases: {
        "session-1": [executableDoc()],
      },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    const header = screen.getByText("Doc 1").parentElement?.parentElement;
    expect(header).toHaveClass("border-b");
    expect(header?.className).not.toContain("bg-muted");
  });

  it("shows the repository label on the active SPEC document", () => {
    useCanvasStore.setState({
      canvases: {
        "session-1": [executableDoc({ repoLabel: "backend" })],
      },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    expect(screen.getByText("(backend)")).toBeInTheDocument();
  });

  it("does not show a repository label for DOCUMENT canvases", () => {
    useCanvasStore.setState({
      canvases: {
        "session-1": [executableDoc({ canvasType: "DOCUMENT", repoLabel: "backend" })],
      },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    expect(screen.queryByText("(backend)")).not.toBeInTheDocument();
  });

  it("hides Run and History for DOCUMENT canvases", () => {
    useCanvasStore.setState({
      canvases: {
        "session-1": [executableDoc({ canvasType: "DOCUMENT" })],
      },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    expect(screen.queryByRole("button", { name: /run/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /history/i })).not.toBeInTheDocument();
  });

  it("hides the History menu when there are no past executions", () => {
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    expect(screen.queryByRole("button", { name: /history/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /run/i })).toBeInTheDocument();
  });

  it("opens launch dialog when Run button is clicked", async () => {
    const user = userEvent.setup();
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    // Click Run button to open dialog
    await user.click(screen.getByRole("button", { name: /run/i }));

    // Dialog should be visible
    expect(screen.getByText("Launch Execution")).toBeInTheDocument();
    expect(screen.getByText("Where should this task run?")).toBeInTheDocument();
    expect(screen.getByText("Which agent harness?")).toBeInTheDocument();
  });

  it("launches execution with providerId via the dialog and navigates to the execution", async () => {
    const user = userEvent.setup();
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    // Open dialog
    await user.click(screen.getByRole("button", { name: /run/i }));

    // Select target - click the trigger button
    const triggers = screen.getAllByRole("combobox");
    await user.click(triggers[0]);
    await user.click(screen.getByText("Docker (Test Provider)"));

    // Select harness
    await user.click(triggers[1]);
    await user.click(screen.getByText("OpenCode"));

    // Click Launch
    await user.click(screen.getByRole("button", { name: /launch/i }));

    expect(executionApi.createSandboxExecution).toHaveBeenCalledWith("session-1", {
      canvasId: "doc-1",
      harness: "OPENCODE",
      modelName: "gpt-4",
      modelProviderId: "prov-1",
      providerId: "prov-1",
    });
    expect(navigateMock).toHaveBeenCalledWith({
      params: { executionId: "exec-1", id: "session-1" },
      to: "/chats/$id/executions/$executionId",
    });
    expect(useExecutionStore.getState().terminalOpen).toBe(true);
    expect(useExecutionStore.getState().logs["exec-1"]).toBeUndefined();
  });

  it("does not show a credential selector for existing-repo canvases", async () => {
    const user = userEvent.setup();
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    await user.click(screen.getByRole("button", { name: /run/i }));

    expect(screen.queryByText(/credential for the new repository/i)).not.toBeInTheDocument();
  });

  it("requires a credential and passes credentialId for new-repo canvases", async () => {
    const user = userEvent.setup();
    useCanvasStore.setState({
      canvases: {
        "session-1": [executableDoc({ isNewRepo: true, repoLabel: "New: fresh-repo" })],
      },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    await user.click(screen.getByRole("button", { name: /run/i }));

    // Credential selector is shown for new-repo canvases
    expect(screen.getByText(/credential for the new repository/i)).toBeInTheDocument();

    const triggers = screen.getAllByRole("combobox");
    await user.click(triggers[0]);
    await user.click(screen.getByText("Docker (Test Provider)"));
    await user.click(triggers[1]);
    await user.click(screen.getByText("OpenCode"));

    // Launch is disabled until a credential is selected
    expect(screen.getByRole("button", { name: /launch/i })).toBeDisabled();

    await user.click(triggers[2]);
    await user.click(screen.getByText("Test Credential"));

    await user.click(screen.getByRole("button", { name: /launch/i }));

    expect(executionApi.createSandboxExecution).toHaveBeenCalledWith("session-1", {
      canvasId: "doc-1",
      credentialId: "cred-1",
      harness: "OPENCODE",
      modelName: "gpt-4",
      modelProviderId: "prov-1",
      providerId: "prov-1",
    });
  });

  it("launches execution using model from launch target instead of global store", async () => {
    const user = userEvent.setup();
    useUIStore.setState({
      selectedModelName: "claude-3",
      selectedProviderId: "anthropic-prov",
    });
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: [
        {
          baseUrl: "https://api.openai.com/v1",
          createdAt: "2024-01-01T00:00:00Z",
          displayName: "OpenAI",
          id: "prov-1",
          isActive: true,
          models: [{ kind: "CHAT" as const, modelName: "gpt-4" }],
          providerType: "OPENAI" as const,
          teamId: "team-1",
          updatedAt: "2024-01-01T00:00:00Z",
        },
        {
          baseUrl: "https://api.anthropic.com",
          createdAt: "2024-01-01T00:00:00Z",
          displayName: "Anthropic",
          id: "anthropic-prov",
          isActive: true,
          models: [{ kind: "CHAT" as const, modelName: "claude-3" }],
          providerType: "ANTHROPIC" as const,
          teamId: "team-1",
          updatedAt: "2024-01-01T00:00:00Z",
        },
      ],
    } as never);
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    // Open dialog
    await user.click(screen.getByRole("button", { name: /run/i }));

    // Select target
    const triggers = screen.getAllByRole("combobox");
    await user.click(triggers[0]);
    await user.click(screen.getByText("Docker (Test Provider)"));

    // Select harness
    await user.click(triggers[1]);
    await user.click(screen.getByText("OpenCode"));

    // Switch model to gpt-4 in the dialog
    await user.click(screen.getByRole("button", { name: "claude-3" }));
    await user.click(screen.getByText("gpt-4"));

    // Click Launch
    await user.click(screen.getByRole("button", { name: /launch/i }));

    expect(executionApi.createSandboxExecution).toHaveBeenCalledWith("session-1", {
      canvasId: "doc-1",
      harness: "OPENCODE",
      modelName: "gpt-4",
      modelProviderId: "prov-1",
      providerId: "prov-1",
    });

    // Global store must retain original chat model and provider
    expect(useUIStore.getState().selectedModelName).toBe("claude-3");
    expect(useUIStore.getState().selectedProviderId).toBe("anthropic-prov");
  });

  it("does not launch execution when no model provider is selected", async () => {
    const user = userEvent.setup();
    useUIStore.setState({ selectedModelName: "gpt-4", selectedProviderId: null });
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: [],
      isError: false,
      isLoading: false,
    } as never);
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    await user.click(screen.getByRole("button", { name: /run/i }));

    const triggers = screen.getAllByRole("combobox");
    await user.click(triggers[0]);
    await user.click(screen.getByText("Docker (Test Provider)"));
    await user.click(triggers[1]);
    await user.click(screen.getByText("OpenCode"));

    expect(screen.getByRole("button", { name: /launch/i })).toBeDisabled();
    expect(executionApi.createSandboxExecution).not.toHaveBeenCalled();
  });

  it("does not launch execution when no model is selected", async () => {
    const user = userEvent.setup();
    useUIStore.setState({ selectedModelName: null, selectedProviderId: "prov-1" });
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: [
        {
          baseUrl: "https://api.openai.com/v1",
          createdAt: "2024-01-01T00:00:00Z",
          displayName: "OpenAI",
          id: "prov-1",
          isActive: true,
          models: [],
          providerType: "OPENAI" as const,
          teamId: "team-1",
          updatedAt: "2024-01-01T00:00:00Z",
        },
      ],
      isError: false,
      isLoading: false,
    } as never);
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    await user.click(screen.getByRole("button", { name: /run/i }));

    const triggers = screen.getAllByRole("combobox");
    await user.click(triggers[0]);
    await user.click(screen.getByText("Docker (Test Provider)"));
    await user.click(triggers[1]);
    await user.click(screen.getByText("OpenCode"));

    expect(screen.getByRole("button", { name: /launch/i })).toBeDisabled();
    expect(executionApi.createSandboxExecution).not.toHaveBeenCalled();
  });

  it("handles execution error gracefully via the dialog", async () => {
    const user = userEvent.setup();
    vi.mocked(executionApi.createSandboxExecution).mockRejectedValueOnce(new Error("Test error"));

    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);

    // Open dialog
    await user.click(screen.getByRole("button", { name: /run/i }));

    // Select target
    const triggers = screen.getAllByRole("combobox");
    await user.click(triggers[0]);
    await user.click(screen.getByText("Docker (Test Provider)"));

    // Select harness
    await user.click(triggers[1]);
    await user.click(screen.getByText("OpenCode"));

    // Click Launch
    await user.click(screen.getByRole("button", { name: /launch/i }));

    const { toast } = await import("sonner");
    expect(toast.error).toHaveBeenCalledWith("Failed to launch execution: Test error");
    expect(useExecutionStore.getState().terminalOpen).toBe(true);
  });

  it("deletes the active document after confirmation and navigates to the remaining document", async () => {
    const user = userEvent.setup();
    useCanvasStore.setState({
      canvases: {
        "session-1": [
          executableDoc(),
          executableDoc({ canvasType: "DOCUMENT", documentId: "doc-2", title: "Doc 2" }),
        ],
      },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    await user.click(screen.getByRole("button", { name: "Delete document" }));

    expect(screen.getByText("Delete Document")).toBeInTheDocument();
    expect(screen.getByText(/Are you sure you want to delete "Doc 1"\?/i)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(canvasApi.deleteCanvasDocument).toHaveBeenCalledWith("session-1", "doc-1");
    expect(useCanvasStore.getState().canvases["session-1"]).toHaveLength(1);
    expect(useCanvasStore.getState().canvases["session-1"][0].documentId).toBe("doc-2");
    expect(navigateMock).toHaveBeenCalledWith({
      params: { docId: "doc-2", id: "session-1" },
      to: "/chats/$id/canvas/$docId",
    });
  });

  it("navigates to the chat when deleting the last document", async () => {
    const user = userEvent.setup();
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    await user.click(screen.getByRole("button", { name: "Delete document" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));

    expect(canvasApi.deleteCanvasDocument).toHaveBeenCalledWith("session-1", "doc-1");
    expect(useCanvasStore.getState().canvases["session-1"]).toBeUndefined();
    expect(navigateMock).toHaveBeenCalledWith({
      params: { id: "session-1" },
      to: "/chats/$id",
    });
  });

  it("shows a delete control for DOCUMENT canvases", () => {
    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc({ canvasType: "DOCUMENT" })] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    expect(screen.getByRole("button", { name: "Delete document" })).toBeInTheDocument();
  });

  it("shows an error toast when the delete request fails", async () => {
    const user = userEvent.setup();
    vi.mocked(canvasApi.deleteCanvasDocument).mockRejectedValueOnce(new Error("Delete failed"));

    useCanvasStore.setState({
      canvases: { "session-1": [executableDoc()] },
    });

    render(<CanvasTabs chatId="session-1" docId="doc-1" />);
    await user.click(screen.getByRole("button", { name: "Delete document" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));

    const { toast } = await import("sonner");
    expect(toast.error).toHaveBeenCalledWith("Failed to delete document: Delete failed");
    expect(useCanvasStore.getState().canvases["session-1"]).toHaveLength(1);
    expect(navigateMock).not.toHaveBeenCalled();
  });
});
