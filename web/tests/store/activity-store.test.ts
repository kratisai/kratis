import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import type { PlanActivity } from '@/types/execution-activity-types'
import type {
  ExecutionActivityResult,
  ExecutionHitlRequiredResult,
  ExecutionHitlResolvedResult,
} from '@/types/websocket-types'

import { activityTitle, useActivityStore } from '@/store/activity-store'

function activityResult(
  overrides: Partial<ExecutionActivityResult> & {
    activityType: ExecutionActivityResult['activityType']
    description: string
  },
): ExecutionActivityResult {
  return {
    executionId: 'exec-1',
    status: 'in_progress',
    type: 'execution_activity',
    ...overrides,
  }
}

function hitlApprovalRequired(
  overrides: Partial<ExecutionHitlRequiredResult> = {},
): ExecutionHitlRequiredResult {
  return {
    command: 'chmod +x script.sh',
    executionId: 'exec-1',
    hitlId: 'tc-1',
    kind: 'approval',
    message: 'Allow chmod +x script.sh?',
    type: 'execution_hitl_required',
    ...overrides,
  }
}

function hitlApprovalResolved(
  overrides: Partial<ExecutionHitlResolvedResult> = {},
): ExecutionHitlResolvedResult {
  return {
    executionId: 'exec-1',
    hitlId: 'tc-1',
    kind: 'approval',
    response: 'approved',
    type: 'execution_hitl_resolved',
    ...overrides,
  }
}

function hitlQuestionRequired(
  overrides: Partial<ExecutionHitlRequiredResult> = {},
): ExecutionHitlRequiredResult {
  return {
    executionId: 'exec-1',
    form: { properties: { target: { type: 'string' } }, type: 'object' },
    hitlId: 'el-1',
    kind: 'question',
    message: 'Choose a deployment target',
    type: 'execution_hitl_required',
    ...overrides,
  }
}

function hitlQuestionResolved(
  overrides: Partial<ExecutionHitlResolvedResult> = {},
): ExecutionHitlResolvedResult {
  return {
    executionId: 'exec-1',
    hitlId: 'el-1',
    kind: 'question',
    response: 'answered',
    type: 'execution_hitl_resolved',
    ...overrides,
  }
}

