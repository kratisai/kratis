import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { ThemeProvider } from 'next-themes'
import { Toaster } from 'sonner'
import { expect } from 'vitest'

import { queryClient } from '@/lib/query-client'
import { routeTree } from '@/router'
import { useAuthStore } from '@/store/auth-store'

// ---------------------------------------------------------------------------
// QueryClient factory
// ---------------------------------------------------------------------------

/**
 * Renders a component outside the router (e.g. AuthDialog, standalone panels).
 * For views that live inside the router, use `renderWithRouter` instead.
 */
export function renderWithProviders(ui: React.ReactElement) {
  const client = createTestQueryClient()
  return {
    ...render(
      <QueryClientProvider client={client}>
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
          {ui}
          <Toaster />
        </ThemeProvider>
      </QueryClientProvider>,
    ),
    queryClient: client,
    user: userEvent.setup(),
  }
}

// ---------------------------------------------------------------------------
// Render helpers
// ---------------------------------------------------------------------------

/**
 * Renders the full application with real providers and a memory router.
 * Pass `initialEntries` to navigate to the desired view (e.g. `['/repos']`).
 */
export function renderWithRouter(initialEntries: string[] = ['/']) {
  const client = createTestQueryClient()
  const memoryHistory = createMemoryHistory({ initialEntries })
  const testRouter = createRouter({ history: memoryHistory, routeTree })

  return {
    ...render(
      <QueryClientProvider client={client}>
        <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
          <RouterProvider router={testRouter} />
          <Toaster />
        </ThemeProvider>
      </QueryClientProvider>,
    ),
    queryClient: client,
    user: userEvent.setup(),
  }
}

function createTestQueryClient() {
  queryClient.clear()
  queryClient.setDefaultOptions({
    mutations: { retry: false },
    queries: { retry: false },
  })
  return queryClient
}

/** Alias matching the integration-test naming convention. */
export const renderIntegration = renderWithRouter

// ---------------------------------------------------------------------------
// Auth store helpers
// ---------------------------------------------------------------------------

export async function fillFormAndSubmit(
  user: UserEvent,
  fields: Record<string, string>,
  submitButtonName: string,
) {
  for (const [label, value] of Object.entries(fields)) {
    await user.type(screen.getByLabelText(label), value)
  }
  await user.click(screen.getByRole('button', { name: new RegExp(submitButtonName, 'i') }))
}

export function setAuthenticated(options?: { teamId?: string; userId?: string }) {
  useAuthStore.getState().login(
    {
      email: 'test@test.com',
      id: options?.userId ?? 'user-1',
      name: 'Test User',
    },
    'test-access-token',
    'test-refresh-token',
    3600,
  )
  if (options?.teamId) {
    useAuthStore.getState().setCurrentTeamId(options.teamId)
  }
}

// ---------------------------------------------------------------------------
// Form helpers
// ---------------------------------------------------------------------------

export function setUnauthenticated() {
  useAuthStore.getState().logout()
}

// ---------------------------------------------------------------------------
// Toast assertion helpers
// ---------------------------------------------------------------------------

export async function waitForErrorToast(message: string) {
  await waitFor(() => {
    expect(screen.getByText(message)).toBeInTheDocument()
  })
}

export async function waitForSuccessToast(message: string) {
  await waitFor(() => {
    expect(screen.getByText(message)).toBeInTheDocument()
  })
}

// ---------------------------------------------------------------------------
// Re-export testing-library utilities for convenience
// ---------------------------------------------------------------------------

export { screen, waitFor }
