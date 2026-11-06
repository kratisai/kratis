import { AlertCircle, Upload } from 'lucide-react'
import { useState } from 'react'

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

interface UploadTeamSkillDialogProps {
  isLoading?: boolean
  onOpenChange: (open: boolean) => void
  onSubmit: (file: File) => void
  open: boolean
}

export function UploadTeamSkillDialog({
  isLoading = false,
  onOpenChange,
  onSubmit,
  open,
}: UploadTeamSkillDialogProps) {
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<null | string>(null)

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!file) {
      setError('Select a .tar.gz skill archive')
      return
    }
    if (!isSkillArchive(file)) {
      setError('Skill archive must be a .tar.gz or .tgz file')
      return
    }
    setError(null)
    onSubmit(file)
    setFile(null)
  }

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) {
      setFile(null)
      setError(null)
    }
    onOpenChange(nextOpen)
  }

  return (
    <Dialog onOpenChange={handleOpenChange} open={open}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>Upload Team Skill</DialogTitle>
          <DialogDescription>
            Upload a tar.gz archive whose SKILL.md frontmatter sets name and description. The
            description tells agents when to use the skill.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit}>
          <div className="space-y-4 py-4">
            <div className="space-y-2">
              <Label htmlFor="skill-archive">Skill archive</Label>
              <Input
                accept=".tar.gz,.tgz,application/gzip"
                id="skill-archive"
                onChange={(event) => {
                  setFile(event.target.files?.[0] ?? null)
                  if (error) setError(null)
                }}
                type="file"
              />
              {file && <p className="text-muted-foreground text-xs">Selected: {file.name}</p>}
              {error && (
                <p className="text-destructive flex items-center gap-1 text-xs">
                  <AlertCircle className="h-3.5 w-3.5" />
                  {error}
                </p>
              )}
            </div>

            <div className="bg-muted/50 text-muted-foreground rounded-md border p-3 text-xs">
              Archives are validated before upload. Path traversal entries, links, and missing
              frontmatter are rejected.
            </div>
          </div>

          <DialogFooter>
            <Button onClick={() => handleOpenChange(false)} type="button" variant="ghost">
              Cancel
            </Button>
            <Button disabled={isLoading} type="submit">
              <Upload className="mr-1.5 h-4 w-4" />
              {isLoading ? 'Uploading...' : 'Upload Skill'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function isSkillArchive(file: File): boolean {
  const name = file.name.toLowerCase()
  return name.endsWith('.tar.gz') || name.endsWith('.tgz')
}
