import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { AskKratisView } from "@/components/views/ask-kratis-view"

import { setAuthenticated, setUnauthenticated } from "../../support/test-render"

// Mock dependencies - must be before imports
vi.mock("@/store/ui-store", () => ({
  useUIStore: vi.fn(() => ({
    selectedModelName: "gpt-4o",
    selectedProviderId: "openai",
  })),
}))

vi.mock("@/store/websocket-store", () => ({
  useWebSocketStore: vi.fn((selector) =>
    selector({
      connect: vi.fn(),
      disconnect: vi.fn(),
      error: null,
      isConnected: true,
      isConnecting: false,
    }),
  ),
}))

const mockStartChat = vi.fn()
vi.mock("@/hooks/use-start-chat", () => ({
  useStartChat: vi.fn(() => ({
    isCreating: false,
    startChat: mockStartChat,
  })),
}))

vi.mock("@/store/chat-store", () => ({
  useChatStore: vi.fn((selector) =>
    selector({
      addMessage: vi.fn(),
      currentChatId: null,
      messages: {},
      sendMessage: vi.fn(),
    }),
  ),
}))

vi.mock("@/hooks/use-repositories", () => ({
  useRepositories: vi.fn(() => ({
    data: [
      { id: "repo-1", name: "backend-core" },
      { id: "repo-2", name: "frontend-web" },
    ],
    isLoading: false,
  })),
}))

vi.mock("@tanstack/react-router", async () => {
  const actual = await vi.importActual("@tanstack/react-router")
  return {
    ...actual,
    useNavigate: vi.fn(() => vi.fn()),
    useParams: vi.fn(() => ({})),
  }
})

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
  },
})

function renderWithProviders(ui: React.ReactElement) {
  return render(ui, { wrapper: Wrapper })
}

function Wrapper({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
}

describe("AskKratisView", () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setUnauthenticated()
  })

  it("renders without crashing", () => {
    setAuthenticated({ teamId: "team-1" })
    renderWithProviders(<AskKratisView />)

    expect(screen.getByText("What can I help you build today?")).toBeTruthy()
  })

  it("displays workflow template cards", () => {
    setAuthenticated({ teamId: "team-1" })
    renderWithProviders(<AskKratisView />)

    expect(screen.getByText("Free-form")).toBeTruthy()
    expect(screen.getByText("Plan & Grill")).toBeTruthy()
    expect(screen.getByText("Investigate Error")).toBeTruthy()
    expect(screen.getByText("Architecture Audit")).toBeTruthy()
  })

  it("switches to Plan & Grill fluent sentence builder when clicked", () => {
    setAuthenticated({ teamId: "team-1" })
    renderWithProviders(<AskKratisView />)

    const planCard = screen.getByText("Plan & Grill")
    fireEvent.click(planCard)

    expect(screen.getByText("Plan a feature in")).toBeTruthy()
    expect(screen.getByText("to implement")).toBeTruthy()
    expect(screen.getByText("and challenge my architectural assumptions.")).toBeTruthy()
  })

  it("switches to Investigate Error fluent sentence builder when clicked", () => {
    setAuthenticated({ teamId: "team-1" })
    renderWithProviders(<AskKratisView />)

    const errorCard = screen.getByText("Investigate Error")
    fireEvent.click(errorCard)

    expect(screen.getByText("Diagnose an issue in")).toBeTruthy()
    expect(screen.getByText("with symptoms")).toBeTruthy()
    expect(screen.getByText("and stack trace")).toBeTruthy()
  })
})
