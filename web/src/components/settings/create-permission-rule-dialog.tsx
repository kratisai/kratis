import { AlertCircle } from 'lucide-react'
import { useState } from 'react'

import type {
  CreateSandboxPermissionRuleRequest,
  SandboxPermissionAction,
  SandboxPermissionRuleType,
} from '@/types/permission-types'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

interface CreatePermissionRuleDialogProps {
  isLoading?: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (data: CreateSandboxPermissionRuleRequest) => void
  open: boolean
}

export function CreatePermissionRuleDialog({
  isLoading = false,
  onOpenChange,
  onSubmit,
  open,
}: CreatePermissionRuleDialogProps) {
  const [commandRoot, setCommandRoot] = useState('')
  const [ruleType, setRuleType] = useState<SandboxPermissionRuleType>('EXACT')
  const [action, setAction] = useState<SandboxPermissionAction>('ALLOW')
  const [error, setError] = useState<null | string>(null)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = commandRoot.trim()
    if (!trimmed) {
      setError('Command pattern is required')
      return
    }
    setError(null)
    onSubmit({
      action,
      commandRoot: trimmed,
      ruleType,
    })
    setCommandRoot('')
    setRuleType('EXACT')
    setAction('ALLOW')
  }

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setError(null)
      setCommandRoot('')
    }
    onOpenChange(nextOpen)
  }

  return (
    <Dialog onOpenChange={handleOpenChange} open={open}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>Add Permission Rule</DialogTitle>
          <DialogDescription>
            Configure pre-approved or blocked tool execution commands for your team.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit}>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="action">Action</Label>
              <Select
                onValueChange={(val) => setAction(val as SandboxPermissionAction)}
                value={action}
              >
                <SelectTrigger id="action">
                  <SelectValue placeholder="Select action" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALLOW">Allow (Auto-approve execution)</SelectItem>
                  <SelectItem value="DENY">Deny (Block execution immediately)</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label htmlFor="rule-type">Match Type</Label>
              <Select
                onValueChange={(val) => setRuleType(val as SandboxPermissionRuleType)}
                value={ruleType}
              >
                <SelectTrigger id="rule-type">
                  <SelectValue placeholder="Select match type" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="EXACT">Exact Match</SelectItem>
                  <SelectItem value="PREFIX_WILD">Prefix Wildcard</SelectItem>
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-2">
              <Label htmlFor="command-root">Command Pattern</Label>
              <Input
                id="command-root"
                onChange={(e) => {
                  setCommandRoot(e.target.value)
                  if (error) setError(null)
                }}
                placeholder={
                  ruleType === 'PREFIX_WILD' ? 'git * or npm run' : 'git status or npm test'
                }
                value={commandRoot}
              />
              {error && <p className="text-destructive text-xs">{error}</p>}
            </div>

            <div className="bg-muted/50 text-muted-foreground space-y-1 rounded-md border p-3 text-xs">
              <div className="text-foreground flex items-center gap-1.5 font-medium">
                <AlertCircle className="h-3.5 w-3.5" />
                Precedence Rule
              </div>
              <p>
                DENY rules take immediate precedence over ALLOW rules. If any DENY rule matches,
                execution is blocked without prompting. Unmatched commands trigger a human approval
                prompt.
              </p>
            </div>
          </div>

          <DialogFooter>
            <Button onClick={() => handleOpenChange(false)} type="button" variant="outline">
              Cancel
            </Button>
            <Button disabled={isLoading} type="submit">
              {isLoading ? 'Adding...' : 'Add Rule'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
