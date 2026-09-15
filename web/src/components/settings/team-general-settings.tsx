import { Settings } from 'lucide-react'
import { useEffect, useState } from 'react'

import type { TeamDto } from '@/types/auth-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useUpdateTeam } from '@/hooks/use-teams'

interface TeamGeneralSettingsProps {
  isOwner: boolean
  team: TeamDto
}

export function TeamGeneralSettings({ isOwner, team }: TeamGeneralSettingsProps) {
  const updateTeam = useUpdateTeam()
  const [name, setName] = useState(team.name)
  const [description, setDescription] = useState(team.description ?? '')

  useEffect(() => {
    setName(team.name)
    setDescription(team.description ?? '')
  }, [team.description, team.id, team.name])

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    updateTeam.mutate({
      description: description || undefined,
      name,
      teamId: team.id,
    })
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <Settings className="h-5 w-5" />
          <CardTitle>General Settings</CardTitle>
        </div>
        <CardDescription>Manage team name and description</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="space-y-2">
            <Label htmlFor="team-name">Team Name</Label>
            <Input
              disabled={!isOwner}
              id="team-name"
              onChange={(e) => setName(e.target.value)}
              value={name}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="team-description">Description</Label>
            <Input
              disabled={!isOwner}
              id="team-description"
              onChange={(e) => setDescription(e.target.value)}
              value={description}
            />
          </div>
          {isOwner && (
            <Button disabled={updateTeam.isPending} type="submit">
              {updateTeam.isPending ? 'Saving...' : 'Save Changes'}
            </Button>
          )}
        </form>
      </CardContent>
    </Card>
  )
}
