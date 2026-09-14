import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Loader2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'

import type { GitHubAppInfoDto, RepoCredentialDto } from '@/types/auth-types'

import { Button } from '@/components/ui/button'
import {
  useCreateCredential,
  useGenerateSshKey,
  useValidateGitHubAppInstallation,
} from '@/hooks/use-credentials'
import { copyToClipboard } from '@/lib/clipboard'
import { fetchGitHubAppInfo } from '@/lib/config-api'
import { deleteCredential, listAvailableRepos } from '@/lib/credential-api'
import { useAuthStore } from '@/store/auth-store'

import type { AuthMode, NewCredentialFormData, ProviderType } from './repository-form-types'

import { CredentialForm } from './credential-form'
import { CredentialList } from './credential-list'

interface AuthStepProps {
  // Data
  credentials: RepoCredentialDto[]
  isLoading: boolean
  // Callbacks
  onBack: () => void
  onNext: () => void
  onSelectCredential: (id: string) => void
  selectedCredentialId: string
  selectedProvider: ProviderType
}

export function AuthStep({
  credentials,
  isLoading,
  onBack,
  onNext,
  onSelectCredential,
  selectedCredentialId,
  selectedProvider,
}: AuthStepProps) {
  // Local state - auth mode toggle
  const [authMode, setAuthMode] = useState<'existing' | AuthMode>('existing')
  const [displayedSshKey, setDisplayedSshKey] = useState<null | string>(null)
  const [isValidating, setIsValidating] = useState(false)
  const queryClient = useQueryClient()
  const teamId = useAuthStore().currentTeamId

  const createCredentialMutation = useCreateCredential()
  const generateSshKeyMutation = useGenerateSshKey()
  const validateInstallationMutation = useValidateGitHubAppInstallation()

  // Fetch GitHub App config so the UI knows whether GitHub App auth is offered
  const { data: githubAppInfo, isSuccess: githubAppInfoLoaded } = useQuery<GitHubAppInfoDto>({
    enabled: selectedProvider === 'github',
    queryFn: fetchGitHubAppInfo,
    queryKey: ['githubAppInfo'],
  })
  const githubAppEnabled = githubAppInfo?.enabled ?? false

  // Track the credential ID we had when we started creating
  const creatingCredentialIdRef = useRef<null | string>(null)

  const isAddingNewCredential = authMode !== 'existing'

  const lastAutoDefaultProviderRef = useRef<null | string>(null)

  // Automatically default to the new credential form if there are no credentials and no credential is selected
  useEffect(() => {
    if (isLoading || credentials.length > 0 || selectedCredentialId) return
    if (selectedProvider === 'github' && !githubAppInfoLoaded) return
    if (lastAutoDefaultProviderRef.current === selectedProvider) return
    lastAutoDefaultProviderRef.current = selectedProvider
    setAuthMode(defaultAuthMode(selectedProvider, githubAppEnabled))
  }, [
    credentials.length,
    githubAppEnabled,
    githubAppInfoLoaded,
    isLoading,
    selectedCredentialId,
    selectedProvider,
  ])

  // Fall back to PAT if GitHub App auth was disabled after the user picked it
  useEffect(() => {
    if (
      selectedProvider === 'github' &&
      authMode === 'github_app' &&
      githubAppInfoLoaded &&
      !githubAppEnabled
    ) {
      setAuthMode('pat')
    }
  }, [authMode, githubAppEnabled, githubAppInfoLoaded, selectedProvider])

  // When selectedCredentialId changes while we're creating, switch back to list mode
  useEffect(() => {
    if (
      creatingCredentialIdRef.current !== null &&
      selectedCredentialId &&
      selectedCredentialId !== creatingCredentialIdRef.current
    ) {
      // Credential was successfully created with a new ID, switch back to list mode
      if (!displayedSshKey) {
        setAuthMode('existing')
        creatingCredentialIdRef.current = null
      }
    }
  }, [selectedCredentialId, displayedSshKey])

  const handleAuthModeChange = (mode: AuthMode) => {
    setAuthMode(mode)
  }

  const handleCredentialSubmit = async (data: NewCredentialFormData) => {
    let type: AuthMode = authMode as AuthMode
    if (selectedProvider === 'gitlab') {
      type = 'pat'
    } else if (selectedProvider === 'github') {
      type = authMode === 'github_app' ? 'github_app' : 'pat'
    }

    const providerMetadataObj: Record<string, string> = {
      provider: selectedProvider,
    }

    const installationId = data.installationId.trim()
    if (type === 'github_app' && installationId) {
      providerMetadataObj.installationId = installationId
    } else if (selectedProvider === 'gitlab') {
      if (data.customUrl.trim()) {
        providerMetadataObj.gitlabUrl = data.customUrl.trim()
      }
    } else if (selectedProvider === 'azure') {
      const org = data.organization.trim()
      if (org) {
        providerMetadataObj.azureBaseUrl = data.customUrl.trim() || `https://dev.azure.com/${org}`
      }
      if (data.project.trim()) {
        providerMetadataObj.azureProject = data.project.trim()
      }
    } else if (selectedProvider === 'bitbucket') {
      if (data.workspace.trim()) {
        providerMetadataObj.bitbucketWorkspace = data.workspace.trim()
      }
    }

    // For GitHub App, validate installation and fetch account name in one step
    let credentialName = data.name.trim()
    if (type === 'github_app' && installationId) {
      try {
        const result = await validateInstallationMutation.mutateAsync(installationId)
        credentialName = result.accountLogin
      } catch {
        // Validation failed - error is already shown by the mutation
        return
      }
    }

    // Store the current credential ID so we can detect when it changes
    creatingCredentialIdRef.current = selectedCredentialId

    // SSH Generation flow
    if (type === 'ssh_key' && !data.secret) {
      generateSshKeyMutation.mutate(credentialName, {
        onSuccess: (savedCred) => {
          onSelectCredential(savedCred.id)
          if (savedCred.publicKey) {
            setDisplayedSshKey(savedCred.publicKey)
          } else {
            onNext()
          }
        },
      })
      return
    }

    // The backend CredentialType enum is the auth method, not the provider.
    const apiType = type === 'github_app' ? 'GITHUB_APP' : type === 'ssh_key' ? 'SSH_KEY' : 'PAT'

    // Standard credential save flow
    createCredentialMutation.mutate(
      {
        name: credentialName,
        providerMetadata: JSON.stringify(providerMetadataObj),
        publicKey: data.publicKey.trim() || undefined,
        secret: data.secret.trim(),
        type: apiType,
      },
      {
        onSuccess: (savedCred) => {
          if (type === 'github_app') {
            onSelectCredential(savedCred.id)
            onNext()
            return
          }

          void (async () => {
            setIsValidating(true)
            try {
              await listAvailableRepos(teamId!, savedCred.id)
              onSelectCredential(savedCred.id)
              onNext()
            } catch (err) {
              const error = err as Error
              toast.error(
                error.message || 'Validation failed. Please verify your token and settings.',
              )
              try {
                await deleteCredential(teamId!, savedCred.id)
              } catch (delErr) {
                console.error('Failed to clean up invalid credential:', delErr)
              }
              void queryClient.invalidateQueries({
                queryKey: ['credentials', teamId],
              })
            } finally {
              setIsValidating(false)
            }
          })()
        },
      },
    )
  }

  const handleCancelForm = () => {
    setAuthMode('existing')
    creatingCredentialIdRef.current = null
    setDisplayedSshKey(null)
  }

  const handleValidateAndNext = async () => {
    if (selectedProvider === 'ssh_key') {
      const cred = credentials.find((c) => c.id === selectedCredentialId)
      if (cred?.publicKey) {
        setDisplayedSshKey(cred.publicKey)
        return
      }
    }

    if (selectedProvider !== 'public') {
      setIsValidating(true)
      try {
        await listAvailableRepos(teamId!, selectedCredentialId)
      } catch (err) {
        const error = err as Error
        toast.error(error.message || 'Validation failed. Please verify your token and settings.')
        setIsValidating(false)
        return
      }
      setIsValidating(false)
    }
    onNext()
  }

  if (displayedSshKey) {
    return (
      <div className="flex h-full min-h-0 flex-1 flex-col py-2">
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1 pb-4">
          <div className="bg-primary/5 border-primary/20 space-y-4 rounded-xl border p-5">
            <div className="space-y-2">
              <h3 className="text-foreground text-sm font-semibold">
                {authMode === 'existing'
                  ? 'SSH Public Key'
                  : 'SSH Key Pair Generated successfully!'}
              </h3>
              <p className="text-muted-foreground text-xs leading-relaxed">
                Make sure you've added this key to your repository settings (as a Deploy Key /
                Access Key with write access) on GitHub, GitLab, or Bitbucket.
              </p>
            </div>

            <div className="bg-background relative rounded-lg border p-3">
              <div className="mb-2 flex items-center justify-between gap-3">
                <span className="text-muted-foreground font-mono text-[10px] tracking-wider uppercase">
                  Public Key Content
                </span>
                <Button
                  className="h-7 gap-1 px-2.5 py-0 text-[11px]"
                  onClick={() =>
                    void copyToClipboard(displayedSshKey, 'Public key copied to clipboard')
                  }
                  size="sm"
                  variant="outline"
                >
                  Copy Key
                </Button>
              </div>
              <pre className="text-muted-foreground bg-muted/30 rounded border p-2 font-mono text-[10px] break-all whitespace-pre-wrap select-all">
                {displayedSshKey}
              </pre>
            </div>

            <div className="text-muted-foreground bg-card space-y-1 rounded-lg border p-3 text-[11px] leading-normal">
              <p className="text-foreground font-medium">Where to add this key:</p>
              <ul className="list-inside list-disc space-y-1">
                <li>
                  <strong>GitHub:</strong> Settings &gt; Deploy keys &gt; Add deploy key
                </li>
                <li>
                  <strong>GitLab:</strong> Settings &gt; Repository &gt; Deploy keys
                </li>
                <li>
                  <strong>Bitbucket:</strong> Repository settings &gt; Access keys &gt; Add key
                </li>
              </ul>
            </div>
          </div>
        </div>

        <div className="mt-auto flex shrink-0 justify-end gap-2 border-t pt-4">
          <Button
            onClick={() => {
              setDisplayedSshKey(null)
            }}
            type="button"
            variant="outline"
          >
            <ArrowLeft className="mr-2 h-4 w-4" /> Back
          </Button>
          <Button
            className="w-full sm:w-auto"
            onClick={() => {
              setDisplayedSshKey(null)
              setAuthMode('existing')
              creatingCredentialIdRef.current = null
              onNext()
            }}
            type="button"
          >
            I have added the deploy key, continue <ArrowRight className="ml-2 h-4 w-4" />
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-1 flex-col py-2">
      {!isAddingNewCredential ? (
        <div className="flex min-h-0 flex-1 flex-col space-y-4 pb-4">
          <CredentialList
            credentials={credentials}
            isLoading={isLoading}
            onAddNew={() => {
              if (selectedProvider === 'github') {
                handleAuthModeChange(githubAppEnabled ? 'github_app' : 'pat')
              } else if (selectedProvider === 'ssh_key') {
                handleAuthModeChange('ssh_key')
              } else {
                handleAuthModeChange('pat')
              }
            }}
            onSelect={onSelectCredential}
            selectedCredentialId={selectedCredentialId}
          />
        </div>
      ) : (
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto pr-1 pb-4">
          <CredentialForm
            authMode={authMode}
            githubAppEnabled={githubAppEnabled}
            installationUrl={githubAppInfo?.installationUrl}
            isPending={
              createCredentialMutation.isPending ||
              validateInstallationMutation.isPending ||
              generateSshKeyMutation.isPending ||
              isValidating
            }
            onAuthModeChange={handleAuthModeChange}
            onCancel={handleCancelForm}
            onSubmit={(data) => {
              void handleCredentialSubmit(data)
            }}
            selectedProvider={selectedProvider}
          />
        </div>
      )}

      {!isAddingNewCredential && (
        <div className="mt-auto flex shrink-0 justify-end gap-2 border-t pt-4">
          <Button onClick={onBack} type="button" variant="outline">
            <ArrowLeft className="mr-2 h-4 w-4" /> Back
          </Button>
          <Button
            disabled={!selectedCredentialId || isValidating}
            onClick={() => {
              void handleValidateAndNext()
            }}
            type="button"
          >
            {isValidating && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
            Next <ArrowRight className="ml-2 h-4 w-4" />
          </Button>
        </div>
      )}
    </div>
  )
}

function defaultAuthMode(provider: ProviderType, githubAppEnabled: boolean): AuthMode {
  if (provider === 'github') return githubAppEnabled ? 'github_app' : 'pat'
  if (provider === 'ssh_key') return 'ssh_key'
  return 'pat'
}
