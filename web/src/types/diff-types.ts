export interface DiffCommentDraft {
  codeSnippet?: string
  comment: string
  createdAt: number
  endLine?: number
  id: string
  line: number
  path: string
  side?: 'new' | 'old'
}

export interface DiffFileDto {
  additions: number
  deletions: number
  patch: string
  path: string
  totalLines: number
}

export interface DiffSummaryDto {
  baseCommit: string
  commitsAhead?: number
  files: DiffSummaryFileDto[]
  hasChanges?: boolean
  headCommit: string
  stagedFiles?: number
  totalAdditions: number
  totalDeletions: number
  unstagedFiles?: number
}

export interface DiffSummaryFileDto {
  additions: number
  deletions: number
  isCollapsedByDefault: boolean
  path: string
  status: GitDiffStatus
}

export type GitDiffStatus = 'ADDED' | 'DELETED' | 'MODIFIED' | 'RENAMED'

export interface PublishCapabilities {
  canCreateRepository: boolean
  defaultBaseBranch: string
  newRepo: boolean
  publishedBranch?: string
  publishedPrNumber?: null | number
  publishedPrUrl?: string
  repositoryType: string
  stats: PublishStats
  suggestedBody: string
  suggestedRepositoryName?: string
  suggestedTitle: string
  supportsPullRequests: boolean
  visibilityOptions: RepositoryVisibility[]
}

export interface PublishPrRequest {
  baseBranch?: string
  body?: string
  branchName: string
  credentialId?: string
  draft?: boolean
  repositoryName?: string
  squash?: boolean
  title: string
  visibility?: RepositoryVisibility
}

export interface PublishStats {
  additions: number
  commitsAhead: number
  deletions: number
  formattedSummary: string
  hasChanges: boolean
  stagedFiles: number
  unstagedFiles: number
}

export interface PullRequestResult {
  baseBranch: string
  headBranch: string
  prNumber: number
  prUrl: string
}

export interface PushBranchRequest {
  branchName: string
  commitMessage?: string
  credentialId?: string
  squash?: boolean
}

export interface PushBranchResponse {
  branchName: string
  commitSha: string
  remoteRef: string
  status: string
}

export interface ReadFileSliceDto {
  lines: string[]
  path: string
  startLine: number
}

export type RepositoryVisibility = 'INTERNAL' | 'PRIVATE' | 'PUBLIC'

export interface SteerCommentDto {
  codeSnippet?: string
  comment: string
  line?: number
  path: string
}

export interface SteerExecutionRequest {
  comments?: SteerCommentDto[]
  prompt?: string
}
