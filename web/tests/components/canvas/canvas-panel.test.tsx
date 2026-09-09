import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { CanvasPanel } from "@/components/canvas/canvas-panel";
import { useCanvasStore } from "@/store/canvas-store";

vi.mock("@/hooks/use-providers", () => ({
  useProviders: vi.fn(() => ({ data: [] })),
}));

vi.mock("@/hooks/use-environments", () => ({
  useEnvironments: vi.fn(() => ({ data: [] })),
}));

vi.mock("@/hooks/use-harnesses", () => ({
  useHarnesses: vi.fn(() => ({ data: [{ name: "OpenCode", value: "OPENCODE" }] })),
}));

vi.mock("@/hooks/use-credentials", () => ({
  useCredentials: vi.fn(() => ({ data: [] })),
}));

vi.mock("@/hooks/use-executions", () => ({
  useChatExecutions: vi.fn(() => ({
    data: [],
    refetch: vi.fn(),
  })),
}));

describe("CanvasPanel", () => {
  beforeEach(() => {
    useCanvasStore.setState({ canvases: {} });
  });

  it("renders empty state when no canvases exist", () => {
    render(<CanvasPanel chatId="session-1" docId="" />);
    expect(screen.getByText(/no canvas documents yet/i)).toBeInTheDocument();
  });

  it("renders the requested canvas document", () => {
    useCanvasStore.setState({
      canvases: {
        "session-1": [
          {
            canvasType: "SPEC",
            chatId: "session-1",
            content: "# Hello World",
            documentId: "doc-1",
            isNewRepo: false,
            title: "Test Canvas",
            version: 1,
          },
        ],
      },
    });

    render(<CanvasPanel chatId="session-1" docId="doc-1" />);
    expect(screen.getByText("Test Canvas")).toBeInTheDocument();
    expect(screen.getByText("Hello World")).toBeInTheDocument();
  });

  it("falls back to the first document when the requested doc does not exist", () => {
    useCanvasStore.setState({
      canvases: {
        "session-1": [
          {
            canvasType: "SPEC",
            chatId: "session-1",
            content: "# First",
            documentId: "doc-1",
            isNewRepo: false,
            title: "First Canvas",
            version: 1,
          },
        ],
      },
    });

    render(<CanvasPanel chatId="session-1" docId="missing-doc" />);
    expect(screen.getByText("First Canvas")).toBeInTheDocument();
  });
});
