import type { ChatDto } from '@/lib/chat-api'
import type { ExecutionEnvironmentDto } from '@/lib/environment-api'
import type {
  ModelProviderDto,
  RemoteRepositoryDto,
  RepoCredentialDto,
  RepositoryDto,
  TeamDto,
  UserDto,
} from '@/types/auth-types'

export function createMockChat(overrides?: Partial<ChatDto>): ChatDto {
  return {
    createdAt: '2024-01-01T00:00:00Z',
    createdByDisplayName: 'Test User',
    id: 'session-1',
    teamId: 'team-1',
    title: 'Test Session',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  }
}

export function createMockCredential(overrides?: Partial<RepoCredentialDto>): RepoCredentialDto {
  return {
    createdAt: '2024-01-01T00:00:00Z',
    id: 'cred-1',
    name: 'Test Credential',
    type: 'GITLAB',
    ...overrides,
  }
}

export function createMockEnvironment(
  overrides?: Partial<ExecutionEnvironmentDto>,
): ExecutionEnvironmentDto {
  return {
    createdAt: '2024-01-01T00:00:00Z',
    id: 'env-1',
    name: 'Test Environment',
    status: 'RUNNING',
    teamId: 'team-1',
    type: 'DOCKER',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  }
}

export function createMockProvider(overrides?: Partial<ModelProviderDto>): ModelProviderDto {
  return {
    baseUrl: 'https://api.openai.com/v1',
    createdAt: '2024-01-01T00:00:00Z',
    displayName: 'OpenAI',
    id: 'provider-1',
    isActive: true,
    models: [
      { kind: 'CHAT', modelName: 'gpt-4' },
      { kind: 'CHAT', modelName: 'gpt-3.5-turbo' },
    ],
    providerType: 'OPENAI',
    teamId: 'team-1',
    updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  }
}

export function createMockRepository(overrides?: Partial<RepositoryDto>): RepositoryDto {
  return {
    branch: 'main',
    createdAt: '2024-01-01T00:00:00Z',
    id: 'repo-1',
    name: 'frontend-app',
    repositoryType: 'GENERIC',
    teamId: 'team-1',
    updatedAt: '2024-01-01T00:00:00Z',
    url: 'https://github.com/user/frontend-app.git',
    ...overrides,
  }
}
export const createMockSession = createMockChat

export function createMockRemoteRepository(
  overrides?: Partial<RemoteRepositoryDto>,
): RemoteRepositoryDto {
  return {
    cloneUrl: 'https://github.com/user/repo.git',
    defaultBranch: 'main',
    name: 'repo',
    sshUrl: 'git@github.com:user/repo.git',
    ...overrides,
  }
}

export function createMockTeam(overrides?: Partial<TeamDto>): TeamDto {
  return {
    createdAt: '2024-01-01T00:00:00Z',
    id: 'team-1',
    isDefault: true,
    name: 'Test Team',
    role: 'owner',
    tavilyApiKeyConfigured: false,
    ...overrides,
  }
}

export function createMockUser(overrides?: Partial<UserDto>): UserDto {
  return {
    createdAt: '2024-01-01T00:00:00Z',
    displayName: 'Test User',
    email: 'test@test.com',
    id: 'user-1',
    ...overrides,
  }
}
