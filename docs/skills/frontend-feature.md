---
name: frontend-feature
description: "Builds or modifies frontend features in the Kratis web app. Use when creating views, wiring UI to REST APIs, writing TanStack Query hooks, building form dialogs with validation, or adding frontend tests. Covers the full vertical slice: API client, hooks, view, sub-components, tests."
---

## Tech Stack
React 19 + TypeScript, Vite, Tailwind CSS v4, shadcn/ui (`web/src/components/ui/`), Zustand (UI state only), TanStack Query (server state), lucide-react, Vitest.

## Conventions
### UI Components
Import from `@/components/ui/<component>`. Add new components via `cd web && npx shadcn@latest add <name>`. 
Never modify existing ui/ files unless project-specific customization is required.

### Forms
Use `react-hook-form` + `zod` + `@hookform/resolvers/zod`. Define schema with `z.object()`, 
infer types, pass to `useForm({ resolver: zodResolver(schema) })`.

### Server State
Use TanStack Query (`useQuery`, `useMutation`, `useQueryClient`) for ALL server data. Never store API 
data in Zustand — reserve Zustand for UI state only (sidebar, view, theme, currentTeamId reference). 
Invalidate queries on mutation success.

**CRITICAL: Single Source of Truth for Server State**
- TanStack Query is the ONLY source of truth for server data (teams, repositories, user profiles)
- Zustand stores ONLY references (e.g., `currentTeamId: string | null`), never full objects from the API
- After login, call `queryClient.invalidateQueries({ queryKey: ['teams'] })` to trigger a refetch — 
  NEVER call API directly in mutation `onSuccess` and store results in Zustand
- Components derive current team from: `teams.find(t => t.id === currentTeamId)` where `teams` comes 
  from `useTeams()` and `currentTeamId` comes from `useAuthStore()`
- This pattern ensures TanStack Query cache is always populated and shared across all components

### Styling
Tailwind utilities only. Use `cn()` from `@/lib/utils` for conditional classes. Use CSS variable 
tokens (`bg-background`, `text-muted-foreground`, `border-border`). Mobile-first responsive prefixes (`sm:`, `md:`).

### Layout
Use semantic HTML (`<main>`, `<nav>`, `<header>`). All interactive elements need accessible names. 
Ensure color contrast meets WCAG standards.

### Views
Create `web/src/components/views/<view-name>.tsx`. Use `h-full overflow-auto p-6` wrapper, `mx-auto max-w-4xl space-y-6` 
content container. Register in `ui-store.ts` View type, `sidebar-nav.tsx` nav items, and `main-layout.tsx` renderView switch.

### Tests

Automated tests are mandatory — always add and update them. Place tests in `web/tests/`
(never beside `src/`), mirroring `src/` under `tests/`, then run `cd web && npm run test`.
See [`web-frontend-testing.md`](web-frontend-testing.md) for the full testing hierarchy,
support helpers, integration-test template, and anti-patterns.

## Patterns
### Component Extraction
Extract sub-components when a view exceeds ~100 lines or contains dialogs, forms, or repeated card/list patterns. 
Place in `web/src/components/<feature-name>/` (e.g., `web/src/components/repos/repository-card.tsx`). One component 
per file, kebab-case filename, PascalCase export. If a child needs a type the parent also uses, define it once in the child and export it.

### Form Dialog
Create the dialog in `web/src/components/<feature-name>/<form-dialog>.tsx`. Define the zod schema inside the 
dialog file, infer the type with `z.infer`, and export it if the parent's mutation needs it. The dialog 
receives `onSubmit: (data: FormData) => void` as a prop — the parent handles the mutation.

### TanStack Query Hooks
Group feature hooks in `web/src/hooks/use-<feature>.ts`. Export functions in alphabetical order, helpers at the 
bottom. Define `const FEATURE_QUERY_KEY = '<feature>'` once at the top. For mutations needing extra fields beyond 
the API DTO, create a named interface (e.g., `interface UpdateXxxVariables extends UpdateXxxRequest { id: string }`). 
Never use inline intersection types (`&`) in mutation functions.

### API Client
Create `web/src/lib/<feature>-api.ts` with named async functions matching backend endpoints, request/response 
types as interfaces, and a shared `fetchWithAuth` helper that reads tokens from the auth store.
