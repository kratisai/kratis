import { CheckCircle2, HelpCircle, XCircle } from 'lucide-react'
import { useState } from 'react'

import type { ElicitationActivity } from '@/types/execution-activity-types'

import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useActivityStore } from '@/store/activity-store'

interface ActivityElicitationProps {
  activity: ElicitationActivity
  executionId: string
}

interface SchemaOption {
  const?: string
  description?: string
  title?: string
}

interface SchemaProperty {
  anyOf?: SchemaOption[]
  description?: string
  enum?: string[]
  items?: { anyOf?: SchemaOption[]; oneOf?: SchemaOption[]; type?: string }
  oneOf?: SchemaOption[]
  title?: string
  type?: string
}

export function ActivityElicitation({ activity, executionId }: ActivityElicitationProps) {
  const resolveHitl = useActivityStore((s) => s.resolveHitl)
  const [values, setValues] = useState<Record<string, boolean | string | string[]>>({})
  const [isSubmitting, setIsSubmitting] = useState(false)

  const schema = (activity.form ?? {}) as {
    properties?: Record<string, SchemaProperty>
  }
  const properties = schema.properties ?? {}
  const isResolved = activity.state === 'completed' || activity.state === 'error'

  const submit = async (response: 'answered' | 'cancelled' | 'declined') => {
    setIsSubmitting(true)
    try {
      const content = response === 'answered' ? values : undefined
      await resolveHitl(executionId, activity.hitlId, response, undefined, content)
    } catch {
      // handled via websocket resolution
    } finally {
      setIsSubmitting(false)
    }
  }

  if (isResolved) {
    const wasAnswered = activity.response === 'answered'
    return (
      <Card className="border-border/50 bg-muted/30 min-w-0">
        <CardContent className="flex min-w-0 items-center gap-2 p-3">
          {wasAnswered ? (
            <CheckCircle2 className="h-4 w-4 shrink-0 text-green-500" />
          ) : (
            <XCircle className="h-4 w-4 shrink-0 text-red-500" />
          )}
          <span className="min-w-0 flex-1 truncate text-sm">
            Question{' '}
            {wasAnswered ? 'answered' : activity.response === 'declined' ? 'declined' : 'cancelled'}
            : <code className="bg-muted rounded px-1 font-mono text-xs">{activity.message}</code>
          </span>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card className="min-w-0 border-blue-500/50 bg-blue-50 dark:bg-blue-950/20">
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium text-blue-800 dark:text-blue-200">
          Agent Question
        </CardTitle>
      </CardHeader>
      <CardContent className="min-w-0 space-y-3">
        <div className="flex min-w-0 items-start gap-2 text-sm">
          <HelpCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <p className="min-w-0 flex-1 font-medium break-words">{activity.message}</p>
        </div>

        {Object.entries(properties).map(([key, property]) => (
          <SchemaField
            key={key}
            onChange={(value) => setValues((prev) => ({ ...prev, [key]: value }))}
            property={property}
            value={values[key]}
          />
        ))}

        <div className="flex gap-2">
          <Button
            disabled={isSubmitting}
            onClick={() => {
              void submit('answered')
            }}
            size="sm"
            variant="default"
          >
            Submit
          </Button>
          <Button
            disabled={isSubmitting}
            onClick={() => {
              void submit('declined')
            }}
            size="sm"
            variant="outline"
          >
            Decline
          </Button>
          <Button
            disabled={isSubmitting}
            onClick={() => {
              void submit('cancelled')
            }}
            size="sm"
            variant="outline"
          >
            Cancel
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function optionLabel(option: SchemaOption): string {
  return option.title ?? option.const ?? ''
}

function propertyOptions(property: SchemaProperty): SchemaOption[] {
  return property.oneOf ?? property.anyOf ?? []
}

function SchemaField({
  onChange,
  property,
  value,
}: {
  onChange: (value: boolean | string | string[]) => void
  property: SchemaProperty
  value: boolean | string | string[] | undefined
}) {
  const label = property.title ?? property.description
  const options = propertyOptions(property)
  const arrayOptions = property.type === 'array' ? propertyOptions(property.items ?? {}) : []

  if (property.type === 'boolean') {
    return (
      <div className="flex items-center gap-2">
        <Checkbox
          checked={value === true}
          onCheckedChange={(checked) => onChange(checked === true)}
        />
        {label && <Label className="text-sm font-normal">{label}</Label>}
      </div>
    )
  }

  if (options.length > 0) {
    return (
      <div className="space-y-1">
        {label && <Label className="text-sm font-normal">{label}</Label>}
        <Select
          onValueChange={(next) => onChange(next)}
          value={typeof value === 'string' ? value : undefined}
        >
          <SelectTrigger className="w-full">
            <SelectValue placeholder="Select an option" />
          </SelectTrigger>
          <SelectContent>
            {options.map((option) => (
              <SelectItem key={optionLabel(option)} value={option.const ?? optionLabel(option)}>
                {optionLabel(option)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    )
  }

  if (arrayOptions.length > 0) {
    const selected = Array.isArray(value) ? value : []
    return (
      <div className="space-y-1">
        {label && <Label className="text-sm font-normal">{label}</Label>}
        <div className="space-y-1">
          {arrayOptions.map((option) => {
            const optionValue = option.const ?? optionLabel(option)
            const checked = selected.includes(optionValue)
            return (
              <div className="flex items-center gap-2" key={optionValue}>
                <Checkbox
                  checked={checked}
                  onCheckedChange={(checked) => {
                    const next = checked
                      ? [...selected, optionValue]
                      : selected.filter((item) => item !== optionValue)
                    onChange(next)
                  }}
                />
                <Label className="text-sm font-normal">{optionLabel(option)}</Label>
              </div>
            )
          })}
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-1">
      {label && <Label className="text-sm font-normal">{label}</Label>}
      <Input
        onChange={(event) => onChange(event.target.value)}
        type={property.type === 'number' || property.type === 'integer' ? 'number' : 'text'}
        value={typeof value === 'string' ? value : ''}
      />
    </div>
  )
}
