import { Loader2, Zap } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useLogin, useRegister } from '@/hooks/use-auth'

interface AuthDialogProps {
  onOpenChange?: (open: boolean) => void
  open: boolean
}

export function AuthDialog({ onOpenChange, open }: AuthDialogProps) {
  const [tab, setTab] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')

  const loginMutation = useLogin()
  const registerMutation = useRegister()

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault()
    loginMutation.mutate(
      { email, password },
      {
        onError: (error) => {
          toast.error(error.message || 'Invalid credentials')
        },
        onSuccess: () => {
          onOpenChange?.(false)
          toast.success('Signed in successfully')
        },
      },
    )
  }

  const handleRegister = (e: React.FormEvent) => {
    e.preventDefault()
    registerMutation.mutate(
      { displayName: name, email, password },
      {
        onError: (error) => {
          toast.error(error.message || 'Registration failed')
        },
        onSuccess: () => {
          toast.success('Account created! Please sign in.')
          setTab('login')
          setPassword('')
        },
      },
    )
  }

  const isLoading = loginMutation.isPending || registerMutation.isPending

  return (
    <Dialog onOpenChange={onOpenChange} open={open}>
      <DialogContent className="sm:max-w-md" showCloseButton={false}>
        <DialogHeader className="space-y-3">
          <div className="flex items-center gap-2">
            <div className="bg-primary flex h-8 w-8 items-center justify-center rounded-lg">
              <Zap className="text-primary-foreground h-4 w-4" />
            </div>
            <DialogTitle className="text-xl font-semibold">Kratis</DialogTitle>
          </div>
          <DialogDescription>
            Async development platform for orchestrating AI agents
          </DialogDescription>
        </DialogHeader>

        <Tabs onValueChange={(v) => setTab(v as 'login' | 'register')} value={tab}>
          <TabsList className="grid w-full grid-cols-2">
            <TabsTrigger value="login">Sign In</TabsTrigger>
            <TabsTrigger value="register">Register</TabsTrigger>
          </TabsList>

          <TabsContent className="mt-4" value="login">
            <form className="space-y-4" onSubmit={handleLogin}>
              <div className="space-y-2">
                <Label htmlFor="email">Email</Label>
                <Input
                  disabled={isLoading}
                  id="email"
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@example.com"
                  required
                  type="email"
                  value={email}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="password">Password</Label>
                <Input
                  disabled={isLoading}
                  id="password"
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  type="password"
                  value={password}
                />
              </div>
              <Button className="w-full" disabled={isLoading} type="submit">
                {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                Sign In
              </Button>
            </form>
          </TabsContent>

          <TabsContent className="mt-4" value="register">
            <form className="space-y-4" onSubmit={handleRegister}>
              <div className="space-y-2">
                <Label htmlFor="reg-name">Name</Label>
                <Input
                  disabled={isLoading}
                  id="reg-name"
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Your name"
                  required
                  value={name}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="reg-email">Email</Label>
                <Input
                  disabled={isLoading}
                  id="reg-email"
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="you@example.com"
                  required
                  type="email"
                  value={email}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="reg-password">Password</Label>
                <Input
                  disabled={isLoading}
                  id="reg-password"
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  type="password"
                  value={password}
                />
              </div>
              <Button className="w-full" disabled={isLoading} type="submit">
                {isLoading && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
                Create Account
              </Button>
            </form>
          </TabsContent>
        </Tabs>
      </DialogContent>
    </Dialog>
  )
}
