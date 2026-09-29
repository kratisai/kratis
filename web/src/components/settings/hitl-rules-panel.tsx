import { Loader2, Plus, Search, Shield, Trash2, TriangleAlert } from 'lucide-react'
import { useMemo, useState } from 'react'

import type {
  CreateHitlRuleRequest,
  HitlRuleAction,
  HitlRuleDto,
  HitlRuleType,
} from '@/types/hitl-rule-types'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useCreateHitlRule, useDeleteHitlRule, useHitlRules } from '@/hooks/use-hitl-rules'
import { formatRelativeTime } from '@/lib/format'

import { CreateHitlRuleDialog } from './create-hitl-rule-dialog'

type ActionFilter = 'ALL' | HitlRuleAction

export function HitlRulesPanel() {
  const { data: rules, isLoading } = useHitlRules()
  const createRule = useCreateHitlRule()
  const deleteRule = useDeleteHitlRule()

  const [search, setSearch] = useState('')
  const [actionFilter, setActionFilter] = useState<ActionFilter>('ALL')
  const [isCreateOpen, setIsCreateOpen] = useState(false)
  const [ruleToDelete, setRuleToDelete] = useState<HitlRuleDto | null>(null)

  const filteredRules = useMemo(() => {
    if (!rules) return []
    return rules.filter((rule) => {
      const matchesSearch =
        !search.trim() || rule.commandRoot.toLowerCase().includes(search.trim().toLowerCase())
      const matchesAction = actionFilter === 'ALL' || rule.action === actionFilter
      return matchesSearch && matchesAction
    })
  }, [rules, search, actionFilter])

  const handleCreateSubmit = (data: CreateHitlRuleRequest) => {
    createRule.mutate(data, {
      onSuccess: () => {
        setIsCreateOpen(false)
      },
    })
  }

  const handleDeleteConfirm = () => {
    if (ruleToDelete) {
      deleteRule.mutate(ruleToDelete.id, {
        onSuccess: () => {
          setRuleToDelete(null)
        },
      })
    }
  }

  return (
    <>
      <Card>
        <CardHeader>
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div className="flex items-center gap-2">
                <Shield className="h-5 w-5" />
                <CardTitle>HITL Rules</CardTitle>
              </div>
              <CardDescription className="mt-1">
                Manage always-allowed and always-blocked command roots for your team. ALLOW rules
                bypass confirmation unless blocked by a matching DENY rule.
              </CardDescription>
            </div>
            <Button onClick={() => setIsCreateOpen(true)} size="sm">
              <Plus className="mr-1.5 h-4 w-4" />
              Add Rule
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="relative max-w-sm flex-1">
              <Search className="text-muted-foreground absolute top-2.5 left-2.5 h-4 w-4" />
              <Input
                className="pl-9"
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search commands..."
                value={search}
              />
            </div>
            <div className="flex items-center gap-1">
              {(['ALL', 'ALLOW', 'DENY'] as const).map((filter) => (
                <Button
                  key={filter}
                  onClick={() => setActionFilter(filter)}
                  size="sm"
                  variant={actionFilter === filter ? 'secondary' : 'ghost'}
                >
                  {filter === 'ALL' ? 'All' : filter === 'ALLOW' ? 'Allow' : 'Deny'}
                </Button>
              ))}
            </div>
          </div>

          {isLoading ? (
            <div className="flex justify-center py-8">
              <Loader2 className="text-muted-foreground h-6 w-6 animate-spin" />
            </div>
          ) : !rules || rules.length === 0 ? (
            <div className="rounded-lg border border-dashed p-8 text-center">
              <Shield className="text-muted-foreground mx-auto mb-2 h-8 w-8 opacity-50" />
              <p className="text-sm font-medium">No HITL rules configured</p>
              <p className="text-muted-foreground mt-1 text-xs">
                Rules created manually or via &quot;Allow Always&quot; prompts will appear here.
              </p>
            </div>
          ) : filteredRules.length === 0 ? (
            <div className="rounded-lg border border-dashed p-8 text-center">
              <p className="text-sm font-medium">No matching rules</p>
              <p className="text-muted-foreground mt-1 text-xs">
                Try adjusting your search query or filter.
              </p>
            </div>
          ) : (
            <>
              <div className="hidden rounded-md border md:block">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Action</TableHead>
                      <TableHead>Command Pattern</TableHead>
                      <TableHead>Match Type</TableHead>
                      <TableHead>Created By</TableHead>
                      <TableHead>Created</TableHead>
                      <TableHead className="text-right">Actions</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {filteredRules.map((rule) => (
                      <TableRow key={rule.id}>
                        <TableCell>
                          <ActionBadge action={rule.action} />
                        </TableCell>
                        <TableCell className="font-mono text-xs break-all">
                          {rule.commandRoot}
                        </TableCell>
                        <TableCell>
                          <RuleTypeBadge ruleType={rule.ruleType} />
                        </TableCell>
                        <TableCell className="text-muted-foreground text-xs">
                          {rule.createdByName || 'System'}
                        </TableCell>
                        <TableCell className="text-muted-foreground text-xs">
                          {formatRelativeTime(rule.createdAt)}
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            aria-label={`Delete rule for ${rule.commandRoot}`}
                            className="h-8 w-8 p-0"
                            disabled={deleteRule.isPending}
                            onClick={() => setRuleToDelete(rule)}
                            size="sm"
                            variant="ghost"
                          >
                            <Trash2 className="text-destructive h-4 w-4" />
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              <div className="space-y-2 md:hidden" data-testid="hitl-rule-cards">
                {filteredRules.map((rule) => (
                  <div className="rounded-lg border p-3" key={rule.id}>
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="font-mono text-xs break-all">{rule.commandRoot}</p>
                        <div className="mt-2 flex flex-wrap items-center gap-2">
                          <ActionBadge action={rule.action} />
                          <RuleTypeBadge ruleType={rule.ruleType} />
                        </div>
                      </div>
                      <Button
                        aria-label={`Delete rule for ${rule.commandRoot}`}
                        className="h-8 w-8 shrink-0 p-0"
                        disabled={deleteRule.isPending}
                        onClick={() => setRuleToDelete(rule)}
                        size="sm"
                        variant="ghost"
                      >
                        <Trash2 className="text-destructive h-4 w-4" />
                      </Button>
                    </div>
                    <p className="text-muted-foreground mt-1.5 text-xs">
                      {rule.createdByName || 'System'} · {formatRelativeTime(rule.createdAt)}
                    </p>
                  </div>
                ))}
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <CreateHitlRuleDialog
        isLoading={createRule.isPending}
        onOpenChange={setIsCreateOpen}
        onSubmit={handleCreateSubmit}
        open={isCreateOpen}
      />

      <Dialog onOpenChange={(open) => !open && setRuleToDelete(null)} open={!!ruleToDelete}>
        <DialogContent className="flex max-h-[90dvh] flex-col overflow-hidden sm:max-w-lg lg:max-w-2xl">
          <DialogHeader className="min-w-0 shrink-0">
            <div className="flex items-center gap-2">
              <TriangleAlert className="text-destructive h-5 w-5 shrink-0" />
              <DialogTitle>Delete HITL Rule</DialogTitle>
            </div>
            <DialogDescription>
              Are you sure you want to delete this HITL rule? This action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <div className="bg-muted max-h-[40dvh] min-h-0 min-w-0 shrink overflow-y-auto rounded-md border p-3">
            <p className="text-foreground font-mono text-xs break-all">
              {ruleToDelete?.commandRoot}
            </p>
          </div>
          <DialogFooter className="shrink-0">
            <Button onClick={() => setRuleToDelete(null)} type="button" variant="outline">
              Cancel
            </Button>
            <Button
              disabled={deleteRule.isPending}
              onClick={handleDeleteConfirm}
              type="button"
              variant="destructive"
            >
              {deleteRule.isPending ? 'Deleting...' : 'Delete Rule'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

function ActionBadge({ action }: { action: HitlRuleAction }) {
  return action === 'ALLOW' ? (
    <Badge className="border-emerald-500/30 bg-emerald-500/15 text-emerald-600 dark:text-emerald-400">
      ALLOW
    </Badge>
  ) : (
    <Badge className="border-destructive/30 bg-destructive/15 text-destructive">DENY</Badge>
  )
}

function RuleTypeBadge({ ruleType }: { ruleType: HitlRuleType }) {
  const label =
    ruleType === 'EXACT' ? 'Exact' : ruleType === 'TOOL_KIND' ? 'Tool Kind' : 'Prefix Wildcard'
  return <Badge variant="outline">{label}</Badge>
}
