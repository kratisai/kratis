import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { UserMessageInput } from '@/components/chat/user-message-input'
import * as useModelProvidersModule from '@/hooks/use-model-providers'
import * as useUIStoreModule from '@/store/ui-store'

vi.mock('@/hooks/use-model-providers')
vi.mock('@/store/ui-store')

// Helper to get the send button (the one with the Send icon)
function getSendButton() {
  const buttons = screen.getAllByRole('button')
  // Send button is the last button in the component
  return buttons[buttons.length - 1]
}

describe('UserMessageInput', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useModelProvidersModule.useModelProviders).mockReturnValue({
      data: [],
      isError: false,
      isLoading: false,
    } as never)
    // Default mock: model is selected
    vi.mocked(useUIStoreModule.useUIStore).mockReturnValue({
      selectedModelName: 'gpt-4',
      selectedProviderId: 'openai-1',
    })
  })

  it('renders with placeholder', () => {
    render(<UserMessageInput onSend={vi.fn()} />)
    expect(screen.getByPlaceholderText(/type a message/i)).toBeInTheDocument()
  })

  it('renders with custom placeholder', () => {
    render(<UserMessageInput onSend={vi.fn()} placeholder="Ask something..." />)
    expect(screen.getByPlaceholderText('Ask something...')).toBeInTheDocument()
  })

  it('calls onSend with trimmed message when send button clicked', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<UserMessageInput onSend={onSend} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, 'Hello world')

    await user.click(getSendButton())

    expect(onSend).toHaveBeenCalledWith('Hello world')
  })

  it('calls onSend when Enter is pressed', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<UserMessageInput onSend={onSend} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, 'Hello world')
    await user.keyboard('{Enter}')

    expect(onSend).toHaveBeenCalledWith('Hello world')
  })

  it('does not send empty message', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<UserMessageInput onSend={onSend} />)

    await user.click(getSendButton())

    expect(onSend).not.toHaveBeenCalled()
  })

  it('does not send when disabled', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<UserMessageInput disabled onSend={onSend} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, 'Hello world')

    await user.click(getSendButton())

    expect(onSend).not.toHaveBeenCalled()
  })

  it('clears input after sending', async () => {
    const onSend = vi.fn()
    const user = userEvent.setup()
    render(<UserMessageInput onSend={onSend} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, 'Hello world')
    await user.keyboard('{Enter}')

    expect(textarea).toHaveValue('')
  })

  it('renders with initial value', () => {
    render(<UserMessageInput initialValue="Initial text" onSend={vi.fn()} />)
    expect(screen.getByPlaceholderText(/type a message/i)).toHaveValue('Initial text')
  })

  it('send button is disabled when input is only whitespace', async () => {
    const user = userEvent.setup()
    render(<UserMessageInput onSend={vi.fn()} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, '   ')

    expect(getSendButton()).toBeDisabled()
  })

  it('renders textarea with minimum rows', () => {
    render(<UserMessageInput onSend={vi.fn()} />)
    const textarea = screen.getByPlaceholderText(/type a message/i)
    expect(textarea).toHaveAttribute('rows', '3')
  })

  it('renders model selector inside wrapper with border', () => {
    render(<UserMessageInput onSend={vi.fn()} />)
    // The wrapper should have a border class
    const wrapper = screen.getByPlaceholderText(/type a message/i).closest('.border-2')
    expect(wrapper).toBeInTheDocument()
  })

  it('send button is disabled when no model is selected', async () => {
    // Override the mock to have no model selected
    vi.mocked(useUIStoreModule.useUIStore).mockReturnValue({
      selectedModelName: null,
      selectedProviderId: null,
    })

    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<UserMessageInput onSend={onSend} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, 'Hello world')

    expect(getSendButton()).toBeDisabled()
    expect(onSend).not.toHaveBeenCalled()
  })

  it('send button is disabled when only providerId is missing', async () => {
    vi.mocked(useUIStoreModule.useUIStore).mockReturnValue({
      selectedModelName: 'gpt-4',
      selectedProviderId: null,
    })

    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<UserMessageInput onSend={onSend} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, 'Hello world')

    expect(getSendButton()).toBeDisabled()
    expect(onSend).not.toHaveBeenCalled()
  })

  it('send button is disabled when only modelName is missing', async () => {
    vi.mocked(useUIStoreModule.useUIStore).mockReturnValue({
      selectedModelName: null,
      selectedProviderId: 'openai-1',
    })

    const user = userEvent.setup()
    const onSend = vi.fn()
    render(<UserMessageInput onSend={onSend} />)

    const textarea = screen.getByPlaceholderText(/type a message/i)
    await user.type(textarea, 'Hello world')

    expect(getSendButton()).toBeDisabled()
    expect(onSend).not.toHaveBeenCalled()
  })
})