describe('activity-store', () => {
  beforeEach(() => {
    useActivityStore.setState({ activitiesByExecution: {} })
  })

  afterEach(() => {
    useActivityStore.setState({ activitiesByExecution: {} })
  })

  describe('handleActivityEvent plan updates', () => {
    it('appends a new plan activity per update, expanded by default', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'plan-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: {
            plan: [
              { content: 'Setup repo', priority: 'high', status: 'in_progress' },
              { content: 'Implement feature', priority: 'medium', status: 'pending' },
            ],
          },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'plan-2',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: {
            plan: [{ content: 'Setup repo', priority: 'high', status: 'completed' }],
          },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[0]).toMatchObject({ actionId: 'plan-1', collapsed: false, type: 'plan' })
      expect((activities[0] as PlanActivity).plan).toHaveLength(2)
      expect(activities[1]).toMatchObject({ actionId: 'plan-2', collapsed: false, type: 'plan' })
      expect((activities[1] as PlanActivity).plan).toEqual([
        { content: 'Setup repo', priority: 'high', status: 'completed' },
      ])
    })

    it('merges only lifecycle events sharing an actionId (a single TodoWrite call)', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'todo-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: { plan: [{ content: 'A', priority: 'medium', status: 'in_progress' }] },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'todo-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: { plan: [{ content: 'A', priority: 'medium', status: 'completed' }] },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect((activities[0] as PlanActivity).plan).toEqual([
        { content: 'A', priority: 'medium', status: 'completed' },
      ])
      expect(activities[0].collapsed).toBe(false)
    })

    it('appends a plan activity when the detail carries no entries yet', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'plan-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect((activities[0] as PlanActivity).plan).toEqual([])
      expect(activities[0].collapsed).toBe(false)
    })

    it('keys plans per execution', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'plan-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: { plan: [{ content: 'A', priority: 'medium', status: 'pending' }] },
          executionId: 'exec-1',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'plan-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: { plan: [{ content: 'B', priority: 'high', status: 'completed' }] },
          executionId: 'exec-2',
        }),
      )

      const exec1 = useActivityStore.getState().activitiesByExecution['exec-1']
      const exec2 = useActivityStore.getState().activitiesByExecution['exec-2']
      expect(exec1).toHaveLength(1)
      expect((exec2[0] as PlanActivity).plan[0].content).toBe('B')
    })

    it('keeps a superseded plan snapshot open until the execution completes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'plan-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: {
            plan: [{ content: 'A', priority: 'medium', status: 'in_progress' }],
          },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tool-1',
          activityType: 'RESEARCH',
          description: 'Reading main.go',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      const plan = activities.find((a) => a.type === 'plan') as PlanActivity
      expect(plan).toBeDefined()
      expect(plan.state).toBe('active')
      expect(plan.collapsed).toBe(false)
      expect(activities).toHaveLength(2)
    })

    it('clears the plan activity when activities are cleared', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'plan-1',
          activityType: 'PLAN',
          description: 'Agent plan updated',
          detail: { plan: [{ content: 'A', priority: 'medium', status: 'pending' }] },
        }),
      )
      store.clearActivities('exec-1')

      expect(useActivityStore.getState().activitiesByExecution['exec-1']).toBeUndefined()
    })
  })

  describe('handleActivityEvent correlation by actionId', () => {
    it('merges RESEARCH messages sharing an actionId into a single record', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-1',
          activityType: 'RESEARCH',
          description: 'Reading main.go',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-1',
          activityType: 'RESEARCH',
          description: 'Reading main.go',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-1',
          activityType: 'RESEARCH',
          description: 'Reading main.go',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'tc-1',
        state: 'active',
        toolName: 'Reading main.go',
        type: 'tool_execution',
      })
    })

    it('creates one record per actionId for RESEARCH messages', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-1',
          activityType: 'RESEARCH',
          description: 'Reading main.go',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-2',
          activityType: 'EDITED',
          description: 'Editing config.yaml',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[0]).toMatchObject({ actionId: 'tc-1', type: 'tool_execution' })
      expect(activities[1]).toMatchObject({ actionId: 'tc-2', type: 'tool_execution' })
    })

    it('merges COMMAND messages (tool_call + tool_call_update) into a single record', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ actionId: 'tc-3', activityType: 'COMMAND', description: 'Running tests' }),
      )
      store.handleActivityEvent(
        activityResult({ actionId: 'tc-3', activityType: 'COMMAND', description: 'Running tests' }),
      )
      store.handleActivityEvent(
        activityResult({ actionId: 'tc-3', activityType: 'COMMAND', description: 'Running tests' }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'tc-3',
        command: 'Running tests',
        state: 'active',
        type: 'command_execution',
      })
    })

    it('keeps separate records for messages without an actionId', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ activityType: 'RESEARCH', description: 'Searching' }),
      )
      store.handleActivityEvent(
        activityResult({ activityType: 'RESEARCH', description: 'Searching' }),
      )

      expect(useActivityStore.getState().activitiesByExecution['exec-1']).toHaveLength(2)
    })

    it('does not merge THINKING records with an actionId-bearing tool activity between them', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(activityResult({ activityType: 'THINKING', description: 'first' }))
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-1',
          activityType: 'RESEARCH',
          description: 'Reading main.go',
        }),
      )
      store.handleActivityEvent(activityResult({ activityType: 'THINKING', description: 'second' }))

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(3)
      expect(activities[0]).toMatchObject({ thought: 'first', type: 'thinking' })
      expect(activities[1]).toMatchObject({ type: 'tool_execution' })
      expect(activities[2]).toMatchObject({ thought: 'second', type: 'thinking' })
    })
  })

  describe('HITL approval correlation by actionId', () => {
    it('merges the HITL approval into the command activity of the same action', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-4',
          activityType: 'COMMAND',
          description: 'chmod +x script.sh',
          detail: { hitl: { hitlId: 'tc-4', kind: 'approval', message: 'Allow chmod?' } },
          status: 'pending',
        }),
      )
      store.handleHitlRequired(hitlApprovalRequired({ hitlId: 'tc-4' }))
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-4',
          activityType: 'COMMAND',
          description: 'chmod +x script.sh',
          detail: { hitl: { hitlId: 'tc-4', kind: 'approval', message: 'Allow chmod?' } },
          status: 'pending',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'tc-4',
        approvalRequired: true,
        command: 'chmod +x script.sh',
        state: 'pending_approval',
        type: 'command_execution',
      })
    })

    it('merges into an existing command activity when the tool_call arrives first', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-5',
          activityType: 'COMMAND',
          description: 'Run: chmod +x script.sh',
        }),
      )
      store.handleHitlRequired(hitlApprovalRequired({ hitlId: 'tc-5' }))

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'tc-5',
        approvalRequired: true,
        command: 'chmod +x script.sh',
        state: 'pending_approval',
        type: 'command_execution',
      })
    })

    it('keeps the merged record pending until the resolution event marks it active', () => {
      const store = useActivityStore.getState()
      store.handleHitlRequired(hitlApprovalRequired({ hitlId: 'tc-6' }))
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-6',
          activityType: 'COMMAND',
          description: 'chmod +x script.sh',
        }),
      )
      store.handleHitlResolved(hitlApprovalResolved({ hitlId: 'tc-6', response: 'approved' }))

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        approved: true,
        command: 'chmod +x script.sh',
        state: 'active',
        type: 'command_execution',
      })
    })

    it('ignores an approval whose hitlId matches no record', () => {
      const store = useActivityStore.getState()
      store.handleHitlRequired(hitlApprovalRequired({ hitlId: 'other' }))
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-7',
          activityType: 'COMMAND',
          description: 'chmod +x script.sh',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({ actionId: 'tc-7', state: 'active' })
    })

    it('stores the offered permission options on the pending activity', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-8',
          activityType: 'COMMAND',
          description: 'rm -rf tmp',
          detail: { hitl: { hitlId: 'tc-8', kind: 'approval', message: 'Remove temp dir' } },
          status: 'pending',
        }),
      )
      store.handleHitlRequired(
        hitlApprovalRequired({
          diff: { newText: 'new', oldText: 'old', path: 'a.ts' },
          hitlId: 'tc-8',
          options: [
            { kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' },
            { kind: 'reject_once', name: 'Reject', optionId: 'reject-once' },
          ],
          title: 'Remove temp dir',
          toolKind: 'execute',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        permissionKind: 'execute',
        permissionOptions: [
          { kind: 'allow_once', name: 'Allow once', optionId: 'allow-once' },
          { kind: 'reject_once', name: 'Reject', optionId: 'reject-once' },
        ],
        permissionTitle: 'Remove temp dir',
        state: 'pending_approval',
        type: 'command_execution',
      })
      expect(activities[0].permissionDiff).toEqual({ newText: 'new', oldText: 'old', path: 'a.ts' })
    })

    it('ignores a resolution whose hitlId matches no record', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-9',
          activityType: 'COMMAND',
          description: 'chmod +x script.sh',
          detail: { hitl: { hitlId: 'tc-9', kind: 'approval', message: 'Allow chmod?' } },
          status: 'pending',
        }),
      )

      store.handleHitlResolved(hitlApprovalResolved({ hitlId: 'unknown', response: 'approved' }))
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0]).toMatchObject({
        state: 'pending_approval',
      })

      store.handleHitlResolved(hitlApprovalResolved({ hitlId: 'tc-9', response: 'approved' }))
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0]).toMatchObject({
        approved: true,
        state: 'active',
        type: 'command_execution',
      })
    })

    it('produces two records for a message and a thought sharing one messageId', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'm-1',
          activityType: 'MESSAGE',
          description: 'visible reply',
          detail: { messageId: 'm-1', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'm-1',
          activityType: 'THINKING',
          description: 'private reasoning',
          detail: { messageId: 'm-1' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[0]).toMatchObject({ actionId: 'm-1', text: 'visible reply', type: 'message' })
      expect(activities[1]).toMatchObject({
        actionId: 'm-1',
        thought: 'private reasoning',
        type: 'thinking',
      })
    })

    it('moves one record between buckets when a late kind arrives', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-0',
          activityType: 'MESSAGE',
          description: 'preamble',
          detail: { messageId: 'msg-0', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({ actionId: 'tc-1', activityType: 'COMMAND', description: 'write_file' }),
      )
      store.handleActivityEvent(
        activityResult({ actionId: 'tc-1', activityType: 'EDITED', description: 'write_file' }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[1]).toMatchObject({
        actionId: 'tc-1',
        state: 'active',
        toolName: 'write_file',
        type: 'tool_execution',
      })
    })
  })

  describe('HITL question (form) activities', () => {
    it('handleHitlRequired creates a question activity with form', () => {
      const store = useActivityStore.getState()
      store.handleHitlRequired(
        hitlQuestionRequired({ form: { properties: { target: { type: 'string' } } } }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        form: { properties: { target: { type: 'string' } } },
        hitlId: 'el-1',
        message: 'Choose a deployment target',
        state: 'active',
        type: 'elicitation',
      })
    })

    it('handleHitlRequired does not duplicate the same hitlId', () => {
      const store = useActivityStore.getState()
      store.handleHitlRequired(hitlQuestionRequired())
      store.handleHitlRequired(hitlQuestionRequired())

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
    })

    it('handleHitlResolved answered marks the question completed', () => {
      const store = useActivityStore.getState()
      store.handleHitlRequired(hitlQuestionRequired())
      store.handleHitlResolved(hitlQuestionResolved())

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({
        response: 'answered',
        state: 'completed',
        type: 'elicitation',
      })
    })

    it('handleHitlResolved declined marks the question error', () => {
      const store = useActivityStore.getState()
      store.handleHitlRequired(hitlQuestionRequired())
      store.handleHitlResolved(hitlQuestionResolved({ response: 'declined' }))

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({
        response: 'declined',
        state: 'error',
        type: 'elicitation',
      })
    })

    it('handleActivityEvent replays an ELICITATION activity from detail', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'el-replay',
          activityType: 'ELICITATION',
          description: 'Pick a target',
          detail: {
            hitl: { hitlId: 'el-replay', kind: 'question', message: 'Pick a target' },
          },
          status: 'pending',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        hitlId: 'el-replay',
        message: 'Pick a target',
        state: 'active',
        type: 'elicitation',
      })
    })

    it('handleActivityEvent ELICITATION answered marks the replayed row completed', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'el-done',
          activityType: 'ELICITATION',
          description: 'Pick a target',
          detail: {
            hitl: {
              content: { target: 'staging' },
              hitlId: 'el-done',
              kind: 'question',
              message: 'Pick a target',
              response: 'answered',
            },
          },
          status: 'completed',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({
        response: 'answered',
        state: 'completed',
        type: 'elicitation',
      })
    })

    it('activityTitle returns the question message', () => {
      const store = useActivityStore.getState()
      store.handleHitlRequired(hitlQuestionRequired())
      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activityTitle(activities[0])).toBe('Choose a deployment target')
    })
  })

  describe('status lifecycle transitions', () => {
    it('transitions a command activity pending → in_progress → completed', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-life',
          activityType: 'COMMAND',
          description: 'npm test',
          status: 'pending',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-life',
          activityType: 'COMMAND',
          description: 'npm test',
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-life',
          activityType: 'COMMAND',
          description: 'npm test',
          detail: { exitCode: 0 },
          status: 'completed',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'tc-life',
        exitCode: 0,
        state: 'completed',
        type: 'command_execution',
      })
    })

    it('maps failed status to the error state', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-fail',
          activityType: 'COMMAND',
          description: 'npm test',
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-fail',
          activityType: 'COMMAND',
          description: 'npm test',
          detail: { exitCode: 1, output: 'npm error code ERESOLVE' },
          status: 'failed',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        exitCode: 1,
        state: 'error',
        type: 'command_execution',
      })
    })

    it('keeps the command from the detail input over the description', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-cmd',
          activityType: 'COMMAND',
          description: 'shell · git status',
          detail: { input: { command: 'git status' } },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ command: 'git status', type: 'command_execution' })
    })
  })

  describe('hitl detail correlation', () => {
    it('marks the activity pending_approval when the detail hitl requires approval', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-approve',
          activityType: 'COMMAND',
          description: 'git push',
          detail: { hitl: { hitlId: 'tc-approve', kind: 'approval', message: 'Allow git push?' } },
          status: 'pending',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({
        approvalRequired: true,
        state: 'pending_approval',
        type: 'command_execution',
      })
    })

    it('records the hitl approval resolution from the detail', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-approve2',
          activityType: 'COMMAND',
          description: 'git push',
          detail: { hitl: { hitlId: 'tc-approve2', kind: 'approval', message: 'Allow git push?' } },
          status: 'pending',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-approve2',
          activityType: 'COMMAND',
          description: 'git push',
          detail: {
            hitl: {
              hitlId: 'tc-approve2',
              kind: 'approval',
              message: 'Allow git push?',
              optionId: 'once',
              response: 'approved',
            },
          },
          status: 'in_progress',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({
        approved: true,
        state: 'active',
        type: 'command_execution',
      })
    })
  })

  describe('message and thought chunk accumulation', () => {
    it('replaces MESSAGE text with the cumulative run emission', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'The deletion feature ',
          detail: { messageId: 'msg-1', role: 'agent' },
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'The deletion feature is complete.',
          detail: { messageId: 'msg-1', role: 'agent' },
          status: 'in_progress',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'msg-1',
        role: 'agent',
        text: 'The deletion feature is complete.',
        type: 'message',
      })
    })

    it('starts a new MESSAGE record when the messageId changes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'first',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-2',
          activityType: 'MESSAGE',
          description: 'second',
          detail: { messageId: 'msg-2', role: 'user' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[1]).toMatchObject({ role: 'user', text: 'second', type: 'message' })
    })

    it('replaces THINKING text with the cumulative run emission', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'thought-1',
          activityType: 'THINKING',
          description: 'reasoning part one ',
          detail: { messageId: 'thought-1' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'thought-1',
          activityType: 'THINKING',
          description: 'reasoning part one reasoning part two',
          detail: { messageId: 'thought-1' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'thought-1',
        thought: 'reasoning part one reasoning part two',
        type: 'thinking',
      })
    })

    it('replaces MESSAGE text when a replayed merged chunk supersedes the partial text', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'The deletion feature ',
          detail: { messageId: 'msg-1', role: 'agent' },
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'The deletion feature is complete.',
          detail: { messageId: 'msg-1', role: 'agent' },
          status: 'in_progress',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'msg-1',
        text: 'The deletion feature is complete.',
        type: 'message',
      })
    })

    it('does not double MESSAGE text when the same chunk is replayed', () => {
      const store = useActivityStore.getState()
      const chunk = {
        actionId: 'msg-1',
        activityType: 'MESSAGE' as const,
        description: 'The deletion feature is complete.',
        detail: { messageId: 'msg-1', role: 'agent' },
        status: 'in_progress' as const,
      }
      store.handleActivityEvent(activityResult(chunk))
      store.handleActivityEvent(activityResult(chunk))
      store.handleActivityEvent(activityResult(chunk))

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'msg-1',
        text: 'The deletion feature is complete.',
        type: 'message',
      })
    })

    it('does not double THINKING text when the same chunk is replayed', () => {
      const store = useActivityStore.getState()
      const chunk = {
        actionId: 'thought-1',
        activityType: 'THINKING' as const,
        description: 'reasoning part one reasoning part two',
        detail: { messageId: 'thought-1' },
        status: 'in_progress' as const,
      }
      store.handleActivityEvent(activityResult(chunk))
      store.handleActivityEvent(activityResult(chunk))

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'thought-1',
        thought: 'reasoning part one reasoning part two',
        type: 'thinking',
      })
    })

    it('replaces THINKING text when a replayed merged chunk supersedes the partial text', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'thought-1',
          activityType: 'THINKING',
          description: 'reasoning part one ',
          detail: { messageId: 'thought-1' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'thought-1',
          activityType: 'THINKING',
          description: 'reasoning part one reasoning part two',
          detail: { messageId: 'thought-1' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'thought-1',
        thought: 'reasoning part one reasoning part two',
        type: 'thinking',
      })
    })

    it('keeps the previous message stream open until its terminal status arrives', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'first message',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-2',
          activityType: 'MESSAGE',
          description: 'second message',
          detail: { messageId: 'msg-2', role: 'agent' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[0]).toMatchObject({
        actionId: 'msg-1',
        state: 'active',
        type: 'message',
      })
      expect(activities[1]).toMatchObject({
        actionId: 'msg-2',
        state: 'active',
        type: 'message',
      })

      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'first message',
          detail: { messageId: 'msg-1', role: 'agent' },
          status: 'completed',
        }),
      )
      const closed = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(closed[0]).toMatchObject({ state: 'completed', type: 'message' })
      expect(closed[0].endedAt).toBeDefined()
    })

    it('keeps the open message stream open when a tool activity starts', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'agent message',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-1',
          activityType: 'RESEARCH',
          description: 'Reading main.go',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'active', type: 'message' })
      expect(activities[1]).toMatchObject({ actionId: 'tc-1', type: 'tool_execution' })
    })

    it('keeps the open message stream open when a command activity starts', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'agent message',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-2',
          activityType: 'COMMAND',
          description: 'npm test',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'active', type: 'message' })
      expect(activities[1]).toMatchObject({ actionId: 'tc-2', type: 'command_execution' })
    })

    it('keeps the open thinking stream open when a message stream starts', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ activityType: 'THINKING', description: 'reasoning' }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'agent reply',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'active', type: 'thinking' })
      expect(activities[1]).toMatchObject({ state: 'active', type: 'message' })
    })

    it('does not close the message stream on same-stream continuation chunks', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'Hello ',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'Hello world',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({
        actionId: 'msg-1',
        state: 'active',
        text: 'Hello world',
        type: 'message',
      })
    })

    it('does not collapse a closed message when a new activity appends', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'first',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-2',
          activityType: 'MESSAGE',
          description: 'second',
          detail: { messageId: 'msg-2', role: 'agent' },
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0].collapsed).toBe(false)
    })

    it('renders a replayed completed message stream as completed', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'full message',
          detail: { messageId: 'msg-1', role: 'agent' },
          status: 'completed',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'completed', type: 'message' })
    })
  })

  describe('execution complete closes trailing streams', () => {
    it('closes a trailing message stream when the execution completes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'trailing message',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleExecutionComplete({
        executionId: 'exec-1',
        exitCode: 0,
        status: 'COMPLETED',
        type: 'execution_complete',
      })

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'completed', type: 'message' })
      expect(activities[0].endedAt).toBeDefined()
    })

    it('closes a trailing thinking stream when the execution completes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ activityType: 'THINKING', description: 'last thought' }),
      )
      store.handleExecutionComplete({
        executionId: 'exec-1',
        exitCode: 0,
        status: 'COMPLETED',
        type: 'execution_complete',
      })

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'completed', type: 'thinking' })
    })

    it('closes multiple trailing open streams when the execution completes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'thought-1',
          activityType: 'THINKING',
          description: 'reasoning',
          detail: { messageId: 'thought-1' },
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'msg-1',
          activityType: 'MESSAGE',
          description: 'message',
          detail: { messageId: 'msg-1', role: 'agent' },
        }),
      )
      store.handleExecutionComplete({
        executionId: 'exec-1',
        exitCode: 0,
        status: 'COMPLETED',
        type: 'execution_complete',
      })

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'completed', type: 'thinking' })
      expect(activities[1]).toMatchObject({ state: 'completed', type: 'message' })
    })
  })

  describe('collapsed state management', () => {
    it('starts open and auto-collapses a command on completion', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-collapse',
          activityType: 'COMMAND',
          description: 'npm test',
          status: 'in_progress',
        }),
      )
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0]).toMatchObject({
        collapsed: false,
        state: 'active',
      })

      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-collapse',
          activityType: 'COMMAND',
          description: 'npm test',
          status: 'completed',
        }),
      )
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0]).toMatchObject({
        collapsed: true,
        state: 'completed',
      })
    })

    it('auto-collapses a tool execution on failure', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-collapse-tool',
          activityType: 'RESEARCH',
          description: 'read main.go',
          detail: { kind: 'read', locations: [{ path: 'main.go' }] },
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-collapse-tool',
          activityType: 'RESEARCH',
          description: 'read main.go',
          status: 'failed',
        }),
      )
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0]).toMatchObject({
        collapsed: true,
        state: 'error',
      })
    })

    it('toggleCollapsed expands a collapsed activity and a manual open survives later events', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-toggle',
          activityType: 'COMMAND',
          description: 'npm test',
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-toggle',
          activityType: 'COMMAND',
          description: 'npm test',
          detail: { exitCode: 0 },
          status: 'completed',
        }),
      )
      const activityId = useActivityStore.getState().activitiesByExecution['exec-1'][0].id

      store.toggleCollapsed('exec-1', activityId)
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0].collapsed).toBe(false)

      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-toggle',
          activityType: 'COMMAND',
          description: 'npm test',
          detail: { exitCode: 0 },
          status: 'completed',
        }),
      )
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0].collapsed).toBe(false)
    })

    it('derives the descriptive title from kind and target', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-title',
          activityType: 'RESEARCH',
          description: 'read',
          detail: { kind: 'read', locations: [{ path: '/kratis/workspace/main.go' }] },
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-title-search',
          activityType: 'RESEARCH',
          description: 'glob',
          detail: { input: { pattern: '**/*.ts' }, kind: 'search' },
          status: 'in_progress',
        }),
      )
      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activityTitle(activities[0])).toBe('read /kratis/workspace/main.go')
      expect(activityTitle(activities[1])).toBe('search **/*.ts')
    })

    it('collapses the previous activity when a new one arrives, leaving the last expanded', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'a1',
          activityType: 'RESEARCH',
          description: 'read pom.xml',
          status: 'completed',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'a2',
          activityType: 'RESEARCH',
          description: 'read EmailService.java',
          status: 'completed',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'a3',
          activityType: 'EDITED',
          description: 'edit Participant.java',
          status: 'completed',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(3)
      expect(activities[0].collapsed).toBe(true)
      expect(activities[1].collapsed).toBe(true)
      expect(activities[2].collapsed).toBe(false)
    })
  })

  describe('thinking auto-collapse', () => {
    it('starts open and auto-collapses when the thought completes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'thought-1',
          activityType: 'THINKING',
          description: 'reasoning',
          detail: { messageId: 'thought-1' },
          status: 'in_progress',
        }),
      )
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0]).toMatchObject({
        collapsed: false,
        state: 'active',
      })

      store.handleActivityEvent(
        activityResult({
          actionId: 'thought-1',
          activityType: 'THINKING',
          description: 'reasoning',
          detail: { messageId: 'thought-1' },
          status: 'completed',
        }),
      )
      expect(useActivityStore.getState().activitiesByExecution['exec-1'][0]).toMatchObject({
        collapsed: true,
        state: 'completed',
      })
    })

    it('keeps the thinking stream open when a tool activity starts', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ activityType: 'THINKING', description: 'reasoning' }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-1',
          activityType: 'RESEARCH',
          description: 'read main.go',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ collapsed: false, state: 'active' })
      expect(activities[1]).toMatchObject({ actionId: 'tc-1', state: 'active' })
    })

    it('auto-collapses a trailing thinking stream when the execution completes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ activityType: 'THINKING', description: 'last thought' }),
      )
      store.handleExecutionComplete({
        executionId: 'exec-1',
        exitCode: 0,
        status: 'COMPLETED',
        type: 'execution_complete',
      })

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ collapsed: true, state: 'completed' })
    })
  })

  describe('parallel activities stay open until their own terminal status', () => {
    it('keeps two parallel tool calls active at the same time', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'search-1',
          activityType: 'RESEARCH',
          description: 'glob',
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'search-2',
          activityType: 'RESEARCH',
          description: 'grep',
          status: 'in_progress',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[0]).toMatchObject({
        actionId: 'search-1',
        collapsed: false,
        state: 'active',
      })
      expect(activities[1]).toMatchObject({
        actionId: 'search-2',
        collapsed: false,
        state: 'active',
      })
    })

    it('keeps two parallel commands active at the same time', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ actionId: 'cmd-1', activityType: 'COMMAND', description: 'npm test' }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'cmd-2',
          activityType: 'COMMAND',
          description: 'npm run build',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(2)
      expect(activities[0]).toMatchObject({
        actionId: 'cmd-1',
        collapsed: false,
        state: 'active',
      })
      expect(activities[1]).toMatchObject({ actionId: 'cmd-2', state: 'active' })
    })

    it('does not close the activity being updated on a progress update', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'search-1',
          activityType: 'RESEARCH',
          description: 'glob',
          status: 'in_progress',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'search-1',
          activityType: 'RESEARCH',
          description: 'glob',
          detail: { kind: 'search' },
          status: 'in_progress',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities).toHaveLength(1)
      expect(activities[0]).toMatchObject({ actionId: 'search-1', state: 'active' })
    })

    it('leaves a pending approval open when a new tool starts', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-approve',
          activityType: 'COMMAND',
          description: 'chmod +x script.sh',
          detail: { hitl: { hitlId: 'tc-approve', kind: 'approval', message: 'Allow?' } },
          status: 'pending',
        }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'tc-next',
          activityType: 'RESEARCH',
          description: 'search',
        }),
      )

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'pending_approval' })
      expect(activities[1]).toMatchObject({ actionId: 'tc-next', state: 'active' })
    })
  })

  describe('execution complete closes every open activity', () => {
    it('closes and collapses an active tool execution when the execution completes', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ actionId: 'search-1', activityType: 'RESEARCH', description: 'glob' }),
      )
      store.handleExecutionComplete({
        executionId: 'exec-1',
        exitCode: 0,
        status: 'COMPLETED',
        type: 'execution_complete',
      })

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({
        actionId: 'search-1',
        collapsed: true,
        state: 'completed',
      })
      expect(activities[0].endedAt).toBeDefined()
    })

    it('closes every active command, not just the last one', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ actionId: 'cmd-1', activityType: 'COMMAND', description: 'npm test' }),
      )
      store.handleActivityEvent(
        activityResult({
          actionId: 'cmd-2',
          activityType: 'COMMAND',
          description: 'npm run build',
        }),
      )
      store.handleExecutionComplete({
        executionId: 'exec-1',
        exitCode: 0,
        status: 'COMPLETED',
        type: 'execution_complete',
      })

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({ state: 'completed' })
      expect(activities[1]).toMatchObject({ state: 'completed' })
    })

    it('marks an active command as error with the exit code on a failed execution', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ actionId: 'cmd-1', activityType: 'COMMAND', description: 'npm test' }),
      )
      store.handleExecutionComplete({
        executionId: 'exec-1',
        exitCode: 1,
        status: 'FAILED',
        type: 'execution_complete',
      })

      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activities[0]).toMatchObject({
        collapsed: true,
        exitCode: 1,
        state: 'error',
      })
    })
  })

  describe('thinking activity title', () => {
    it('uses the thought as a single-line summary title', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ activityType: 'THINKING', description: 'Searching' }),
      )
      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activityTitle(activities[0])).toBe('Searching')
    })

    it('uses the first line of a multi-line thought as the title', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({
          activityType: 'THINKING',
          description: 'Searching for the failing test.\nFound it.',
        }),
      )
      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activityTitle(activities[0])).toBe('Searching for the failing test.')
    })

    it('falls back to "Thinking" when the thought is blank', () => {
      const store = useActivityStore.getState()
      store.handleActivityEvent(
        activityResult({ activityType: 'THINKING', description: '   \n  ' }),
      )
      const activities = useActivityStore.getState().activitiesByExecution['exec-1']
      expect(activityTitle(activities[0])).toBe('Thinking')
    })
  })
})
