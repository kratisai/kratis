import { Bug, Lightbulb, ShieldAlert, Sparkles } from 'lucide-react'
import { useState } from 'react'

import { FluentSentenceInput } from '@/components/chat/fluent/fluent-sentence-input'
import { UserMessageInput } from '@/components/chat/user-message-input'
import { Card, CardContent } from '@/components/ui/card'
import { useStartChat } from '@/hooks/use-start-chat'
import { ASK_TEMPLATES, getTemplateById } from '@/lib/ask-templates'
import { cn } from '@/lib/utils'
import { useWebSocketStore } from '@/store/websocket-store'

const iconMap = {
  Bug,
  Lightbulb,
  ShieldAlert,
  Sparkles,
}

export function AskKratisView() {
  const isConnected = useWebSocketStore((state) => state.isConnected)
  const isConnecting = useWebSocketStore((state) => state.isConnecting)
  const error = useWebSocketStore((state) => state.error)
  const { isCreating, startChat } = useStartChat()
  const [activeTemplateId, setActiveTemplateId] = useState<string>('free-form')

  const activeTemplate = getTemplateById(activeTemplateId) || ASK_TEMPLATES[0]

  const handleSend = (message: string) => {
    if (!message.trim()) return
    startChat(message)
  }

  // Show connection status
  if (!isConnected && !isConnecting) {
    return (
      <div className="flex h-full flex-col items-center justify-center p-6">
        <div className="w-full max-w-2xl space-y-8 text-center">
          <div className="bg-primary/10 mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl">
            <Sparkles className="text-primary h-8 w-8" />
          </div>
          <h1 className="text-2xl font-semibold text-balance">Connecting to Kratis...</h1>
          {error && <p className="text-destructive">{error}</p>}
        </div>
      </div>
    )
  }

  // Default: show welcome screen with suggestions / templates
  return (
    <div className="flex h-full flex-col items-center justify-center p-6">
      <div className="w-full max-w-3xl space-y-8">
        <div className="text-center">
          <div className="bg-primary/10 mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl">
            <Sparkles className="text-primary h-8 w-8" />
          </div>
          <h1 className="text-2xl font-semibold text-balance">
            {isCreating ? 'Initializing chat...' : 'What can I help you build today?'}
          </h1>
          <p className="text-muted-foreground mt-2 text-sm">
            {isCreating
              ? 'Connecting your agentic workspace...'
              : 'Choose a structured workflow or orchestrate AI agents freely'}
          </p>
        </div>

        {!isCreating && (
          <>
            {activeTemplateId === 'free-form' ? (
              <UserMessageInput onSend={handleSend} placeholder="Describe your task..." />
            ) : (
              <FluentSentenceInput
                key={activeTemplate.id}
                onSend={handleSend}
                template={activeTemplate}
              />
            )}

            <div className="space-y-2">
              <span className="text-muted-foreground px-1 text-xs font-medium tracking-wider uppercase">
                Workflow Templates
              </span>
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                {ASK_TEMPLATES.map((tpl) => {
                  const IconComponent = iconMap[tpl.icon as keyof typeof iconMap]
                  const isActive = activeTemplateId === tpl.id
                  return (
                    <Card
                      className={cn(
                        'hover:border-primary/50 hover:bg-accent/40 cursor-pointer transition-all',
                        isActive
                          ? 'border-primary bg-primary/5 ring-primary/30 shadow-sm ring-1'
                          : 'border-border text-muted-foreground hover:text-foreground',
                      )}
                      key={tpl.id}
                      onClick={() => setActiveTemplateId(tpl.id)}
                    >
                      <CardContent className="flex flex-col gap-2 p-3.5">
                        <div className="flex items-center gap-2">
                          <div
                            className={cn(
                              'flex h-7 w-7 items-center justify-center rounded-md transition-colors',
                              isActive
                                ? 'bg-primary text-primary-foreground'
                                : 'bg-muted text-muted-foreground',
                            )}
                          >
                            <IconComponent className="h-4 w-4 shrink-0" />
                          </div>
                          <span
                            className={cn(
                              'text-sm leading-none font-medium',
                              isActive && 'text-foreground',
                            )}
                          >
                            {tpl.title}
                          </span>
                        </div>
                        <span className="text-muted-foreground line-clamp-2 text-xs leading-relaxed">
                          {tpl.description}
                        </span>
                      </CardContent>
                    </Card>
                  )
                })}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
