import { zodResolver } from '@hookform/resolvers/zod'
import { ExternalLink, Eye, EyeOff, Loader2, ShieldAlert } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

import type { AuthMode, NewCredentialFormData, ProviderType } from './repository-form-types'

import { newCredentialSchema } from './repository-form-types'

const PAT_PERMISSION_HINTS: Partial<Record<ProviderType, string>> = {
  azure: 'The PAT needs Code: Read & write. Add Code: manage to create new repositories.',
  bitbucket:
    'The token needs Repositories: Read & write and Pull requests: Read & write. Add Repositories: Admin to create new repositories.',
  github:
    'A fine-grained PAT needs Contents: Read & write, Metadata: Read-only, and Pull requests: Read & write. Add Administration: Read & write to create new repositories.',
  gitlab:
    'The token needs the api and write_repository scopes. Creating projects in a group also needs the Developer role.',
}

interface CredentialFormProps {
  authMode: AuthMode
  githubAppEnabled: boolean
  installationUrl?: string
  isPending: boolean
  onAuthModeChange: (mode: AuthMode) => void
  onCancel: () => void
  onSubmit: (data: NewCredentialFormData) => void
  selectedProvider: ProviderType
}

export function CredentialForm({
  authMode,
  githubAppEnabled,
  installationUrl,
  isPending,
  onAuthModeChange,
  onCancel,
  onSubmit,
  selectedProvider,
}: CredentialFormProps) {
  // Local UI state - showSecret is purely UI state for this component
  const [showSecret, setShowSecret] = useState(false)

  const {
    formState: { errors },
    handleSubmit: handleFormSubmit,
    register,
    setValue,
  } = useForm<NewCredentialFormData>({
    defaultValues: {
      customUrl: '',
      installationId: '',
      name: '',
      organization: '',
      project: '',
      publicKey: '',
      secret: '',
      type: authMode,
      workspace: '',
    },
    resolver: zodResolver(newCredentialSchema),
  })

  useEffect(() => {
    setValue('type', authMode)
  }, [authMode, setValue])

  const onSubmitForm = (data: NewCredentialFormData) => {
    if (authMode === 'ssh_key') {
      data.secret = ''
      data.publicKey = ''
    }
    onSubmit(data)
  }

  return (
    <form
      autoComplete="off"
      className="bg-accent/15 space-y-3.5 rounded-xl border p-4"
      onSubmit={(e) => {
        void handleFormSubmit(onSubmitForm)(e)
      }}
    >
      <div className="flex items-center justify-between border-b pb-2">
        <span className="text-sm font-semibold">
          {authMode === 'github_app'
            ? 'New GitHub App Setup'
            : authMode === 'ssh_key'
              ? 'New SSH Key Setup'
              : 'New Token Setup'}
        </span>
        <Button
          className="h-7 px-2 text-xs"
          onClick={onCancel}
          size="sm"
          type="button"
          variant="ghost"
        >
          Cancel
        </Button>
      </div>

      {selectedProvider === 'github' && githubAppEnabled && (
        <div className="grid gap-2">
          <Label>Integration Type</Label>
          <div className="grid grid-cols-2 gap-2">
            <button
              className={`rounded-lg border p-2 text-center text-xs font-medium transition-all ${
                authMode === 'github_app'
                  ? 'border-primary bg-primary/5 text-primary'
                  : 'border-border bg-card'
              }`}
              onClick={() => onAuthModeChange('github_app')}
              type="button"
            >
              GitHub App (recommended)
            </button>
            <button
              className={`rounded-lg border p-2 text-center text-xs font-medium transition-all ${
                authMode === 'pat'
                  ? 'border-primary bg-primary/5 text-primary'
                  : 'border-border bg-card'
              }`}
              onClick={() => onAuthModeChange('pat')}
              type="button"
            >
              Personal Access Token
            </button>
          </div>
        </div>
      )}

      <div className="space-y-3">
        {authMode === 'github_app' && (
          <div className="bg-card space-y-3 rounded-lg border p-3">
            <div className="text-muted-foreground flex items-start gap-2 border-b pb-2 text-[11px] leading-relaxed">
              <ShieldAlert className="text-primary h-4 w-4 shrink-0" />
              <div className="space-y-1.5">
                <p>
                  Click below to install the GitHub App, then copy the Installation ID from the
                  GitHub URL after installation.
                </p>
                {installationUrl && (
                  <a
                    className="text-primary flex items-center gap-1 font-medium hover:underline"
                    href={installationUrl}
                    rel="noopener noreferrer"
                    target="_blank"
                  >
                    Install GitHub App
                    <ExternalLink className="h-3 w-3" />
                  </a>
                )}
              </div>
            </div>
            <div className="grid gap-1.5">
              <Label className="text-[11px]" htmlFor="appInstallationId">
                Installation ID
              </Label>
              <Input
                id="appInstallationId"
                {...register('installationId')}
                placeholder="e.g. 12345678"
              />
              {errors.installationId && (
                <p className="text-destructive text-xs">{errors.installationId.message}</p>
              )}
            </div>
          </div>
        )}

        {(authMode === 'pat' || authMode === 'ssh_key') && (
          <div className="grid gap-1.5">
            <Label htmlFor="credName">Credential Name</Label>
            <Input id="credName" {...register('name')} placeholder="e.g. My Key/Token Name" />
            {errors.name && <p className="text-destructive text-xs">{errors.name.message}</p>}
          </div>
        )}

        {authMode === 'pat' && (
          <div className="space-y-3">
            <div className="grid gap-1.5">
              <div className="flex items-center justify-between">
                <Label htmlFor="credSecret">Personal/Group Access Token</Label>
                <button
                  className="text-muted-foreground hover:text-foreground flex items-center gap-1 text-xs"
                  onClick={() => setShowSecret((prev) => !prev)}
                  type="button"
                >
                  {showSecret ? <EyeOff className="h-3 w-3" /> : <Eye className="h-3 w-3" />}
                  {showSecret ? 'Hide' : 'Show'}
                </button>
              </div>
              <Input
                autoComplete="one-time-code"
                id="credSecret"
                {...register('secret')}
                placeholder="Enter Token (e.g. ghp_... or glpat-...)"
                type={showSecret ? 'text' : 'password'}
              />
              {errors.secret && <p className="text-destructive text-xs">{errors.secret.message}</p>}
              {PAT_PERMISSION_HINTS[selectedProvider] && (
                <p className="text-muted-foreground text-xs">
                  {PAT_PERMISSION_HINTS[selectedProvider]}
                </p>
              )}
            </div>
            {selectedProvider === 'gitlab' && (
              <div className="space-y-3">
                <div className="grid gap-1.5">
                  <Label className="text-xs" htmlFor="gitlabUrl">
                    Self-Hosted GitLab URL (optional)
                  </Label>
                  <Input
                    autoComplete="one-time-code"
                    id="gitlabUrl"
                    {...register('customUrl')}
                    placeholder="https://gitlab.mycompany.com"
                  />
                </div>
              </div>
            )}

            {selectedProvider === 'azure' && (
              <div className="space-y-3">
                <div className="grid gap-1.5">
                  <Label className="text-xs" htmlFor="azureOrg">
                    Azure DevOps Organization
                  </Label>
                  <Input
                    autoComplete="one-time-code"
                    id="azureOrg"
                    {...register('organization')}
                    placeholder="e.g. mycompany"
                  />
                  <Label className="text-muted-foreground text-[10px]">
                    Required to discover repositories
                  </Label>
                </div>
                <div className="grid gap-1.5">
                  <Label className="text-xs" htmlFor="azureProject">
                    Project (optional)
                  </Label>
                  <Input
                    autoComplete="one-time-code"
                    id="azureProject"
                    {...register('project')}
                    placeholder="e.g. MyProject"
                  />
                </div>
                <div className="grid gap-1.5">
                  <Label className="text-xs" htmlFor="azureBaseUrl">
                    Azure DevOps Server Base URL (optional, self-hosted)
                  </Label>
                  <Input
                    autoComplete="one-time-code"
                    id="azureBaseUrl"
                    {...register('customUrl')}
                    placeholder="https://azure.mycompany.com/tfs/DefaultCollection"
                  />
                </div>
              </div>
            )}

            {selectedProvider === 'bitbucket' && (
              <div className="space-y-3">
                <div className="grid gap-1.5">
                  <Label className="text-xs" htmlFor="bitbucketWorkspace">
                    Workspace (optional)
                  </Label>
                  <Input
                    autoComplete="one-time-code"
                    id="bitbucketWorkspace"
                    {...register('workspace')}
                    placeholder="e.g. myteam"
                  />
                  <Label className="text-muted-foreground text-[10px]">
                    Leave blank to list all accessible workspaces
                  </Label>
                </div>
              </div>
            )}
          </div>
        )}

        {authMode === 'ssh_key' && (
          <div className="text-muted-foreground bg-card space-y-1 rounded-lg border p-3 text-xs leading-relaxed">
            <p className="text-foreground font-semibold">Automatic SSH Generation</p>
            <p>
              Kratis will generate a secure 4096-bit RSA key pair on the server. The private key
              stays secure on the server, and you will be shown the public key to copy to your Git
              host.
            </p>
          </div>
        )}
      </div>

      <Button className="mt-2 w-full" disabled={isPending} type="submit">
        {isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
        Save & Authenticate
      </Button>
    </form>
  )
}
