import { act } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'

import { createMockTeam } from '../support/test-factories'
import {
  mockListChats,
  mockListTeams,
  setupFetchMock,
} from '../support/test-fetch-mocks'
import {
  renderIntegration,
  screen,
  setAuthenticated,
  setUnauthenticated,
  waitFor,
} from '../support/test-render'
import { setupConnected } from '../support/test-websocket'

describe('Canvas Plan Rendering', () => {
  setupFetchMock()

  beforeEach(() => {
    setUnauthenticated()
    useChatStore.getState().clearChats()
    useCanvasStore.getState().clearCanvases()
  })

  it('renders canvas plan as markdown with headings, lists, and code blocks', async () => {
    const team = createMockTeam()
    mockListTeams([team])
    mockListChats([
      {
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'Test User',
        id: 'session-1',
        teamId: team.id,
        title: 'Test Session',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])

    setAuthenticated({ teamId: team.id, userId: 'user-1' })

    const planContent = [
      '# Execution Plan',
      '',
      '## Goal',
      'Build a REST API',
      '',
      '## Steps',
      '- Set up project structure',
      '- Implement endpoints',
      '- Add tests',
      '',
      '## Code',
      '```java',
      '@GetMapping("/api")',
      'public String hello() { return "world"; }',
      '```',
    ].join('\n')

    act(() => {
      useCanvasStore.getState().handleCanvasEvent({
        event: {
          canvasType: 'DOCUMENT',
          chatId: 'session-1',
          content: planContent,
          documentId: 'plan-doc-1',
          isNewRepo: false,
          title: 'My Plan',
        },
        type: 'canvas',
      })
    })

    renderIntegration(['/chats/session-1'])
    setupConnected()

    // Verify markdown is rendered
    await waitFor(() => {
      expect(screen.getByText('Execution Plan')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('Goal')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('Build a REST API')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('Steps')).toBeInTheDocument()
    })

    // List items should be rendered
    await waitFor(() => {
      expect(screen.getByText('Set up project structure')).toBeInTheDocument()
    })

    // Code block content should be visible
    await waitFor(() => {
      expect(screen.getByText(/@GetMapping/)).toBeInTheDocument()
    })
  })

  it('re-renders canvas content when updated via WebSocket event', async () => {
    const team = createMockTeam()
    mockListTeams([team])
    mockListChats([
      {
        createdAt: '2024-01-01T00:00:00Z',
        createdByDisplayName: 'Test User',
        id: 'session-1',
        teamId: team.id,
        title: 'Test Session',
        updatedAt: '2024-01-01T00:00:00Z',
      },
    ])

    setAuthenticated({ teamId: team.id, userId: 'user-1' })

    // Initial canvas content
    act(() => {
      useCanvasStore.getState().handleCanvasEvent({
        event: {
          canvasType: 'DOCUMENT',
          chatId: 'session-1',
          content: '# Version 1\nInitial plan content',
          documentId: 'plan-doc-1',
          isNewRepo: false,
          title: 'My Plan',
        },
        type: 'canvas',
      })
    })

    renderIntegration(['/chats/session-1'])
    setupConnected()

    // Wait for initial render
    await waitFor(() => {
      expect(screen.getByText('Version 1')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('Initial plan content')).toBeInTheDocument()
    })

    // Simulate WebSocket canvas update event
    act(() => {
      useCanvasStore.getState().handleCanvasEvent({
        event: {
          chatId: 'session-1',
          content: '# Version 2\nUpdated plan content with new steps',
          documentId: 'plan-doc-1',
          title: 'My Plan',
          version: 2,
        },
        type: 'canvas',
      })
    })

    // Verify updated content is rendered
    await waitFor(() => {
      expect(screen.getByText('Version 2')).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('Updated plan content with new steps')).toBeInTheDocument()
    })
  })
})
