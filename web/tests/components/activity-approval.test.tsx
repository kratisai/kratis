import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { CommandExecutionActivity } from '@/types/execution-activity-types'

import { ActivityApproval } from '@/components/session/activities/activity-approval'
import { useActivityStore } from '@/store/activity-store'

const resolveHitl = vi.fn()

function pendingActivity(overrides: Partial<CommandExecutionActivity> = {}): CommandExecutionActivity {
  return {
    actionId: 'tc-1',
    approvalRequired: true,
    collapsed: false,
    command: 'rm -rf node_modules',
    executionId: 'exec-1',
    id: 'act-1',
    output: [],
    startedAt: '2026-01-01T00:00:00Z',
    state: 'pending_approval',
    type: 'command_execution',
    ...overrides,
  }
}

describe('ActivityApproval', () => {
  beforeEach(() => {
    resolveHitl.mockResolvedValue(undefined)
    useActivityStore.setState({ resolveHitl })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders one button per offered option with its label', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionOptions: [
            { kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' },
            { kind: 'allow_always', name: 'Always allow', optionId: 'allow-always' },
            { kind: 'reject_once', name: 'Reject', optionId: 'reject-once' },
          ],
        })}
        executionId="exec-1"
      />,
    )

    expect(screen.getByRole('button', { name: /Allow once/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Always allow/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Reject/ })).toBeInTheDocument()
  })

  it('renders the diff preview when the permission carries a diff', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionDiff: { newText: 'const a = 1', oldText: 'const a = 2', path: 'src/a.ts' },
        })}
        executionId="exec-1"
      />,
    )

    expect(screen.getByText('src/a.ts')).toBeInTheDocument()
    expect(screen.getByText('const a = 2')).toBeInTheDocument()
    expect(screen.getByText('const a = 1')).toBeInTheDocument()
  })

  it('always shows a Cancel button even when no reject option is offered', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionOptions: [{ kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' }],
        })}
        executionId="exec-1"
      />,
    )

    expect(screen.getByRole('button', { name: /Cancel/ })).toBeInTheDocument()
  })

  it('resolves the selected allow option as approved via the store', async () => {
    const user = userEvent.setup()
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionOptions: [{ kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' }],
        })}
        executionId="exec-1"
      />,
    )

    await user.click(screen.getByRole('button', { name: /Allow once/ }))

    expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'approved', 'allow-once')
  })

  it('resolves the selected reject option as declined via the store', async () => {
    const user = userEvent.setup()
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionOptions: [{ kind: 'reject_once', name: 'Reject', optionId: 'reject-once' }],
        })}
        executionId="exec-1"
      />,
    )

    await user.click(screen.getByRole('button', { name: /Reject/ }))

    expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'declined', 'reject-once')
  })

  it('cancels via the store', async () => {
    const user = userEvent.setup()
    render(<ActivityApproval activity={pendingActivity()} executionId="exec-1" />)

    await user.click(screen.getByRole('button', { name: /Cancel/ }))

    expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'cancelled')
  })

  it('renders the command in a scrollable pre block', () => {
    render(<ActivityApproval activity={pendingActivity()} executionId="exec-1" />)

    expect(screen.getByText('rm -rf node_modules')).toHaveClass('overflow-x-auto')
  })

  it('bounds the permission card width without outer horizontal scroll', () => {
    render(<ActivityApproval activity={pendingActivity()} executionId="exec-1" />)

    const heading = screen.getByText('Permission Required')
    const card = heading.closest('[data-slot="card"]')
    expect(card).toHaveClass('min-w-0')
    expect(card).not.toHaveClass('overflow-x-auto')
  })

  it('renders the diff preview in a horizontal-scroll container', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionDiff: { newText: 'const a = 1', oldText: 'const a = 2', path: 'src/a.ts' },
        })}
        executionId="exec-1"
      />,
    )

    const path = screen.getByText('src/a.ts')
    expect(path.parentElement).toHaveClass('overflow-x-auto')
  })

  it('truncates a long resolved command instead of overflowing', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({ approved: true, state: 'active' })}
        executionId="exec-1"
      />,
    )

    const summary = screen.getByText(/Permission approved/)
    expect(summary).toHaveClass('truncate', 'min-w-0')
    const card = summary.closest('[data-slot="card"]')
    expect(card).toHaveClass('min-w-0')
  })

  it('renders the resolved summary for an approved activity', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({ approved: true, state: 'active' })}
        executionId="exec-1"
      />,
    )

    expect(screen.getByText(/Permission approved/)).toBeInTheDocument()
  })

  it('renders the resolved summary for a rejected activity', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({ approved: false, state: 'error' })}
        executionId="exec-1"
      />,
    )

    expect(screen.getByText(/Permission rejected/)).toBeInTheDocument()
  })
})
