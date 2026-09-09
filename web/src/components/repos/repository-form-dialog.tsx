import { zodResolver } from '@hookform/resolvers/zod'
import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useForm } from 'react-hook-form'

import type { RepositoryDto } from '@/types/auth-types'

import { WizardShell } from '@/components/wizard/wizard-shell'
import { useAvailableRepos, useCredentials } from '@/hooks/use-credentials'
import { useRepositories } from '@/hooks/use-repositories'

import type { RepositoryFormData, RepositorySubmitPayload } from './repository-form-types'

import { AuthStep } from './auth-step'
import { ProviderSelector } from './provider-selector'
import { RepositoryDetailsForm } from './repository-details-form'
import { PROVIDER_TYPE_TO_REPOSITORY_TYPE, repositorySchema } from './repository-form-types'
import { SelectNewRepoList } from './select-new-repo-list'
import { useRepositoryWizard } from './use-repository-wizard'

export function RepositoryFormDialog({
  isOpen,
  isPending,
  onClose,
  onSubmit,
  repo,
}: {
  isOpen: boolean
  isPending: boolean
  onClose: () => void
  onSubmit: (data: RepositorySubmitPayload) => void
  repo: null | RepositoryDto
}) {
  const { actions: wizardActions, state: wizardState } = useRepositoryWizard()

  const { data: credentials, isLoading: loadingCredentials } = useCredentials()
  const { data: onboardedRepos } = useRepositories()

  const supportsDynamicListing = useMemo(() => {
    return (
      wizardState.selectedProvider === 'github' ||
      wizardState.selectedProvider === 'gitlab' ||
      wizardState.selectedProvider === 'bitbucket' ||
      wizardState.selectedProvider === 'azure'
    )
  }, [wizardState.selectedProvider])

  // Fetch remote repositories dynamically from provider using standard query hook
  const {
    data: remoteRepos,
    error: remoteReposError,
    isLoading: loadingRemoteRepos,
    refetch: refetchRemoteRepos,
  } = useAvailableRepos(wizardState.selectedCredentialId || null, supportsDynamicListing)

  const {
    formState: { errors },
    handleSubmit,
    register,
    reset,
    setValue,
    watch,
  } = useForm<RepositoryFormData>({
    defaultValues: {
      branch: 'main',
      credentialId: '',
      name: '',
      url: '',
    },
    resolver: zodResolver(repositorySchema),
  })

  const watchedUrl = watch('url')
  const watchedName = watch('name')
  const lastDerivedNameRef = useRef('')

  useEffect(() => {
    if (!watchedUrl) return
    const derived = getRepoNameFromUrl(watchedUrl)
    if (derived) {
      if (!watchedName || watchedName === lastDerivedNameRef.current) {
        setValue('name', derived, { shouldValidate: true })
        lastDerivedNameRef.current = derived
      }
    }
  }, [watchedUrl, setValue, watchedName])

  // Automatically update step & selection on dialog opening
  useEffect(() => {
    if (isOpen) {
      if (repo) {
        wizardActions.goToStep('details')
        setValue('name', repo.name)
        setValue('url', repo.url)
        setValue('branch', repo.branch)
        setValue('credentialId', repo.credentialId || '')
        wizardActions.selectCredential(repo.credentialId || '')
      } else {
        wizardActions.reset()
        reset()
      }
    }
  }, [isOpen, repo, setValue, reset, wizardActions])

  const handleOpenChange = useCallback(
    (open: boolean) => {
      if (!open) {
        onClose()
      }
    },
    [onClose],
  )

  // Filters credentials matching the current provider. Credential types are auth methods
  // (GITHUB_APP/PAT/SSH_KEY), so the provider is read from the credential metadata. Legacy
  // credentials that predate the auth/provider split carry the provider as their type.
  const filteredCredentials = useMemo(
    () =>
      credentials?.filter((cred) => {
        if (wizardState.selectedProvider === 'ssh_key') {
          return cred.type.toUpperCase() === 'SSH_KEY'
        }
        const type = cred.type.toUpperCase()
        if (type === wizardState.selectedProvider.toUpperCase()) {
          return true
        }
        let metaProvider = ''
        try {
          const parsed = JSON.parse(cred.providerMetadata || '{}') as { provider?: string }
          metaProvider = parsed.provider ?? ''
        } catch {
          // malformed metadata -> no provider
        }
        return metaProvider === wizardState.selectedProvider
      }) || [],
    [credentials, wizardState.selectedProvider],
  )

  const handleBatchOnboard = useCallback(
    (selectedRepoNames: Set<string>) => {
      if (selectedRepoNames.size === 0) return

      const reposToOnboard = remoteRepos?.filter((r) => selectedRepoNames.has(r.name)) || []
      const repositoryType = PROVIDER_TYPE_TO_REPOSITORY_TYPE[wizardState.selectedProvider]
      for (const remote of reposToOnboard) {
        onSubmit({
          branch: remote.defaultBranch || 'main',
          credentialId: wizardState.selectedCredentialId || undefined,
          ingestImmediately: wizardState.ingestImmediately,
          name: remote.name,
          repositoryType,
          url: remote.cloneUrl || remote.sshUrl,
        })
      }
    },
    [
      onSubmit,
      remoteRepos,
      wizardState.ingestImmediately,
      wizardState.selectedCredentialId,
      wizardState.selectedProvider,
    ],
  )

  const handleFormSubmit = useCallback(
    (data: RepositoryFormData) => {
      const repositoryType = PROVIDER_TYPE_TO_REPOSITORY_TYPE[wizardState.selectedProvider]
      onSubmit({
        ...data,
        credentialId: wizardState.selectedCredentialId || undefined,
        ingestImmediately: wizardState.ingestImmediately,
        repositoryType,
      })
    },
    [
      onSubmit,
      wizardState.ingestImmediately,
      wizardState.selectedCredentialId,
      wizardState.selectedProvider,
    ],
  )

  // Computed: onboarded repo names for filtering
  const onboardedRepoNames = useMemo(
    () => new Set(onboardedRepos?.map((r) => r.name) || []),
    [onboardedRepos],
  )

  const getStepTitle = () => {
    switch (wizardState.currentStep) {
      case 'auth':
        if (wizardState.selectedProvider === 'ssh_key') {
          return 'Authenticate Git with SSH Key'
        }
        return `Authenticate ${wizardState.selectedProvider.toUpperCase()}`
      case 'details':
        return repo ? 'Edit Repository Settings' : 'Onboard Settings'
      case 'provider':
        return 'Choose Repository Host'
      case 'repos':
        return 'Select Remote Repositories'
    }
  }

  const getStepDescription = () => {
    switch (wizardState.currentStep) {
      case 'auth':
        return 'Select an existing credential or add a new one'
      case 'details':
        return 'Verify configuration settings and sync'
      case 'provider':
        return 'Select where your repository is hosted'
      case 'repos':
        return 'Select one or more repositories to onboard'
    }
  }

  const steps = useMemo(
    () =>
      repo
        ? [{ id: 'details', label: 'Details' }]
        : [
            { id: 'provider', label: 'Provider' },
            { id: 'auth', label: 'Authenticate' },
            { id: 'repos', label: 'Repositories' },
            { id: 'details', label: 'Details' },
          ],
    [repo],
  )

  // Get credential name for display in details form
  const credentialName =
    credentials?.find((c) => c.id === wizardState.selectedCredentialId)?.name ?? null

  return (
    <WizardShell
      currentStepId={wizardState.currentStep}
      description={getStepDescription()}
      onOpenChange={handleOpenChange}
      open={isOpen}
      steps={steps}
      title={getStepTitle()}
    >
      {wizardState.currentStep === 'provider' && (
        <ProviderSelector
          onNext={() => {
            if (wizardState.selectedProvider === 'public') {
              setValue('credentialId', '')
              wizardActions.goToStep('details')
            } else {
              wizardActions.goToStep('auth')
            }
          }}
          onSelect={wizardActions.selectProvider}
          selectedProvider={wizardState.selectedProvider}
        />
      )}

      {wizardState.currentStep === 'auth' && (
        <AuthStep
          credentials={filteredCredentials}
          isLoading={loadingCredentials}
          onBack={() => wizardActions.goToStep('provider')}
          onNext={() => {
            if (supportsDynamicListing) {
              wizardActions.goToStep('repos')
            } else {
              wizardActions.goToStep('details')
            }
          }}
          onSelectCredential={wizardActions.selectCredential}
          selectedCredentialId={wizardState.selectedCredentialId}
          selectedProvider={wizardState.selectedProvider}
        />
      )}

      {wizardState.currentStep === 'repos' && (
        <SelectNewRepoList
          error={remoteReposError}
          ingestImmediately={wizardState.ingestImmediately}
          isLoading={loadingRemoteRepos}
          onBack={() => wizardActions.goToStep('auth')}
          onBatchOnboard={handleBatchOnboard}
          onboardedRepoNames={onboardedRepoNames}
          onEnterManually={() => wizardActions.goToStep('details')}
          onIngestImmediatelyChange={wizardActions.setIngestImmediately}
          onRefetchRepos={() => {
            void refetchRemoteRepos()
          }}
          repos={remoteRepos ?? []}
          selectedProvider={wizardState.selectedProvider}
          totalRepoCount={remoteRepos?.length ?? 0}
        />
      )}

      {wizardState.currentStep === 'details' && (
        <form
          className="flex h-full min-h-0 flex-1 flex-col"
          onSubmit={(e) => {
            void handleSubmit(handleFormSubmit)(e)
          }}
        >
          <RepositoryDetailsForm
            credentialName={credentialName}
            errors={errors}
            ingestImmediately={wizardState.ingestImmediately}
            isEditing={!!repo}
            isPending={isPending}
            onBack={() => {
              if (wizardState.selectedProvider === 'public') {
                wizardActions.goToStep('provider')
              } else if (wizardState.selectedProvider === 'ssh_key') {
                wizardActions.goToStep('auth')
              } else {
                wizardActions.goToStep('repos')
              }
            }}
            onCancel={onClose}
            onIngestImmediatelyChange={wizardActions.setIngestImmediately}
            register={register}
          />
        </form>
      )}
    </WizardShell>
  )
}

function getRepoNameFromUrl(url: string): string {
  if (!url) return ''
  let cleanUrl = url.trim()
  if (cleanUrl.endsWith('.git')) {
    cleanUrl = cleanUrl.slice(0, -4)
  }
  while (cleanUrl.endsWith('/')) {
    cleanUrl = cleanUrl.slice(0, -1)
  }
  const lastSlash = cleanUrl.lastIndexOf('/')
  const lastColon = cleanUrl.lastIndexOf(':')
  const splitIdx = Math.max(lastSlash, lastColon)
  if (splitIdx !== -1) {
    return cleanUrl.substring(splitIdx + 1)
  }
  return cleanUrl
}
