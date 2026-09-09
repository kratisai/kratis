import { useEffect, useState } from 'react'

import type { AgentHarness } from '@/lib/execution-api'

import { ModelSelectorDropdown } from '@/components/chat/model-selector-dropdown'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useCredentials } from '@/hooks/use-credentials'
import { useEnvironments } from '@/hooks/use-environments'
import { useHarnesses } from '@/hooks/use-harnesses'
import { useProviders } from '@/hooks/use-providers'
import { useCanvasStore } from '@/store/canvas-store'
import { useChatStore } from '@/store/chat-store'
import { useUIStore } from '@/store/ui-store'

export interface LaunchTarget {
  credentialId?: string
  environmentId?: string
  harness: AgentHarness
  modelName: string
  modelProviderId: string
  name: string
  providerId?: string
}

interface ExecutionLaunchDialogProps {
  docId: string
  onLaunch: (target: LaunchTarget) => void
  onOpenChange: (open: boolean) => void
  open: boolean
}

export function ExecutionLaunchDialog({
  docId,
  onLaunch,
  onOpenChange,
  open,
}: ExecutionLaunchDialogProps) {
  const { data: providers } = useProviders()
  const { data: environments } = useEnvironments()
  const { data: harnesses } = useHarnesses()
  const { data: credentials } = useCredentials()

  const defaultModelName = useUIStore((state) => state.selectedModelName)
  const defaultProviderId = useUIStore((state) => state.selectedProviderId)

  const currentChatId = useChatStore((state) => state.currentChatId)
  const activeDoc = useCanvasStore((state) =>
    currentChatId
      ? (state.canvases[currentChatId] ?? []).find((doc) => doc.documentId === docId)
      : undefined,
  )
  const requiresCredential = activeDoc?.isNewRepo === true

  const [selectedTargetKey, setSelectedTargetKey] = useState('')
  const [selectedHarness, setSelectedHarness] = useState<AgentHarness>('')
  const [selectedCredentialId, setSelectedCredentialId] = useState('')
  const [selectedModelProviderId, setSelectedModelProviderId] = useState<null | string>(
    defaultProviderId,
  )
  const [selectedModelName, setSelectedModelName] = useState<null | string>(defaultModelName)

  useEffect(() => {
    if (open) {
      setSelectedModelProviderId(defaultProviderId)
      setSelectedModelName(defaultModelName)
    }
  }, [open, defaultProviderId, defaultModelName])

  const workspaceConnectors = (environments || []).filter(
    (env) => env.type === 'CONNECTOR' && env.status === 'CONNECTED',
  )

  // Build target options: providers first, then workspace connectors
  type TargetOption = Omit<
    LaunchTarget,
    'credentialId' | 'harness' | 'modelName' | 'modelProviderId'
  >
  const targetOptions: Array<{ key: string; label: string; target: TargetOption }> = []

  if (providers) {
    for (const prov of providers) {
      targetOptions.push({
        key: `provider:${prov.id}`,
        label: `Docker (${prov.name})`,
        target: {
          name: `Spawn new Docker Container (${prov.name})`,
          providerId: prov.id,
        },
      })
    }
  }

  for (const connector of workspaceConnectors) {
    targetOptions.push({
      key: `env:${connector.id}`,
      label: `Connector: ${connector.name}`,
      target: {
        environmentId: connector.id,
        name: `Workspace Connector: ${connector.name}`,
      },
    })
  }

  const canLaunch =
    selectedTargetKey !== '' &&
    selectedHarness !== '' &&
    Boolean(selectedModelProviderId && selectedModelName) &&
    (!requiresCredential || selectedCredentialId !== '')

  const handleLaunch = () => {
    const option = targetOptions.find((o) => o.key === selectedTargetKey)
    if (!option || !selectedHarness) return
    if (!selectedModelProviderId || !selectedModelName) return
    if (requiresCredential && selectedCredentialId === '') return

    onLaunch({
      ...option.target,
      credentialId: requiresCredential ? selectedCredentialId : undefined,
      harness: selectedHarness,
      modelName: selectedModelName,
      modelProviderId: selectedModelProviderId,
    })

    // Reset state
    setSelectedTargetKey('')
    setSelectedHarness('')
    setSelectedCredentialId('')
    setSelectedModelProviderId(defaultProviderId)
    setSelectedModelName(defaultModelName)
  }

  return (
    <Dialog onOpenChange={onOpenChange} open={open}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Launch Execution</DialogTitle>
          <DialogDescription>Select where and how to run this task.</DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">Where should this task run?</label>
            <Select onValueChange={setSelectedTargetKey} value={selectedTargetKey}>
              <SelectTrigger>
                <SelectValue placeholder="Select execution target" />
              </SelectTrigger>
              <SelectContent>
                {targetOptions.length > 0 ? (
                  targetOptions.map((option) => (
                    <SelectItem key={option.key} value={option.key}>
                      {option.label}
                    </SelectItem>
                  ))
                ) : (
                  <SelectItem disabled value="none">
                    No targets available
                  </SelectItem>
                )}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">Which agent harness?</label>
            <Select onValueChange={(v) => setSelectedHarness(v)} value={selectedHarness}>
              <SelectTrigger>
                <SelectValue placeholder="Select harness" />
              </SelectTrigger>
              <SelectContent>
                {harnesses && harnesses.length > 0 ? (
                  harnesses.map((h) => (
                    <SelectItem key={h.value} value={h.value}>
                      {h.name}
                    </SelectItem>
                  ))
                ) : (
                  <SelectItem disabled value="none">
                    No harnesses available
                  </SelectItem>
                )}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">Which model?</label>
            <ModelSelectorDropdown
              modelName={selectedModelName}
              onSelectModel={(provId, mName) => {
                setSelectedModelProviderId(provId)
                setSelectedModelName(mName)
              }}
              providerId={selectedModelProviderId}
              triggerVariant="form"
            />
          </div>

          {requiresCredential ? (
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium">
                Credential for the new repository (required)
              </label>
              <Select onValueChange={setSelectedCredentialId} value={selectedCredentialId}>
                <SelectTrigger>
                  <SelectValue placeholder="Select credential" />
                </SelectTrigger>
                <SelectContent>
                  {credentials && credentials.length > 0 ? (
                    credentials.map((cred) => (
                      <SelectItem key={cred.id} value={cred.id}>
                        {cred.name}
                      </SelectItem>
                    ))
                  ) : (
                    <SelectItem disabled value="none">
                      No credentials available
                    </SelectItem>
                  )}
                </SelectContent>
              </Select>
            </div>
          ) : null}
        </div>

        <DialogFooter>
          <Button disabled={!canLaunch} onClick={handleLaunch}>
            Launch
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
