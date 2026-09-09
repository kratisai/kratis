import { Trash2, UserPlus, Users } from 'lucide-react'
import { useState } from 'react'

import type { AddTeamMemberRequest, TeamMemberDto } from '@/types/auth-types'

import { InviteMemberDialog } from '@/components/settings/invite-member-dialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { useAddTeamMember, useRemoveTeamMember, useTeam } from '@/hooks/use-teams'

interface TeamMembersProps {
  isOwner?: boolean
  teamId: string
}

const roleMap: Record<string, AddTeamMemberRequest['role']> = {
  member: 'MEMBER',
  owner: 'OWNER',
}

export function TeamMembers({ isOwner: isOwnerProp, teamId }: TeamMembersProps) {
  const { data: teamDetail, isLoading } = useTeam(teamId)
  const isOwner = isOwnerProp ?? teamDetail?.role === 'OWNER'
  const addMember = useAddTeamMember()
  const removeMember = useRemoveTeamMember()
  const [inviteOpen, setInviteOpen] = useState(false)

  const handleInvite = (data: { email: string; role: 'member' | 'owner' }) => {
    addMember.mutate({
      data: {
        email: data.email,
        role: roleMap[data.role],
      },
      teamId,
    })
  }

  const handleRemove = (userId: string) => {
    removeMember.mutate({ teamId, userId })
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Users className="h-5 w-5" />
            <CardTitle>Team Members</CardTitle>
          </div>
          {isOwner && (
            <Button onClick={() => setInviteOpen(true)} size="sm" variant="outline">
              <UserPlus className="mr-2 h-4 w-4" />
              Invite Member
            </Button>
          )}
        </div>
        <CardDescription>Manage who has access to this team</CardDescription>
      </CardHeader>
      <CardContent>
        {isLoading ? (
          <p className="text-muted-foreground text-sm">Loading members...</p>
        ) : !teamDetail?.members || teamDetail.members.length === 0 ? (
          <p className="text-muted-foreground text-sm">No members yet</p>
        ) : (
          <ul className="divide-border divide-y">
            {teamDetail.members.map((member: TeamMemberDto) => (
              <li className="flex items-center justify-between py-3" key={member.id}>
                <div className="flex flex-col">
                  <span className="text-sm font-medium">{member.displayName}</span>
                  <span className="text-muted-foreground text-xs">{member.email}</span>
                </div>
                <div className="flex items-center gap-4">
                  <span className="bg-secondary text-secondary-foreground rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase">
                    {member.role}
                  </span>
                  {isOwner && member.role !== 'owner' && (
                    <Button
                      className="text-destructive"
                      onClick={() => handleRemove(member.userId)}
                      size="icon"
                      variant="ghost"
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      <InviteMemberDialog onOpenChange={setInviteOpen} onSubmit={handleInvite} open={inviteOpen} />
    </Card>
  )
}
