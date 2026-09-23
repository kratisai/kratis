// API types matching backend DTOs

export interface AddTeamMemberRequest {
  email: string
  role: 'MEMBER' | 'OWNER'
}

export interface ApiError {
  errors?: Record<string, string>
  message: string
}

export interface ArchitecturePatternDto {
  description: string
  name: string
}

export interface AuthTokensResponse {
  accessToken: string
  expiresIn: number
  refreshToken: string
  user: UserDto
}

export interface CreateModelProviderRequest {
  apiKey?: string
  baseUrl?: string
  displayName: string
  models?: ModelEntryDto[]
  providerType: ProviderType
}

export interface CreateTeamRequest {
  description?: string
  name: string
}

export interface DimensionStatDto {
  category: string
  fileCount: number
  name: string
  synopsis: null | string
  topFiles: string[]
}

export interface GitHubAppInfoDto {
  appId: string
  appName: string
  enabled: boolean
  installationUrl: string
}

export interface GitHubInstallationInfoDto {
  accountLogin: string
  installationId: string
}

export interface IngestionBatchStatsDto {
  architecturePatterns: ArchitecturePatternDto[]
  batchId: string
  completionTokens: null | number
  dimensions: DimensionStatDto[]
  edgeTypeCounts: Record<string, number>
  nodeTypeCounts: Record<string, number>
  promptTokens: null | number
  totalDurationSeconds: null | number
  totalEdges: number
  totalNodes: number
  totalSpend: null | number
  totalTokens: null | number
  totalToolCalls: null | number
  usageLastUpdatedAt: null | string
  wikiPageSlugs: string[]
}

export type IngestionStatus = 'FAILED' | 'PROCESSING' | 'QUEUED' | 'SUCCESS'

export interface InstallationInfoDto {
  installId: string
  version?: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface ModelEntryDto {
  contextWindowTokens?: number
  kind: ModelKind
  modelName: string
}

export type ModelKind = 'CHAT' | 'EMBEDDING'

export interface ModelProviderDto {
  baseUrl?: string
  createdAt: string
  displayName: string
  id: string
  isActive: boolean
  models?: ModelEntryDto[]
  providerType: ProviderType
  teamId: string
  updatedAt: string
}

export type ProviderType =
  | 'ANTHROPIC'
  | 'AZURE_OPENAI'
  | 'BEDROCK'
  | 'DEEPSEEK'
  | 'GOOGLE'
  | 'GROQ'
  | 'MISTRAL'
  | 'OLLAMA'
  | 'OPENAI'
  | 'OTHER'

export interface RefreshTokenRequest {
  refreshToken: string
}

export interface RegisterUserRequest {
  displayName: string
  email: string
  password: string
}

export interface RemoteRepositoryDto {
  cloneUrl: string
  defaultBranch: string
  name: string
  sshUrl: string
}

export interface RepoCredentialDto {
  createdAt: string
  id: string
  name: string
  providerMetadata?: string
  publicKey?: string
  type: string
}

export interface RepositoryDto {
  branch: string
  commitHash?: null | string
  createdAt: string
  credentialId?: string
  id: string
  ingestionStatus?: IngestionStatus | null
  lastIngestedAt?: null | string
  latestBatchId?: null | string
  name: string
  queuePosition?: null | number
  repositoryType: string
  teamId: string
  updatedAt: string
  url: string
}

export interface SaveRepoCredentialRequest {
  name: string
  providerMetadata?: string
  publicKey?: string
  secret?: string
  type: string
}

export interface SupportedProviderType {
  defaultBaseUrl?: string
  description: string
  displayName: string
  requiresApiKey: boolean
  requiresBaseUrl: boolean
  type: string
}

export interface TeamDetailDto {
  createdAt: string
  description?: string
  embeddingModel?: string
  embeddingProvider?: string
  id: string
  ingestionModel?: string
  ingestionProvider?: string
  isDefault: boolean
  members: TeamMemberDto[]
  name: string
  role: string
  updatedAt: string
}

export interface TeamDto {
  createdAt: string
  description?: string
  embeddingModel?: string
  embeddingProvider?: string
  id: string
  ingestionModel?: string
  ingestionProvider?: string
  isDefault: boolean
  name: string
  role: string
  tavilyApiKeyConfigured: boolean
}

export interface TeamMemberDto {
  displayName: string
  email: string
  id: string
  role: string
  userId: string
}

export interface UpdateModelProviderRequest {
  apiKey?: string
  baseUrl?: string
  displayName?: string
  isActive?: boolean
  models?: ModelEntryDto[]
  providerType: ProviderType
}

export interface UpdateTeamRequest {
  description?: string
  embeddingModel?: string
  embeddingProvider?: string
  ingestionModel?: string
  ingestionProvider?: string
  name?: string
}

export interface UpdateUserRequest {
  displayName?: string
  email?: string
}

export interface UserDto {
  createdAt: string
  displayName: string
  email: string
  id: string
}
