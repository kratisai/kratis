import { Cpu, Plug2, Server, Shield, User, Users } from 'lucide-react'
import { useState } from 'react'

import { CreateTeamDialog } from '@/components/settings/create-team-dialog'
import { CredentialsPanel } from '@/components/settings/credentials-panel'
import { EnquiriesBanner } from '@/components/settings/enquiries-banner'
import { EnquiriesDialog } from '@/components/settings/enquiries-dialog'
import { EnvironmentList } from '@/components/settings/environment-list'
import { HitlRulesPanel } from '@/components/settings/hitl-rules-panel'
import { ModelDefaultsSettings } from '@/components/settings/model-defaults-settings'
import { ModelProviderList } from '@/components/settings/model-provider-list'
import { ProviderList } from '@/components/settings/provider-list'
import { TavilySettings } from '@/components/settings/tavily-settings'
import { TeamGeneralSettings } from '@/components/settings/team-general-settings'
import { TeamMembers } from '@/components/settings/team-members'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useCreateTeam, useTeams, useUpdateUserProfile } from '@/hooks/use-teams'
import { useAuthStore } from '@/store/auth-store'

export function SettingsView() {
  const { currentTeamId } = useAuthStore()
  const { data: teams } = useTeams()
  const [createTeamOpen, setCreateTeamOpen] = useState(false)
  const [enquiriesOpen, setEnquiriesOpen] = useState(false)
  const createTeam = useCreateTeam()

  const handleCreateTeam = (data: { description?: string; name: string }) => {
    createTeam.mutate(data)
  }

  const currentTeam = teams?.find((t) => t.id === currentTeamId)
  const isOwner = currentTeam?.role === 'owner'

  return (
    <>
      <div className="min-h-full overflow-visible p-4 sm:p-6 md:h-full md:overflow-auto">
        <div className="mx-auto max-w-6xl space-y-6" data-testid="settings-panel">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h1 className="text-2xl font-semibold">Settings</h1>
              <p className="text-muted-foreground">Manage your account and team preferences</p>
            </div>
          </div>

          {!currentTeam ? (
            <div className="space-y-6">
              <EnquiriesBanner onEnquire={() => setEnquiriesOpen(true)} />
              <ProfileForm />
            </div>
          ) : (
            <Tabs defaultValue="account">
              <TabsList className="w-full justify-start overflow-x-auto [&>button]:shrink-0">
                <TabsTrigger value="account">
                  <User className="h-4 w-4" />
                  Account
                </TabsTrigger>
                <TabsTrigger value="team">
                  <Users className="h-4 w-4" />
                  Team
                </TabsTrigger>
                <TabsTrigger value="models">
                  <Cpu className="h-4 w-4" />
                  Models
                </TabsTrigger>
                <TabsTrigger value="integrations">
                  <Plug2 className="h-4 w-4" />
                  Integrations
                </TabsTrigger>
                <TabsTrigger value="runtime">
                  <Server className="h-4 w-4" />
                  Runtime
                </TabsTrigger>
                <TabsTrigger value="hitl-rules">
                  <Shield className="h-4 w-4" />
                  HITL rules
                </TabsTrigger>
              </TabsList>

              <div className="mt-6">
                <EnquiriesBanner onEnquire={() => setEnquiriesOpen(true)} />
              </div>

              <TabsContent className="mt-6" value="account">
                <ProfileForm />
              </TabsContent>

              <TabsContent className="mt-6 space-y-6" value="team">
                <div className="flex items-center justify-between">
                  <h2 className="text-lg font-medium">Team — {currentTeam.name}</h2>
                  <Button onClick={() => setCreateTeamOpen(true)} size="sm" variant="secondary">
                    Create Team
                  </Button>
                </div>
                <TeamGeneralSettings isOwner={isOwner} team={currentTeam} />
                <TeamMembers isOwner={isOwner} teamId={currentTeam.id} />
              </TabsContent>

              <TabsContent className="mt-6 space-y-6" value="models">
                <ModelProviderList isOwner={isOwner} team={currentTeam} />
                <ModelDefaultsSettings isOwner={isOwner} team={currentTeam} />
              </TabsContent>

              <TabsContent className="mt-6 space-y-6" value="integrations">
                <TavilySettings isOwner={isOwner} team={currentTeam} />
                <CredentialsPanel />
              </TabsContent>

              <TabsContent className="mt-6 space-y-6" value="runtime">
                <ProviderList isOwner={isOwner} />
                <EnvironmentList isOwner={isOwner} />
              </TabsContent>

              <TabsContent className="mt-6 space-y-6" value="hitl-rules">
                <HitlRulesPanel />
              </TabsContent>
            </Tabs>
          )}
        </div>
      </div>
      <CreateTeamDialog
        onOpenChange={setCreateTeamOpen}
        onSubmit={handleCreateTeam}
        open={createTeamOpen}
      />
      <EnquiriesDialog onOpenChange={setEnquiriesOpen} open={enquiriesOpen} />
    </>
  )
}

function ProfileForm() {
  const { user } = useAuthStore()
  const updateProfile = useUpdateUserProfile()
  const [displayName, setDisplayName] = useState(user?.name ?? '')
  const [email, setEmail] = useState(user?.email ?? '')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    updateProfile.mutate({
      displayName: displayName || undefined,
      email: email || undefined,
    })
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <User className="h-5 w-5" />
          <CardTitle>Profile</CardTitle>
        </div>
        <CardDescription>Your personal information</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input id="name" onChange={(e) => setDisplayName(e.target.value)} value={displayName} />
          </div>
          <div className="space-y-2">
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              onChange={(e) => setEmail(e.target.value)}
              type="email"
              value={email}
            />
          </div>
          <Button disabled={updateProfile.isPending} type="submit">
            {updateProfile.isPending ? 'Saving...' : 'Save Changes'}
          </Button>
        </form>
      </CardContent>
    </Card>
  )
}
