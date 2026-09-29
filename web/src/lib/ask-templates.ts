import type { AskTemplate } from '@/types/ask-template'

export const ASK_TEMPLATES: AskTemplate[] = [
  {
    compilePrompt: (values) => values.message || '',
    description: 'Describe any task or question freely',
    icon: 'Sparkles',
    id: 'free-form',
    segments: [],
    title: 'Free-form',
  },
  {
    compilePrompt: (values) => {
      const repo = values.repo ? values.repo.trim() : '[Repository]'
      const description = values.description ? values.description.trim() : ''
      return `Plan a feature in repository "${repo}" to implement: ${description}\n\nPlease challenge my architectural assumptions, identify potential edge cases, discuss boundary constraints, and outline a potential solution.  Ask questions to remove ambiguity, providing a choice of 2-4 options per question (a/b/c etc.) and your recommendation.`
    },
    description: 'Plan a feature and challenge assumptions',
    icon: 'Lightbulb',
    id: 'plan-and-grill',
    segments: [
      { type: 'text', value: 'Plan a feature in' },
      { control: 'repo-select', key: 'repo', placeholder: 'select repository', type: 'slot' },
      { type: 'text', value: 'to implement' },
      {
        control: 'inline-text',
        key: 'description',
        placeholder: 'describe the feature...',
        type: 'slot',
      },
      { type: 'text', value: 'and challenge my architectural assumptions.' },
    ],
    title: 'Plan & Grill',
  },
  {
    compilePrompt: (values) => {
      const repo = values.repo ? values.repo.trim() : '[Repository]'
      const symptoms = values.symptoms ? values.symptoms.trim() : ''
      const trace = values.trace ? values.trace.trim() : ''
      let prompt = `Diagnose an issue in repository "${repo}".\n\nSymptoms: ${symptoms}`
      if (trace) {
        prompt += `\n\nStack trace / Error logs:\n` + '```\n' + trace + '\n```'
      }
      prompt +=
        '\n\nPlease analyze root causes, trace affected components, and provide concrete remediation steps.'
      return prompt
    },
    description: 'Diagnose bugs with symptoms and stack traces',
    icon: 'Bug',
    id: 'investigate-error',
    segments: [
      { type: 'text', value: 'Diagnose an issue in' },
      { control: 'repo-select', key: 'repo', placeholder: 'select repository', type: 'slot' },
      { type: 'text', value: 'with symptoms' },
      {
        control: 'inline-text',
        key: 'symptoms',
        placeholder: 'short symptom description...',
        type: 'slot',
      },
      { type: 'text', value: 'and stack trace' },
      {
        control: 'expandable-text',
        key: 'trace',
        optional: true,
        placeholder: 'paste error logs or stack trace (optional)...',
        type: 'slot',
      },
      { type: 'text', value: '.' },
    ],
    title: 'Investigate Error',
  },
  {
    compilePrompt: (values) => {
      const repo = values.repo ? values.repo.trim() : '[Repository]'
      const targetPath = values.targetPath ? values.targetPath.trim() : '.'
      return `Audit repository "${repo}" against established architectural patterns and identify boundary violations in path "${targetPath}".\n\nPlease check for circular dependencies, improper layer coupling, domain leaks, and suggest architectural refactoring.`
    },
    description: 'Audit architectural patterns and boundaries',
    icon: 'ShieldAlert',
    id: 'pattern-audit',
    segments: [
      { type: 'text', value: 'Audit' },
      { control: 'repo-select', key: 'repo', placeholder: 'select repository', type: 'slot' },
      {
        type: 'text',
        value:
          'against established architectural patterns and identify boundary violations in path',
      },
      {
        control: 'inline-text',
        key: 'targetPath',
        placeholder: 'src/...',
        type: 'slot',
      },
      { type: 'text', value: '.' },
    ],
    title: 'Architecture Audit',
  },
]

export function getTemplateById(id: string): AskTemplate | undefined {
  return ASK_TEMPLATES.find((t) => t.id === id)
}
