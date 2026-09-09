import { createRootRoute, createRoute, createRouter, redirect } from '@tanstack/react-router'

import { MainLayout } from '@/components/layout/main-layout'
import { AskKratisView } from '@/components/views/ask-kratis-view'
import { CanvasView } from '@/components/views/canvas-view'
import { ChatView } from '@/components/views/chat-view'
import { ReposView } from '@/components/views/repos-view'
import { RepositoryDrilldownView } from '@/components/views/repository-drilldown-view'
import { SettingsView } from '@/components/views/settings-view'
import { UsageView } from '@/components/views/usage-view'
import { WikiView } from '@/components/views/wiki-view'

const rootRoute = createRootRoute({
  component: MainLayout,
})

const indexRoute = createRoute({
  beforeLoad: () => {
    throw redirect({ to: '/ask' })
  },
  getParentRoute: () => rootRoute,
  path: '/',
})

const askRoute = createRoute({
  component: AskKratisView,
  getParentRoute: () => rootRoute,
  path: '/ask',
})

const usageRoute = createRoute({
  component: UsageView,
  getParentRoute: () => rootRoute,
  path: '/usage',
})

const reposRoute = createRoute({
  component: ReposView,
  getParentRoute: () => rootRoute,
  path: '/repos',
})

const repoDrilldownRoute = createRoute({
  component: RepositoryDrilldownView,
  getParentRoute: () => rootRoute,
  path: '/repos/$id',
})

const chatRoute = createRoute({
  component: ChatView,
  getParentRoute: () => rootRoute,
  path: '/chats/$id',
})

const chatIndexRoute = createRoute({
  component: () => null,
  getParentRoute: () => chatRoute,
  path: '/',
})

const chatCanvasRoute = createRoute({
  component: CanvasView,
  getParentRoute: () => chatRoute,
  path: '/canvas',
})

const chatCanvasDocRoute = createRoute({
  component: CanvasView,
  getParentRoute: () => chatRoute,
  path: '/canvas/$docId',
})

const chatExecutionRoute = createRoute({
  component: () => null,
  getParentRoute: () => chatRoute,
  path: '/executions/$executionId',
})

const wikiRoute = createRoute({
  component: WikiView,
  getParentRoute: () => rootRoute,
  path: '/wiki/$repoId',
})

const wikiPageRoute = createRoute({
  component: WikiView,
  getParentRoute: () => rootRoute,
  path: '/wiki/$repoId/$pageSlug',
})

const settingsRoute = createRoute({
  component: SettingsView,
  getParentRoute: () => rootRoute,
  path: '/settings',
})

const chatTree = chatRoute.addChildren([
  chatIndexRoute,
  chatCanvasRoute,
  chatCanvasDocRoute,
  chatExecutionRoute,
])

export const routeTree = rootRoute.addChildren([
  indexRoute,
  askRoute,
  usageRoute,
  reposRoute,
  repoDrilldownRoute,
  chatTree,
  wikiRoute,
  wikiPageRoute,
  settingsRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
