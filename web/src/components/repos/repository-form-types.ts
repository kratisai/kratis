import { z } from 'zod'

export type AuthMode = 'github_app' | 'pat' | 'ssh_key'
export type ProviderType = 'azure' | 'bitbucket' | 'github' | 'gitlab' | 'public' | 'ssh_key'
export type WizardStep = 'auth' | 'details' | 'provider' | 'repos'

// Schema for the new credential form (used by react-hook-form in AuthStep)
export const newCredentialSchema = z
  .object({
    customUrl: z.string().default(''),
    installationId: z.string().default(''),
    name: z.string().default(''),
    organization: z.string().default(''),
    project: z.string().default(''),
    publicKey: z.string().default(''),
    secret: z.string().default(''),
    type: z.string().optional(),
    workspace: z.string().default(''),
  })
  .superRefine((data, ctx) => {
    if (data.type !== 'github_app' && (!data.name || data.name.trim() === '')) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Name is required',
        path: ['name'],
      })
    }
  })

export type NewCredentialFormData = z.infer<typeof newCredentialSchema>

// Schema for the repository details form (used by react-hook-form)
export const repositorySchema = z.object({
  branch: z.string().min(1, 'Branch is required').default('main'),
  credentialId: z.string().optional(),
  name: z.string().min(1, 'Name is required').max(255),
  url: z.string().min(1, 'URL is required').max(500),
})

export type RepositoryType = 'AZURE' | 'BITBUCKET' | 'GENERIC' | 'GITHUB' | 'GITLAB'

export const PROVIDER_TYPE_TO_REPOSITORY_TYPE: Record<ProviderType, RepositoryType> = {
  azure: 'AZURE',
  bitbucket: 'BITBUCKET',
  github: 'GITHUB',
  gitlab: 'GITLAB',
  public: 'GENERIC',
  ssh_key: 'GENERIC',
}

// Payload for creating a new credential (emitted by AuthStep)
export interface NewCredentialPayload {
  customUrl?: string
  installationId?: string
  name: string
  organization?: string
  project?: string
  publicKey?: string
  secret: string
  type: AuthMode
  workspace?: string
}

export type RepositoryFormData = z.infer<typeof repositorySchema>

// Payload submitted to the API. repositoryType is always provided by the wizard
// (derived from the selected provider); submitting without it is a programmer error.
// ingestImmediately is a client-side preference: when true the caller triggers ingestion
// right after the created repository commits, instead of leaving it as a manual step.
export type RepositorySubmitPayload = z.infer<typeof repositorySchema> & {
  ingestImmediately: boolean
  repositoryType: RepositoryType
}

// Minimal wizard state - only what crosses component boundaries
export interface RepositoryWizardState {
  currentStep: WizardStep
  ingestImmediately: boolean
  selectedCredentialId: string
  selectedProvider: ProviderType
}
