import { Bot, Pause, Play, Plus, Settings } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

const agents = [
  {
    description: 'Automatically reviews PRs and suggests improvements',
    name: 'Code Reviewer',
    status: 'active',
    tasksCompleted: 47,
  },
  {
    description: 'Generates unit tests for new functions',
    name: 'Test Generator',
    status: 'idle',
    tasksCompleted: 23,
  },
  {
    description: 'Writes and updates documentation',
    name: 'Doc Writer',
    status: 'active',
    tasksCompleted: 89,
  },
]

export function AgentsView() {
  return (
    <div className="min-h-full overflow-visible p-6 md:h-full md:overflow-auto">
      <div className="mx-auto max-w-4xl space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold">Agents</h1>
            <p className="text-muted-foreground">Configure and monitor your AI agent runtimes</p>
          </div>
          <Button>
            <Plus className="mr-2 h-4 w-4" />
            Add Agent
          </Button>
        </div>

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {agents.map((agent) => (
            <Card key={agent.name}>
              <CardHeader className="pb-2">
                <div className="flex items-start justify-between">
                  <div className="bg-secondary flex h-10 w-10 items-center justify-center rounded-lg">
                    <Bot className="h-5 w-5" />
                  </div>
                  <div
                    className={`flex h-6 items-center rounded-full px-2 text-xs font-medium ${
                      agent.status === 'active'
                        ? 'bg-primary/10 text-primary'
                        : 'bg-muted text-muted-foreground'
                    }`}
                  >
                    <span
                      className={`mr-1.5 h-1.5 w-1.5 rounded-full ${
                        agent.status === 'active' ? 'bg-primary' : 'bg-muted-foreground'
                      }`}
                    />
                    {agent.status}
                  </div>
                </div>
                <CardTitle className="mt-3 text-base">{agent.name}</CardTitle>
                <CardDescription>{agent.description}</CardDescription>
              </CardHeader>
              <CardContent>
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground text-sm">
                    {agent.tasksCompleted} tasks completed
                  </span>
                  <div className="flex gap-1">
                    <Button className="h-8 w-8" size="icon" variant="ghost">
                      {agent.status === 'active' ? (
                        <Pause className="h-4 w-4" />
                      ) : (
                        <Play className="h-4 w-4" />
                      )}
                    </Button>
                    <Button className="h-8 w-8" size="icon" variant="ghost">
                      <Settings className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    </div>
  )
}
