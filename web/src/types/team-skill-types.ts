export interface TeamSkillDto {
  createdAt: string
  description: string
  enabled: boolean
  id: string
  name: string
  updatedAt: string
}

export interface UpdateTeamSkillRequest {
  enabled?: boolean
}
