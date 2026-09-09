import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { fireEvent, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { beforeEach, describe, expect, it, vi } from "vitest"

import { FluentSentenceInput } from "@/components/chat/fluent/fluent-sentence-input"
import { getTemplateById } from "@/lib/ask-templates"

vi.mock("@/store/ui-store", () => ({
  useUIStore: vi.fn(() => ({
    selectedModelName: "gpt-4o",
    selectedProviderId: "openai",
  })),
}))

vi.mock("@/hooks/use-repositories", () => ({
  useRepositories: vi.fn(() => ({
    data: [{ id: "repo-1", name: "payments-service" }],
    isLoading: false,
  })),
}))

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
  },
})

function renderWithProviders(ui: React.ReactElement) {
  return render(ui, {
    wrapper: ({ children }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  })
}

describe("FluentSentenceInput", () => {
  const onSendMock = vi.fn()
  const template = getTemplateById("plan-and-grill")!

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it("renders sentence segments and slot controls", () => {
    renderWithProviders(<FluentSentenceInput onSend={onSendMock} template={template} />)

    expect(screen.getByText("Plan a feature in")).toBeTruthy()
    expect(screen.getByText("to implement")).toBeTruthy()
    expect(screen.getByText("and challenge my architectural assumptions.")).toBeTruthy()
    expect(screen.getByText("select repository")).toBeTruthy()
  })

  it("disables Send button when required slots are missing", () => {
    renderWithProviders(<FluentSentenceInput onSend={onSendMock} template={template} />)

    const sendButton = screen.getByRole("button", { name: /send/i })
    expect(sendButton).toBeDisabled()
  })

  it("enables Send and submits compiled prompt when required slots are filled", async () => {
    const user = userEvent.setup()
    renderWithProviders(<FluentSentenceInput onSend={onSendMock} template={template} />)

    // Select repository via click on trigger
    const repoTrigger = screen.getByRole("button", { name: /select repository/i })
    await user.click(repoTrigger)

    const repoOption = await screen.findByText("payments-service")
    await user.click(repoOption)

    // Fill inline text slot
    const input = screen.getByPlaceholderText("describe the feature...")
    fireEvent.change(input, { target: { value: "OAuth2 authentication flow" } })

    const sendButton = screen.getByRole("button", { name: /send/i })
    expect(sendButton).not.toBeDisabled()

    await user.click(sendButton)
    expect(onSendMock).toHaveBeenCalledTimes(1)
    expect(onSendMock).toHaveBeenCalledWith(
      expect.stringContaining("OAuth2 authentication flow"),
    )
  })

  it("toggles prompt preview correctly", () => {
    renderWithProviders(<FluentSentenceInput onSend={onSendMock} template={template} />)

    const previewToggle = screen.getByRole("button", { name: /preview prompt/i })
    expect(screen.queryByText("Generated Agent Prompt")).toBeNull()

    fireEvent.click(previewToggle)
    expect(screen.getByText("Generated Agent Prompt")).toBeTruthy()

    fireEvent.click(previewToggle)
    expect(screen.queryByText("Generated Agent Prompt")).toBeNull()
  })
})
