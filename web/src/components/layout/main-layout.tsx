import { Outlet } from '@tanstack/react-router'
import { useState } from 'react'

import { Header } from './header'
import { MobileSidebar } from './mobile-sidebar'
import { Sidebar } from './sidebar'

export function MainLayout() {
  const [mobileOpen, setMobileOpen] = useState(false)

  return (
    <div className="bg-background flex min-h-dvh md:h-dvh" data-testid="main-layout">
      {/* Desktop Sidebar */}
      <div className="hidden md:flex">
        <Sidebar />
      </div>

      {/* Mobile Sidebar */}
      <MobileSidebar onClose={() => setMobileOpen(false)} open={mobileOpen} />

      {/* Main Content */}
      <div className="flex min-w-0 flex-1 flex-col overflow-visible md:overflow-hidden">
        <Header onMobileMenuClick={() => setMobileOpen(true)} />
        <main className="flex-1 overflow-visible md:overflow-hidden">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
