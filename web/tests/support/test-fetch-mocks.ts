import { afterEach, beforeEach, vi } from 'vitest'

import type { ChatDto } from '@/lib/chat-api'
import type { ExecutionEnvironmentDto } from '@/lib/environment-api'
import type { AuthTokensResponse, RepositoryDto, TeamDto } from '@/types/auth-types'

import { useWebSocketStore } from '@/store/websocket-store'

import { allInstances, MockWebSocket } from './test-websocket'

// ---------------------------------------------------------------------------
// Core infrastructure
// ---------------------------------------------------------------------------

type FetchHandler = (url: string, options: RequestInit) => null | Response

let fetchHandlers: FetchHandler[] = []

export function addFetchHandler(handler: FetchHandler) {
  fetchHandlers.push(handler)
}

export function jsonResponse<T = unknown>(data: T, status = 200) {
  return new Response(JSON.stringify(data), {
    headers: { 'Content-Type': 'application/json' },
    status,
  })
}

export function mockAffectedRepositories(
  credentialId: string,
  repos: Array<Record<string, unknown>> = [],
) {
  addFetchHandler((url) => {
    if (url.includes(`/credentials/${credentialId}/affected-repositories`)) {
      return jsonResponse(repos)
    }
    return null
  })
}

export function mockCancelPermission(shouldFail = false) {
  addFetchHandler((url, options) => {
    if (url.includes('/api/v1/hitl-rules/cancel') && options.method === 'POST') {
      if (shouldFail) return jsonResponse({ message: 'Not found' }, 404)
      if (!hasBearerToken(options.headers)) return jsonResponse({ message: 'Forbidden' }, 403)
      return new Response(null, { status: 204 })
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Lifecycle hooks – call once at the top of each describe block
// ---------------------------------------------------------------------------

export function mockCreateChat(response = { createdAt: new Date().toISOString(), id: 'new-session-id' }) {
  addFetchHandler((url, options) => {
    const isExactSessionUrl = url.endsWith('/api/v1/chats') || url.includes('/api/v1/chats?')
    if (isExactSessionUrl && options.method === 'POST') {
      return jsonResponse(response, 202)
    }
    return null
  })
}

export function mockCreateCredential(response?: Record<string, unknown>) {
  addFetchHandler((url, options) => {
    if (url.includes('/credentials') && options.method === 'POST') {
      return jsonResponse(
        {
          createdAt: '2024-01-01T00:00:00Z',
          id: 'cred-new',
          name: 'New Credential',
          type: 'GITLAB',
          ...response,
        },
        201,
      )
    }
    return null
  })
}

export function mockCreateEnvironment(response?: Record<string, unknown>) {
  addFetchHandler((url, options) => {
    if (url.includes('/environments') && options.method === 'POST') {
      return jsonResponse(
        {
          environment: {
            containerId: null,
            createdAt: '2024-01-01T00:00:00Z',
            id: 'env-new',
            lastHeartbeat: null,
            name: 'New Connector',
            status: 'DISCONNECTED',
            teamId: 'team-1',
            type: 'CONNECTOR',
            updatedAt: '2024-01-01T00:00:00Z',
          },
          installCommand:
            'kratis-connector --mode=daemon --server-url=ws://localhost:8080/ws/env --token=test-token',
          ...response,
        },
        201,
      )
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Auth mocks
// ---------------------------------------------------------------------------

export function mockCreateExecution(response?: Record<string, unknown>) {
  const calls: Array<Record<string, unknown>> = []
  addFetchHandler((url, options) => {
    if (url.includes('/executions') && options.method === 'POST') {
      const body = JSON.parse((options.body as string) || '{}')
      calls.push(body)
      return jsonResponse(
        {
          chatId: 'session-1',
          command: body.command || '',
          createdAt: '2024-01-01T00:00:00Z',
          environmentId: body.environmentId || 'env-1',
          id: 'exec-1',
          startedAt: '2024-01-01T00:00:00Z',
          status: 'PENDING',
          ...response,
        },
        201,
      )
    }
    return null
  })
  return calls
}

export function mockCreateModelProvider(response?: Record<string, unknown>) {
  addFetchHandler((url, options) => {
    if (url.includes('/model-providers') && options.method === 'POST') {
      return jsonResponse(
        {
          createdAt: '2024-01-01T00:00:00Z',
          displayName: 'New Provider',
          id: 'provider-new',
          isActive: true,
          providerType: 'OPENAI',
          teamId: 'team-1',
          updatedAt: '2024-01-01T00:00:00Z',
          ...response,
        },
        201,
      )
    }
    return null
  })
}

export function mockCreateRepository(shouldFail = false) {
  addFetchHandler((url, options) => {
    if (url.includes('/repositories') && options.method === 'POST') {
      if (shouldFail) {
        return jsonResponse({ message: 'Repository name already exists' }, 409)
      }
      return jsonResponse(
        {
          branch: 'main',
          createdAt: '2024-01-01T00:00:00Z',
          id: 'repo-1',
          name: 'new-repo',
          teamId: 'team-1',
          updatedAt: '2024-01-01T00:00:00Z',
          url: 'https://github.com/test/repo.git',
        },
        201,
      )
    }
    return null
  })
}

export function mockCreateTeam() {
  addFetchHandler((url, options) => {
    if (url.includes('/api/v1/teams') && options.method === 'POST') {
      return jsonResponse(
        {
          createdAt: '2024-01-01T00:00:00Z',
          id: 'team-new',
          name: 'New Team',
          role: 'owner',
          updatedAt: '2024-01-01T00:00:00Z',
        },
        201,
      )
    }
    return null
  })
}
export function mockDeleteCredential(credentialId: string, deletedIds: string[] = []) {
  addFetchHandler((url, options) => {
    if (url.includes(`/credentials/${credentialId}`) && options.method === 'DELETE') {
      deletedIds.push(credentialId)
      return jsonResponse(null, 204)
    }
    return null
  })
}

export function mockDeleteEnvironment(envId: string) {
  addFetchHandler((url, options) => {
    if (
      url.includes(`/environments/${envId}`) &&
      options.method === 'DELETE'
    ) {
      return new Response(null, { status: 204 })
    }
    return null
  })
}

export function mockDeleteModelProvider(providerId: string) {
  addFetchHandler((url, options) => {
    if (
      url.includes(`/model-providers/teams/`) &&
      url.includes(`/${providerId}`) &&
      options.method === 'DELETE'
    ) {
      return new Response(null, { status: 204 })
    }
    return null
  })
}

export function mockDiscoverModels(models: Array<{ kind: string; modelName: string }> = []) {
  addFetchHandler((url) => {
    const match = url.match(/\/api\/v1\/model-providers\/[^/]+\/discover-models$/)
    if (match) {
      return jsonResponse(models)
    }
    return null
  })
}

/**
 * Generic escape-hatch: register any method + path-regex handler inline.
 * Prefer a typed `mock*` helper where one exists.
 */
export function mockEndpoint(method: string, pathRegex: RegExp, response: unknown, status = 200) {
  addFetchHandler((url, options) => {
    if (
      pathRegex.test(url) &&
      (!options.method || options.method.toUpperCase() === method.toUpperCase())
    ) {
      return jsonResponse(response, status)
    }
    return null
  })
}

export function mockGenerateSshKey(response?: Record<string, unknown>) {
  addFetchHandler((url, options) => {
    if (url.includes('/credentials/generate-ssh-key') && options.method === 'POST') {
      return jsonResponse(
        {
          createdAt: '2024-01-01T00:00:00Z',
          id: 'cred-ssh-new',
          name: 'SSH Key',
          publicKey: 'ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ...',
          type: 'SSH_KEY',
          ...response,
        },
        201,
      )
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Credential mocks
// ---------------------------------------------------------------------------

export function mockGetTeam(team: Record<string, unknown>) {
  addFetchHandler((url) => {
    if (url === `/api/v1/teams/${team.id}`) {
      return jsonResponse(team)
    }
    return null
  })
}

export function mockGetWikiPage(pageId: string, page: Record<string, unknown>) {
  addFetchHandler((url) => {
    if (url.endsWith(`/wiki/pages/${pageId}`)) {
      return jsonResponse(page)
    }
    return null
  })
}

export function mockInviteMember() {
  addFetchHandler((url, options) => {
    if (
      url.includes('/api/v1/teams/') &&
      url.includes('/members') &&
      options.method === 'POST'
    ) {
      return jsonResponse(
        {
          displayName: 'New Member',
          email: 'newmember@example.com',
          id: 'member-new',
          role: 'MEMBER',
          userId: 'user-new',
        },
        201,
      )
    }
    return null
  })
}

export function mockListChatExecutions(executions: Array<Record<string, unknown>> = []) {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/chats\/[^/]+\/executions$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse(executions)
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Environment mocks
// ---------------------------------------------------------------------------

export function mockListChats(chats: Array<ChatDto> = []) {
  addFetchHandler((url, options) => {
    // Match the chat collection only, not sub-resources like /chats/{id}/executions.
    const isChatList = url === '/api/v1/chats' || url.startsWith('/api/v1/chats?')
    if (isChatList && (options.method === 'GET' || !options.method)) {
      return jsonResponse(chats)
    }
    return null
  })
}

export function mockListCredentials(credentials: Array<Record<string, unknown>> = []) {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/credentials$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse(credentials)
    }
    return null
  })
}

export function mockListEnvironments(
  environments: Array<Partial<ExecutionEnvironmentDto>> = [],
) {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/environments$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse(environments)
    }
    return null
  })
}

export function mockListHarnesses(harnesses: Array<{ name: string; value: string }> = [{ name: 'OpenCode', value: 'OPENCODE' }]) {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/harnesses$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse(harnesses)
    }
    return null
  })
}

export function mockListModelProviders(providers: Array<Record<string, unknown>> = []) {
  addFetchHandler((url) => {
    if (url.includes('/model-providers')) {
      return jsonResponse(providers)
    }
    return null
  })
}

export function mockListProviders(providers: Array<Record<string, unknown>> = []) {
  addFetchHandler((url, options) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/environment-providers$/)
    if (match && (!options.method || options.method === 'GET')) {
      return jsonResponse(providers)
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Model provider mocks
// ---------------------------------------------------------------------------

export function mockListRemoteRepositories(repos: Array<Record<string, unknown>> = []) {
  addFetchHandler((url) => {
    if (url.includes('/available-repos')) {
      return jsonResponse(repos)
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Repository mocks
// ---------------------------------------------------------------------------

export function mockListRepositories(repos: Array<RepositoryDto> = []) {
  addFetchHandler((url) => {
    const match = url.match(/\/api\/v1\/teams\/[^/]+\/repositories$/)
    if (match) {
      return jsonResponse(repos)
    }
    return null
  })
}

export function mockListTeams(teams: Array<TeamDto> = []) {
  addFetchHandler((url) => {
    if (url === '/api/v1/teams' || url === '/api/v1/teams/') {
      return jsonResponse(teams)
    }
    return null
  })
}

export function mockListWikiPageChildren(
  parentPageId: string,
  children: Array<Record<string, unknown>> = [],
) {
  addFetchHandler((url) => {
    if (url.includes(`/wiki/pages/${parentPageId}/children`)) {
      return jsonResponse(children)
    }
    return null
  })
}



export function mockListWikiPages(pages: Array<Record<string, unknown>> = []) {
  addFetchHandler((url) => {
    if (url.includes('/wiki/pages') && url.endsWith('/pages')) {
      return jsonResponse(pages)
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Session mocks
// ---------------------------------------------------------------------------

export function mockLogin(response?: Partial<AuthTokensResponse>) {
  addFetchHandler((url, options) => {
    if (url.includes('/api/v1/auth/login') && options.method === 'POST') {
      return jsonResponse({
        accessToken: 'test-access-token',
        expiresIn: 3600,
        refreshToken: 'test-refresh-token',
        user: {
          createdAt: '2024-01-01T00:00:00Z',
          displayName: 'Test User',
          email: 'test@test.com',
          id: 'user-1',
        },
        ...response,
      })
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Team mocks
// ---------------------------------------------------------------------------

export function mockLoginShouldFail(errorMessage = 'Invalid credentials') {
  addFetchHandler((url, options) => {
    if (url.includes('/api/v1/auth/login') && options.method === 'POST') {
      return jsonResponse({ message: errorMessage }, 401)
    }
    return null
  })
}

export function mockRefreshToken(shouldFail = false) {
  addFetchHandler((url, options) => {
    if (url === '/api/v1/auth/refresh' && options.method === 'POST') {
      if (shouldFail) {
        return jsonResponse({ message: 'Invalid refresh token' }, 401)
      }
      return jsonResponse({
        accessToken: 'new-access-token',
        expiresIn: 3600,
        refreshToken: 'new-refresh-token',
      })
    }
    return null
  })
}

export function mockRegister() {
  addFetchHandler((url, options) => {
    if (url.includes('/api/v1/auth/register') && options.method === 'POST') {
      return jsonResponse(
        {
          createdAt: '2024-01-01T00:00:00Z',
          displayName: 'Test User',
          email: 'test@test.com',
          id: 'user-1',
        },
        201,
      )
    }
    return null
  })
}

export function mockResolvePermission(shouldFail = false) {
  addFetchHandler((url, options) => {
    if (url.includes('/api/v1/hitl-rules/resolve') && options.method === 'POST') {
      if (shouldFail) return jsonResponse({ message: 'Not found' }, 404)
      if (!hasBearerToken(options.headers)) return jsonResponse({ message: 'Forbidden' }, 403)
      return new Response(null, { status: 204 })
    }
    return null
  })
}

// ---------------------------------------------------------------------------
// Wiki mocks
// ---------------------------------------------------------------------------

export function mockSupportedProviderTypes(
  types: Array<Record<string, unknown>> = [
    {
      defaultBaseUrl: 'https://api.openai.com/v1',
      description: 'OpenAI API',
      displayName: 'OpenAI',
      requiresApiKey: true,
      requiresBaseUrl: false,
      type: 'OPENAI',
    },
    {
      defaultBaseUrl: 'https://api.anthropic.com',
      description: 'Anthropic Claude',
      displayName: 'Anthropic',
      requiresApiKey: true,
      requiresBaseUrl: false,
      type: 'ANTHROPIC',
    },
    {
      defaultBaseUrl: 'http://localhost:11434',
      description: 'Local Ollama instance',
      displayName: 'Ollama',
      requiresApiKey: false,
      requiresBaseUrl: true,
      type: 'OLLAMA',
    },
  ],
) {
  addFetchHandler((url) => {
    if (url.includes('/model-providers/supported-types')) {
      return jsonResponse(types)
    }
    return null
  })
}

export function mockTerminateEnvironment(envId: string) {
  addFetchHandler((url, options) => {
    if (
      url.includes(`/environments/${envId}/terminate`) &&
      options.method === 'POST'
    ) {
      return new Response(null, { status: 204 })
    }
    return null
  })
}

export function mockTestConnection(
  result: { error?: string; models?: string[]; success: boolean } = {
    models: ['gpt-4', 'gpt-3.5-turbo'],
    success: true,
  },
  shouldFail = false,
) {
  addFetchHandler((url, options) => {
    if (url.includes('/model-providers/test-connection') && options.method === 'POST') {
      if (shouldFail) {
        return jsonResponse({ message: 'Connection failed' }, 500)
      }
      return jsonResponse(result)
    }
    return null
  })
}

export function mockTriggerIngestion(repoId = 'repo-1', shouldFail = false) {
  addFetchHandler((url, options) => {
    if (url.endsWith(`/repositories/${repoId}/ingest`) && options.method === 'POST') {
      if (shouldFail) {
        return jsonResponse({ message: 'Failed to start ingestion' }, 500)
      }
      return jsonResponse({ batchId: 'batch-1', status: 'QUEUED' }, 202)
    }
    return null
  })
}

export function mockUpdateCredential(credentialId: string, response?: Record<string, unknown>) {
  addFetchHandler((url, options) => {
    if (url.includes(`/credentials/${credentialId}`) && options.method === 'PUT') {
      return jsonResponse({
        createdAt: '2024-01-01T00:00:00Z',
        id: credentialId,
        name: 'Updated Credential',
        type: 'GITLAB',
        ...response,
      })
    }
    return null
  })
}

export function mockUpdateProfile(user?: Record<string, unknown>) {
  addFetchHandler((url, options) => {
    if (
      url.includes('/users/profile') &&
      (options.method === 'PUT' || options.method === 'PATCH')
    ) {
      return jsonResponse({
        createdAt: '2024-01-01T00:00:00Z',
        displayName: 'Test User',
        email: 'test@test.com',
        id: 'user-1',
        ...user,
      })
    }
    return null
  })
}

export function mockValidateGitHubAppInstallation(
  result: { exists: boolean; installationId: string; isValid: boolean } = {
    exists: true,
    installationId: '12345',
    isValid: true,
  },
) {
  addFetchHandler((url, options) => {
    if (
      url.includes('/credentials/validate-github-app-installation') &&
      options.method === 'POST'
    ) {
      return jsonResponse(result)
    }
    return null
  })
}

export function setupFetchMock() {
  beforeEach(() => {
    fetchHandlers = []
    allInstances.length = 0
    vi.stubGlobal('WebSocket', MockWebSocket)
    mockListChats([])
    mockCreateChat()

    // Polyfills for jsdom – Radix UI uses methods jsdom doesn't implement
    if (typeof Element.prototype.hasPointerCapture !== 'function') {
      Element.prototype.hasPointerCapture = () => false
    }
    if (typeof Element.prototype.scrollIntoView !== 'function') {
      Element.prototype.scrollIntoView = () => {}
    }
    if (typeof globalThis.ResizeObserver === 'undefined') {
      globalThis.ResizeObserver = class ResizeObserver {
        disconnect() {}
        observe() {}
        unobserve() {}
      }
    }

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: Request | string | URL, init?: RequestInit) => {
        const url =
          typeof input === 'string'
            ? input
            : input instanceof URL
              ? input.toString()
              : input.url
        const options = init ?? {}

        for (const handler of fetchHandlers) {
          const response = handler(url, options)
          if (response) return response
        }
        // Default: return 404 for unhandled requests
        return new Response(JSON.stringify({ message: 'No mock handler found' }), {
          headers: { 'Content-Type': 'application/json' },
          status: 404,
        })
      }),
    )
  })

  afterEach(() => {
    useWebSocketStore.getState().disconnect()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    fetchHandlers = []
  })
}

function hasBearerToken(headers: HeadersInit | undefined): boolean {
  const value = new Headers(headers).get('authorization')
  return value === 'Bearer test-access-token'
}
