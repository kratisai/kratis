import { LogOut, Menu, Monitor, Moon, Sun } from 'lucide-react'
import { useTheme } from 'next-themes'

import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useTeams } from '@/hooks/use-teams'
import { cn } from '@/lib/utils'
import { useAuthStore } from '@/store/auth-store'
import { useUIStore } from '@/store/ui-store'

import { MobileTopicSelector } from './mobile-topic-selector'

interface HeaderProps {
  onMobileMenuClick?: () => void
}

export function Header({ onMobileMenuClick }: HeaderProps) {
  const { setTheme, theme } = useTheme()
  const { currentTeamId, logout, setCurrentTeamId, user } = useAuthStore()
  const { data: teams } = useTeams()
  const { toggleSidebar } = useUIStore()

  // Derive current team from query data + currentTeamId
  const currentTeam = teams?.find((t) => t.id === currentTeamId) ?? null

  const initials =
    user?.name
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase() || 'U'

  return (
    <header className="bg-background border-border sticky top-0 z-40 flex h-14 items-center justify-between border-b px-4 md:static">
      <div className="flex min-w-0 items-center gap-2">
        <Button className="md:hidden" onClick={onMobileMenuClick} size="icon" variant="ghost">
          <Menu className="h-5 w-5" />
        </Button>
        <Button className="hidden md:flex" onClick={toggleSidebar} size="icon" variant="ghost">
          <Menu className="h-5 w-5" />
        </Button>
        <MobileTopicSelector />
      </div>

      <div className="flex shrink-0 items-center gap-2">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              className="group hover:bg-accent hover:text-accent-foreground h-9 px-1.5 sm:px-2"
              variant="ghost"
            >
              <div className="flex items-center gap-2">
                <span className="hidden max-w-[120px] truncate text-sm font-medium sm:inline sm:max-w-[200px]">
                  {currentTeam?.name || 'Select Team'}
                </span>
                <Avatar className="h-7 w-7">
                  <AvatarImage src={user?.avatar} />
                  <AvatarFallback className="bg-primary text-primary-foreground group-hover:bg-muted group-hover:text-primary text-[10px]">
                    {initials}
                  </AvatarFallback>
                </Avatar>
              </div>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-64" sideOffset={8}>
            <DropdownMenuLabel className="font-normal">
              <div className="flex flex-col space-y-1">
                <p className="text-sm leading-none font-medium">{user?.name}</p>
                <p className="text-muted-foreground text-xs leading-none">{user?.email}</p>
              </div>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />

            <DropdownMenuLabel className="text-muted-foreground text-xs font-semibold tracking-wider uppercase">
              Teams
            </DropdownMenuLabel>
            <DropdownMenuRadioGroup
              onValueChange={(id) => {
                setCurrentTeamId(id)
              }}
              value={currentTeamId || ''}
            >
              {teams?.map((team) => (
                <DropdownMenuRadioItem className="cursor-pointer" key={team.id} value={team.id}>
                  <span className="truncate">{team.name}</span>
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>

            <DropdownMenuSeparator />

            <div className="p-2">
              <div className="bg-muted flex rounded-md p-1">
                <button
                  className={cn(
                    'flex flex-1 items-center justify-center rounded-sm py-1.5 text-xs font-medium transition-all',
                    theme === 'light'
                      ? 'bg-background text-foreground shadow-sm'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                  onClick={() => setTheme('light')}
                >
                  <Sun className="mr-1.5 h-3.5 w-3.5" />
                  Light
                </button>
                <button
                  className={cn(
                    'flex flex-1 items-center justify-center rounded-sm py-1.5 text-xs font-medium transition-all',
                    theme === 'dark'
                      ? 'bg-background text-foreground shadow-sm'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                  onClick={() => setTheme('dark')}
                >
                  <Moon className="mr-1.5 h-3.5 w-3.5" />
                  Dark
                </button>
                <button
                  className={cn(
                    'flex flex-1 items-center justify-center rounded-sm py-1.5 text-xs font-medium transition-all',
                    theme === 'system'
                      ? 'bg-background text-foreground shadow-sm'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                  onClick={() => setTheme('system')}
                >
                  <Monitor className="mr-1.5 h-3.5 w-3.5" />
                  System
                </button>
              </div>
            </div>

            <DropdownMenuSeparator />

            <DropdownMenuItem
              className="text-destructive focus:bg-destructive/10 focus:text-destructive cursor-pointer"
              onClick={logout}
            >
              <LogOut className="mr-2 h-4 w-4" />
              <span>Sign Out</span>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  )
}
