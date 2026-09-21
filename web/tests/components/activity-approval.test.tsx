import { cleanup, render, screen, within } from '@testing-library/react'
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

const ALLOW_ONCE = { kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' } as const
const REJECT_ONCE = { kind: 'reject_once', name: 'Reject', optionId: 'reject-once' } as const

describe('ActivityApproval', () => {
  beforeEach(() => {
    resolveHitl.mockResolvedValue(undefined)
    useActivityStore.setState({ resolveHitl })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('renders one allow and one reject button from the offered options', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionOptions: [
            { kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' },
            { kind: 'reject_once', name: 'Reject', optionId: 'reject-once' },
          ],
        })}
        executionId="exec-1"
      />,
    )

    expect(screen.getByRole('button', { name: /Allow once/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Reject/ })).toBeInTheDocument()
  })

  it('relabels an always-allow fallback option as Allow once', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({
          permissionOptions: [
            { kind: 'allow_always', name: 'Always allow', optionId: 'allow-always' },
            { kind: 'reject_always', name: 'Always reject', optionId: 'reject-always' },
          ],
        })}
        executionId="exec-1"
      />,
    )

    expect(screen.getByRole('button', { name: /Allow once/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Always allow/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Reject/ })).toBeInTheDocument()
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

    expect(resolveHitl).toHaveBeenCalledWith(
      'exec-1',
      'tc-1',
      'approved',
      'allow-once',
      undefined,
      undefined,
      '',
    )
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

    expect(resolveHitl).toHaveBeenCalledWith(
      'exec-1',
      'tc-1',
      'declined',
      'reject-once',
      undefined,
      undefined,
      '',
    )
  })

  it('cancels via the store', async () => {
    const user = userEvent.setup()
    render(<ActivityApproval activity={pendingActivity()} executionId="exec-1" />)

    await user.click(screen.getByRole('button', { name: /Cancel/ }))

    expect(resolveHitl).toHaveBeenCalledWith(
      'exec-1',
      'tc-1',
      'cancelled',
      undefined,
      undefined,
      undefined,
      '',
    )
  })

  it('sends typed feedback as steering guidance with the rejection', async () => {
    const user = userEvent.setup()
    render(
      <ActivityApproval
        activity={pendingActivity({ permissionOptions: [ALLOW_ONCE, REJECT_ONCE] })}
        executionId="exec-1"
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Message to agent' }))
    await user.type(screen.getByLabelText('Message to agent'), 'use pnpm instead')
    await user.click(screen.getByRole('button', { name: /Reject/ }))

    expect(resolveHitl).toHaveBeenCalledWith(
      'exec-1',
      'tc-1',
      'declined',
      'reject-once',
      undefined,
      undefined,
      'use pnpm instead',
    )
  })

  it('hides the feedback textarea until the message toggle is opened', async () => {
    const user = userEvent.setup()
    render(
      <ActivityApproval
        activity={pendingActivity({ permissionOptions: [ALLOW_ONCE, REJECT_ONCE] })}
        executionId="exec-1"
      />,
    )

    expect(screen.queryByLabelText('Message to agent')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Message to agent' }))

    expect(screen.getByLabelText('Message to agent')).toBeInTheDocument()
  })

  it('hides the remember toggle when the approval carries no command segments', () => {
    render(
      <ActivityApproval
        activity={pendingActivity({ permissionOptions: [ALLOW_ONCE, REJECT_ONCE] })}
        executionId="exec-1"
      />,
    )

    expect(screen.queryByRole('button', { name: /Remember choices/ })).not.toBeInTheDocument()
  })

  describe('remember choices panel', () => {
    const segments = [
      { suggestedRoot: 'npm run test', text: 'npm run test src/foo.test.ts' },
      { suggestedRoot: 'git push', text: 'git push' },
    ]
    const segmentedActivity = () =>
      pendingActivity({ permissionOptions: [ALLOW_ONCE, REJECT_ONCE], permissionSegments: segments })

    it('does not show segments until the panel is expanded', async () => {
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)

      expect(screen.queryByLabelText('Command root for npm run test src/foo.test.ts')).not.toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /Remember choices/ }))

      expect(screen.getByLabelText('Command root for npm run test src/foo.test.ts')).toBeInTheDocument()
      expect(screen.getByLabelText('Command root for git push')).toBeInTheDocument()
    })

    it('scrolls the card into view when the remember panel opens', async () => {
      const scrollSpy = vi
        .spyOn(Element.prototype, 'scrollIntoView')
        .mockImplementation(() => undefined)
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)

      await user.click(screen.getByRole('button', { name: /Remember choices/ }))

      expect(scrollSpy).toHaveBeenCalledWith({ behavior: 'smooth', block: 'end' })
      scrollSpy.mockRestore()
    })

    it('prefills each root input with the suggested root', async () => {
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)
      await user.click(screen.getByRole('button', { name: /Remember choices/ }))

      expect(screen.getByLabelText('Command root for npm run test src/foo.test.ts')).toHaveValue(
        'npm run test',
      )
      expect(screen.getByLabelText('Command root for git push')).toHaveValue('git push')
    })

    it('ticked segment turns Allow into Allow and remember and submits the rule', async () => {
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)
      await user.click(screen.getByRole('button', { name: /Remember choices/ }))
      await user.click(screen.getByRole('button', { name: 'Always allow npm run test src/foo.test.ts' }))

      const allowButton = screen.getByRole('button', { name: /Allow and remember/ })
      await user.click(allowButton)

      expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'approved', 'allow-once', undefined, [
        { action: 'ALLOW', commandRoot: 'npm run test' },
      ], '')
    })

    it('edited root text is submitted instead of the suggestion', async () => {
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)
      await user.click(screen.getByRole('button', { name: /Remember choices/ }))

      const input = screen.getByLabelText('Command root for npm run test src/foo.test.ts')
      await user.clear(input)
      await user.type(input, 'npm')
      await user.click(screen.getByRole('button', { name: 'Always allow npm run test src/foo.test.ts' }))
      await user.click(screen.getByRole('button', { name: /Allow and remember/ }))

      expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'approved', 'allow-once', undefined, [
        { action: 'ALLOW', commandRoot: 'npm' },
      ], '')
    })

    it('a crossed segment disables Allow and turns Reject into Reject and remember', async () => {
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)
      await user.click(screen.getByRole('button', { name: /Remember choices/ }))
      await user.click(screen.getByRole('button', { name: 'Always block git push' }))

      expect(screen.getByRole('button', { name: /Allow once/ })).toBeDisabled()

      await user.click(screen.getByRole('button', { name: /Reject and remember/ }))

      expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'declined', 'reject-once', undefined, [
        { action: 'DENY', commandRoot: 'git push' },
      ], '')
    })

    it('mixed marks submit allow and deny rules together as a rejection', async () => {
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)
      await user.click(screen.getByRole('button', { name: /Remember choices/ }))
      await user.click(screen.getByRole('button', { name: 'Always allow npm run test src/foo.test.ts' }))
      await user.click(screen.getByRole('button', { name: 'Always block git push' }))

      await user.click(screen.getByRole('button', { name: /Reject and remember/ }))

      expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'declined', 'reject-once', undefined, [
        { action: 'ALLOW', commandRoot: 'npm run test' },
        { action: 'DENY', commandRoot: 'git push' },
      ], '')
    })

    it('toggling a mark off restores the plain allow flow', async () => {
      const user = userEvent.setup()
      render(<ActivityApproval activity={segmentedActivity()} executionId="exec-1" />)
      await user.click(screen.getByRole('button', { name: /Remember choices/ }))

      const tick = screen.getByRole('button', { name: 'Always allow npm run test src/foo.test.ts' })
      await user.click(tick)
      expect(screen.getByRole('button', { name: /Allow and remember/ })).toBeInTheDocument()

      await user.click(tick)
      expect(tick).toHaveAttribute('aria-pressed', 'false')

      await user.click(screen.getByRole('button', { name: /Allow once/ }))
      expect(resolveHitl).toHaveBeenCalledWith(
        'exec-1',
        'tc-1',
        'approved',
        'allow-once',
        undefined,
        undefined,
        '',
      )
    })

    it('segments with a blank root are skipped when submitting rules', async () => {
      const user = userEvent.setup()
      render(
        <ActivityApproval
          activity={pendingActivity({
            permissionOptions: [ALLOW_ONCE, REJECT_ONCE],
            permissionSegments: [{ suggestedRoot: '', text: '> out.txt' }],
          })}
          executionId="exec-1"
        />,
      )
      await user.click(screen.getByRole('button', { name: /Remember choices/ }))
      await user.click(screen.getByRole('button', { name: 'Always allow > out.txt' }))
      await user.click(screen.getByRole('button', { name: /Allow and remember/ }))

      expect(resolveHitl).toHaveBeenCalledWith(
        'exec-1',
        'tc-1',
        'approved',
        'allow-once',
        undefined,
        undefined,
        '',
      )
    })

    it('passes the segment rule type through to remembered rules', async () => {
      const user = userEvent.setup()
      render(
        <ActivityApproval
          activity={pendingActivity({
            permissionOptions: [ALLOW_ONCE, REJECT_ONCE],
            permissionSegments: [{ ruleType: 'TOOL_KIND', suggestedRoot: 'edit', text: 'edit' }],
          })}
          executionId="exec-1"
        />,
      )

      await user.click(screen.getByRole('button', { name: /Remember choices/ }))
      await user.click(screen.getByRole('button', { name: 'Always allow edit' }))
      await user.click(screen.getByRole('button', { name: /Allow and remember/ }))

      expect(resolveHitl).toHaveBeenCalledWith('exec-1', 'tc-1', 'approved', 'allow-once', undefined, [
        { action: 'ALLOW', commandRoot: 'edit', ruleType: 'TOOL_KIND' },
      ], '')
    })

    it('falls back to segments from the persisted hitl detail', async () => {
      const user = userEvent.setup()
      render(
        <ActivityApproval
          activity={pendingActivity({
            detail: {
              hitl: {
                commandSegments: [{ suggestedRoot: 'make lint', text: 'make lint' }],
                hitlId: 'tc-1',
                kind: 'approval',
                message: 'Approve',
                options: [ALLOW_ONCE, REJECT_ONCE],
              },
            } as never,
            permissionOptions: undefined,
          })}
          executionId="exec-1"
        />,
      )

      await user.click(screen.getByRole('button', { name: /Remember choices/ }))
      const panel = screen.getByLabelText('Command root for make lint')
      expect(within(panel.parentElement!.parentElement!).getByText('make lint')).toBeInTheDocument()
    })
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
