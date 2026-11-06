import { Loader2, Plus, Sparkles, Trash2 } from 'lucide-react'
import { useState } from 'react'

import type { TeamSkillDto } from '@/types/team-skill-types'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {
  useDeleteTeamSkill,
  useTeamSkills,
  useUpdateTeamSkill,
  useUploadTeamSkill,
} from '@/hooks/use-team-skills'

import { UploadTeamSkillDialog } from './upload-team-skill-dialog'

export function TeamSkillsPanel() {
  const { data: skills, isLoading } = useTeamSkills()
  const uploadSkill = useUploadTeamSkill()
  const updateSkill = useUpdateTeamSkill()
  const deleteSkill = useDeleteTeamSkill()

  const [isUploadOpen, setIsUploadOpen] = useState(false)
  const [skillToDelete, setSkillToDelete] = useState<null | TeamSkillDto>(null)

  const handleUpload = (file: File) => {
    uploadSkill.mutate(file, {
      onSuccess: () => setIsUploadOpen(false),
    })
  }

  const handleDelete = () => {
    if (!skillToDelete) return
    deleteSkill.mutate(skillToDelete.id, {
      onSuccess: () => setSkillToDelete(null),
    })
  }

  return (
    <>
      <Card>
        <CardHeader>
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div className="flex items-center gap-2">
                <Sparkles className="h-5 w-5" />
                <CardTitle>Skills</CardTitle>
              </div>
              <CardDescription className="mt-1">
                Team skills are published to every execution sandbox. Repository skills override
                team skills with the same name.
              </CardDescription>
            </div>
            <Button onClick={() => setIsUploadOpen(true)} size="sm">
              <Plus className="mr-1.5 h-4 w-4" />
              Upload Skill
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex justify-center py-8">
              <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
            </div>
          ) : !skills || skills.length === 0 ? (
            <div className="rounded-lg border border-dashed p-8 text-center">
              <Sparkles className="text-muted-foreground mx-auto mb-2 h-8 w-8 opacity-50" />
              <p className="text-sm font-medium">No team skills</p>
              <p className="text-muted-foreground mt-1 text-xs">
                Upload a skill archive to make it available to task executions.
              </p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Skill</TableHead>
                    <TableHead>When to Use</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {skills.map((skill) => (
                    <TableRow key={skill.id}>
                      <TableCell className="font-medium">{skill.name}</TableCell>
                      <TableCell className="max-w-[280px] truncate">{skill.description}</TableCell>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <Checkbox
                            aria-label={`Toggle ${skill.name}`}
                            checked={skill.enabled}
                            disabled={updateSkill.isPending}
                            onCheckedChange={(checked) =>
                              updateSkill.mutate({
                                enabled: checked === true,
                                skillId: skill.id,
                              })
                            }
                          />
                          <Badge variant={skill.enabled ? 'default' : 'secondary'}>
                            {skill.enabled ? 'Enabled' : 'Disabled'}
                          </Badge>
                        </div>
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          aria-label={`Delete skill ${skill.name}`}
                          onClick={() => setSkillToDelete(skill)}
                          size="sm"
                          variant="ghost"
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <UploadTeamSkillDialog
        isLoading={uploadSkill.isPending}
        onOpenChange={setIsUploadOpen}
        onSubmit={handleUpload}
        open={isUploadOpen}
      />

      <Dialog onOpenChange={(open) => !open && setSkillToDelete(null)} open={!!skillToDelete}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete skill</DialogTitle>
            <DialogDescription>
              Delete {skillToDelete?.name}? The archive is removed from storage and future
              executions no longer receive this skill.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button onClick={() => setSkillToDelete(null)} variant="ghost">
              Cancel
            </Button>
            <Button disabled={deleteSkill.isPending} onClick={handleDelete} variant="destructive">
              {deleteSkill.isPending ? 'Deleting...' : 'Delete'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}
