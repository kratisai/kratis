import { Eye, EyeOff, KeyRound } from 'lucide-react'
import { useState } from 'react'

import type { TeamDto } from '@/types/auth-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useUpdateTavilyApiKey } from '@/hooks/use-teams'

interface TavilySettingsProps {
  isOwner: boolean
  team: TeamDto
}

export function TavilySettings({ isOwner, team }: TavilySettingsProps) {
  const updateTavilyApiKey = useUpdateTavilyApiKey()
  const [tavilyKey, setTavilyKey] = useState('')
  const [showTavilyKey, setShowTavilyKey] = useState(false)

  const handleTavilySubmit = (e: React.FormEvent) => {
    e.preventDefault()
    updateTavilyApiKey.mutate({
      tavilyApiKey: tavilyKey || null,
      teamId: team.id,
    })
    setTavilyKey('')
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center gap-2">
          <KeyRound className="h-5 w-5" />
          <CardTitle>Web Search (Tavily API)</CardTitle>
        </div>
        <CardDescription>
          Configure Tavily API key for web search capabilities in the planning agent
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={handleTavilySubmit}>
          <div className="space-y-2">
            <Label htmlFor="tavily-api-key">
              Tavily API Key
              {team.tavilyApiKeyConfigured && (
                <span className="ml-2 text-xs text-green-600">✓ Configured</span>
              )}
            </Label>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <Input
                  disabled={!isOwner}
                  id="tavily-api-key"
                  onChange={(e) => setTavilyKey(e.target.value)}
                  placeholder="tvly-..."
                  type={showTavilyKey ? 'text' : 'password'}
                  value={tavilyKey}
                />
              </div>
              {isOwner && (
                <Button
                  onClick={() => setShowTavilyKey(!showTavilyKey)}
                  type="button"
                  variant="outline"
                >
                  {showTavilyKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                </Button>
              )}
            </div>
            <p className="text-muted-foreground text-xs">
              Get your API key from{' '}
              <a
                className="hover:text-foreground underline"
                href="https://app.tavily.com"
                rel="noopener noreferrer"
                target="_blank"
              >
                app.tavily.com
              </a>
            </p>
          </div>
          {isOwner && (
            <Button disabled={updateTavilyApiKey.isPending} type="submit">
              {updateTavilyApiKey.isPending ? 'Saving...' : 'Save API Key'}
            </Button>
          )}
        </form>
      </CardContent>
    </Card>
  )
}
