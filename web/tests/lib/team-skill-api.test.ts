import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  deleteTeamSkill,
  listTeamSkills,
  updateTeamSkill,
  uploadTeamSkill,
} from '@/lib/team-skill-api'
import { useAuthStore } from '@/store/auth-store'

vi.mock('@/store/auth-store', () => ({
  useAuthStore: {
    getState: vi.fn(),
  },
}))

const mockSkill = {
  createdAt: '2026-09-06T12:00:00Z',
  description: 'Run lint',
  enabled: true,
  id: 'skill-1',
  name: 'lint',
  updatedAt: '2026-09-06T12:00:00Z',
}

describe('team-skill-api', () => {
  const mockFetch = vi.fn()
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = mockFetch
    vi.mocked(useAuthStore.getState).mockReturnValue({
      accessToken: 'mock-token',
    } as unknown as ReturnType<typeof useAuthStore.getState>)
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
    vi.resetAllMocks()
  })

  it('lists team skills', async () => {
    mockFetch.mockResolvedValueOnce({ json: async () => [mockSkill], ok: true })

    await expect(listTeamSkills('team-1')).resolves.toEqual([mockSkill])
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/team-1/skills',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer mock-token',
          'Content-Type': 'application/json',
        }),
      }),
    )
  })

  it('uploads a skill as multipart form data without a JSON content type', async () => {
    mockFetch.mockResolvedValueOnce({ json: async () => mockSkill, ok: true })
    const file = new File(['archive'], 'lint.tar.gz', { type: 'application/gzip' })

    await expect(uploadTeamSkill('team-1', file)).resolves.toEqual(mockSkill)

    const [, options] = mockFetch.mock.calls[0]
    expect(options.method).toBe('POST')
    expect(options.body).toBeInstanceOf(FormData)
    expect((options.body as FormData).get('file')).toBe(file)
    expect(options.headers).not.toHaveProperty('Content-Type')
    expect(options.headers.Authorization).toBe('Bearer mock-token')
  })

  it('updates a skill', async () => {
    mockFetch.mockResolvedValueOnce({ json: async () => mockSkill, ok: true })

    await expect(
      updateTeamSkill('team-1', 'skill-1', { enabled: false }),
    ).resolves.toEqual(mockSkill)

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/team-1/skills/skill-1',
      expect.objectContaining({
        body: JSON.stringify({ enabled: false }),
        method: 'PUT',
      }),
    )
  })

  it('deletes a skill and throws ApiError on failure', async () => {
    mockFetch.mockResolvedValueOnce({ ok: true })

    await deleteTeamSkill('team-1', 'skill-1')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/v1/teams/team-1/skills/skill-1',
      expect.objectContaining({ method: 'DELETE' }),
    )

    mockFetch.mockResolvedValueOnce({
      json: async () => ({ message: 'Skill not found' }),
      ok: false,
      status: 404,
    })

    await expect(deleteTeamSkill('team-1', 'missing')).rejects.toThrow('Skill not found')
  })
})