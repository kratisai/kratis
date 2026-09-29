import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { Header } from '@/components/layout/header'

vi.mock('next-themes', () => ({
  useTheme: () => ({ setTheme: vi.fn(), theme: 'dark' }),
}))

vi.mock('@/hooks/use-teams', () => ({
  useTeams: () => ({ data: [] }),
}))

vi.mock('@/store/auth-store', () => {
  const state = {
    currentTeamId: null,
    logout: vi.fn(),
    setCurrentTeamId: vi.fn(),
    user: { avatar: null, email: 'test@kratis.ai', name: 'Test User' },
  }
  return {
    useAuthStore: (selector?: (s: typeof state) => unknown) => (selector ? selector(state) : state),
  }
})

vi.mock('@/store/ui-store', () => ({
  useUIStore: () => ({ toggleSidebar: vi.fn() }),
}))

vi.mock('@/components/layout/mobile-topic-selector', () => ({
  MobileTopicSelector: () => <div data-testid="mobile-topic-selector" />,
}))

describe('Header', () => {
  it('sticks to the top of the viewport on mobile so it stays visible while the document scrolls', () => {
    const { container } = render(<Header />)

    const header = container.querySelector('header')
    expect(header).not.toBeNull()
    expect(header).toHaveClass('sticky', 'top-0', 'z-40')
    expect(header).toHaveClass('md:static')
    expect(header).toHaveClass('bg-background', 'h-14')
  })
})
