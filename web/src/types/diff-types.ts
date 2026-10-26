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

export interface PublishCapabilities {
  canCreateRepository: boolean
  changeSet?: ChangeSetSummaryDto
  commitCount?: number
  defaultBaseBranch: string
  digest?: string
  hasUncommittedChanges?: boolean
  incomingCommits?: number
  newRepo: boolean
  previousPrMerged?: boolean
  publishedBranch?: string
  publishedPrs?: PublishedPr[]
  repositoryType: string
  stats: PublishStats
  status?: PublishStatus
  suggestedBody: string
  suggestedNewBranch?: string
  suggestedRepositoryName?: string
  suggestedTitle: string
  supportsPullRequests: boolean
  target?: TargetDto
  visibilityOptions: RepositoryVisibility[]
}

export interface PublishPrRequest {
  baseBranch?: string
  body?: string
  branchName: string
  createNewPr?: boolean
  credentialId?: string
  digest?: string
  draft?: boolean
  newBranchName?: string
  repositoryName?: string
  squash?: boolean
  title: string
  visibility?: RepositoryVisibility
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

export interface SteerExecutionRequest {
  comments?: SteerCommentDto[]
  prompt?: string
}

interface ChangeSetFileDto {
  additions: number
  deletions: number
  path: string
  status: string
}

interface ChangeSetSummaryDto {
  additions: number
  deletions: number
  digest: string
  files: ChangeSetFileDto[]
}

type GitDiffStatus = 'ADDED' | 'DELETED' | 'MODIFIED' | 'RENAMED'

interface PublishedPr {
  baseBranch: string
  createdAt?: string
  headBranch: string
  prNumber: number
  prUrl: string
  status: PublishedPrStatus
}

type PublishedPrStatus = 'CLOSED' | 'MERGED' | 'OPEN'

interface PublishStats {
  additions: number
  commitsAhead: number
  deletions: number
  formattedSummary: string
  hasChanges: boolean
  stagedFiles: number
  unstagedFiles: number
}

type PublishStatus = 'REBASE_REQUIRED' | 'UP_TO_DATE'

interface SteerCommentDto {
  codeSnippet?: string
  comment: string
  line?: number
  path: string
}

interface TargetDto {
  branchName?: string
  kind: TargetKind
  prNumber?: number
}

type TargetKind = 'OPEN_PR' | 'OPEN_PR_NEW_BRANCH' | 'UPDATE_PR'
