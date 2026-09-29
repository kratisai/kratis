import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { toast } from 'sonner'

import type {
  AddTeamMemberRequest,
  CreateTeamRequest,
  TeamDto,
  UpdateTeamRequest,
  UpdateUserRequest,
} from '@/types/auth-types'

import {
  addTeamMember,
  createTeam,
  getTeam,
  listTeams,
  removeTeamMember,
  updateTavilyApiKey,
  updateTeam,
  updateUserProfile,
} from '@/lib/team-api'
import { useAuthStore } from '@/store/auth-store'

export const TEAMS_QUERY_KEY = 'teams'

interface RemoveTeamMemberVariables {
  teamId: string
  userId: string
}

interface UpdateTeamVariables extends UpdateTeamRequest {
  teamId: string
}

export function useAddTeamMember() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (variables: { data: AddTeamMemberRequest; teamId: string }) =>
      addTeamMember(variables.teamId, variables.data),
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to add team member')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAMS_QUERY_KEY] })
      toast.success('Team member added successfully')
    },
  })
}

export function useCreateTeam() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (data: CreateTeamRequest) => createTeam(data),
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to create team')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAMS_QUERY_KEY] })
      toast.success('Team created successfully')
    },
  })
}

export function useRemoveTeamMember() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (variables: RemoveTeamMemberVariables) =>
      removeTeamMember(variables.teamId, variables.userId),
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to remove team member')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAMS_QUERY_KEY] })
      toast.success('Team member removed successfully')
    },
  })
}

export function useTeam(teamId: string) {
  return useQuery({
    enabled: !!teamId,
    queryFn: () => getTeam(teamId),
    queryKey: [TEAMS_QUERY_KEY, teamId, 'detail'],
  })
}

export function useTeams() {
  const query = useQuery<TeamDto[]>({
    queryFn: listTeams,
    queryKey: [TEAMS_QUERY_KEY],
  })
  useEffect(() => {
    const currentTeamId = useAuthStore.getState().currentTeamId
    if (query.isSuccess && !currentTeamId) {
      useAuthStore.getState().setCurrentTeamId(query.data[0].id)
    }
  }, [query.isSuccess, query.data])
  return query
}

export function useUpdateTavilyApiKey() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (variables: { tavilyApiKey: null | string; teamId: string }) =>
      updateTavilyApiKey(variables.teamId, variables.tavilyApiKey),
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update Tavily API key')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAMS_QUERY_KEY] })
      toast.success('Tavily API key updated successfully')
    },
  })
}

export function useUpdateTeam() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (variables: UpdateTeamVariables) => updateTeam(variables.teamId, variables),
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update team')
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [TEAMS_QUERY_KEY] })
      toast.success('Team updated successfully')
    },
  })
}

export function useUpdateUserProfile() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (data: UpdateUserRequest) => updateUserProfile(data),
    onError: (error: Error) => {
      toast.error(error.message || 'Failed to update profile')
    },
    onSuccess: (data) => {
      void queryClient.invalidateQueries({ queryKey: [TEAMS_QUERY_KEY] })
      // Update the user in auth store
      const { login, user } = useAuthStore.getState()
      if (user) {
        const updatedUser = {
          ...user,
          email: data.email,
          name: data.displayName,
        }
        login(
          updatedUser,
          useAuthStore.getState().accessToken!,
          useAuthStore.getState().refreshToken!,
          0,
        )
      }
      toast.success('Profile updated successfully')
    },
  })
}
