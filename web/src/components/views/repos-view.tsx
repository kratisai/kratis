'use client'

import { useNavigate } from '@tanstack/react-router'
import { GitBranch, Loader2, Plus, Search } from 'lucide-react'
import { useCallback, useMemo, useState } from 'react'

import type { RepositorySubmitPayload } from '@/components/repos/repository-form-types'
import type { RepositoryDto } from '@/types/auth-types'

import { DeleteConfirmDialog } from '@/components/repos/delete-confirm-dialog'
import { RepositoryCard } from '@/components/repos/repository-card'
import { RepositoryFormDialog } from '@/components/repos/repository-form-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  useCreateRepository,
  useDeleteRepository,
  useRepositories,
  useUpdateRepository,
} from '@/hooks/use-repositories'

export function ReposView() {
  const navigate = useNavigate()
  const { data: repositories, isLoading } = useRepositories()
  const sortedRepositories = useMemo(() => {
    if (!repositories) return []
    return [...repositories].sort((a, b) =>
      a.name.toLowerCase().localeCompare(b.name.toLowerCase()),
    )
  }, [repositories])

  const [filter, setFilter] = useState('')

  const filteredRepositories = useMemo(() => {
    const query = filter.trim().toLowerCase()
    if (!query) return sortedRepositories
    return sortedRepositories.filter(
      (repo) => repo.name.toLowerCase().includes(query) || repo.url.toLowerCase().includes(query),
    )
  }, [filter, sortedRepositories])

  const createMutation = useCreateRepository()
  const updateMutation = useUpdateRepository()
  const deleteMutation = useDeleteRepository()

  const [editingRepo, setEditingRepo] = useState<null | RepositoryDto>(null)
  const [deletingRepo, setDeletingRepo] = useState<null | RepositoryDto>(null)
  const [showFormDialog, setShowFormDialog] = useState(false)

  const handleOpenForm = useCallback((repo?: RepositoryDto) => {
    setEditingRepo(repo ?? null)
    setShowFormDialog(true)
  }, [])

  const handleCloseForm = useCallback(() => {
    setShowFormDialog(false)
    setEditingRepo(null)
  }, [])

  const handleFormSubmit = useCallback(
    (data: RepositorySubmitPayload) => {
      if (editingRepo) {
        updateMutation.mutate({
          branch: data.branch,
          credentialId: data.credentialId || undefined,
          name: data.name,
          repoId: editingRepo.id,
          repositoryType: data.repositoryType,
        })
      } else {
        createMutation.mutate(data)
      }
      handleCloseForm()
    },
    [createMutation, editingRepo, handleCloseForm, updateMutation],
  )

  const handleDelete = useCallback(
    (repo: RepositoryDto) => {
      deleteMutation.mutate(repo.id, {
        onSuccess: () => setDeletingRepo(null),
      })
    },
    [deleteMutation],
  )

  const handleNavigate = useCallback(
    (repo: RepositoryDto) => {
      void navigate({ params: { id: repo.id }, to: '/repos/$id' })
    },
    [navigate],
  )

  const isPending = createMutation.isPending || updateMutation.isPending || deleteMutation.isPending

  return (
    <div className="min-h-full overflow-visible p-6 md:h-full md:overflow-auto">
      <div className="mx-auto max-w-4xl space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold">Repositories</h1>
            <p className="text-muted-foreground">Connect and manage your code repositories</p>
          </div>
          <Button onClick={() => handleOpenForm()}>
            <Plus className="mr-2 h-4 w-4" />
            Add Repository
          </Button>
        </div>

        {isLoading && (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="text-muted-foreground h-8 w-8 animate-spin" />
          </div>
        )}

        {!isLoading && sortedRepositories.length === 0 && (
          <div className="text-muted-foreground rounded-lg border border-dashed p-12 text-center">
            <GitBranch className="mx-auto mb-4 h-12 w-12 opacity-50" />
            <p className="text-lg font-medium">No repositories yet</p>
            <p className="mt-1 text-sm">Add a repository to get started</p>
          </div>
        )}

        {!isLoading && sortedRepositories.length > 0 && (
          <div className="relative">
            <Search className="text-muted-foreground absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2" />
            <Input
              aria-label="Search repositories"
              className="pl-9"
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Search repositories..."
              value={filter}
            />
          </div>
        )}

        {!isLoading && sortedRepositories.length > 0 && filteredRepositories.length === 0 && (
          <div className="text-muted-foreground rounded-lg border border-dashed p-12 text-center">
            <p className="text-lg font-medium">No repositories match &quot;{filter}&quot;</p>
            <p className="mt-1 text-sm">Try a different search term</p>
          </div>
        )}

        <div className="grid gap-4">
          {filteredRepositories.map((repo) => (
            <RepositoryCard
              key={repo.id}
              onEdit={handleOpenForm}
              onNavigate={handleNavigate}
              onRequestDelete={setDeletingRepo}
              repo={repo}
            />
          ))}
        </div>
      </div>

      <RepositoryFormDialog
        isOpen={showFormDialog}
        isPending={isPending}
        onClose={handleCloseForm}
        onSubmit={handleFormSubmit}
        repo={editingRepo}
      />

      <DeleteConfirmDialog
        isPending={deleteMutation.isPending}
        onCancel={() => setDeletingRepo(null)}
        onConfirm={() => deletingRepo && handleDelete(deletingRepo)}
        repoName={deletingRepo?.name ?? ''}
      />
    </div>
  )
}
