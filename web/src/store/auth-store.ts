import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface Session {
  createdAt: Date
  id: string
  title: string
  type: 'agent' | 'ask' | 'repo'
}

export interface User {
  avatar?: string
  email: string
  id: string
  name: string
}

interface AuthState {
  accessToken: null | string
  addSession: (session: Session) => void
  currentTeamId: null | string
  isAuthenticated: boolean
  isTokenExpired: () => boolean
  login: (user: User, accessToken: string, refreshToken: string, expiresIn: number) => void
  logout: () => void
  refreshToken: null | string
  sessions: Session[]
  setCurrentTeamId: (teamId: null | string) => void
  tokenExpiry: null | number
  updateTokens: (accessToken: string, refreshToken: string, expiresIn: number) => void
  user: null | User
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      addSession: (session) =>
        set((state) => ({
          sessions: [session, ...state.sessions],
        })),
      currentTeamId: null,
      isAuthenticated: false,
      isTokenExpired: () => {
        const { tokenExpiry } = get()
        if (!tokenExpiry) return true
        return Date.now() >= tokenExpiry
      },
      login: (user, accessToken, refreshToken, expiresIn) =>
        set({
          accessToken,
          currentTeamId: null,
          isAuthenticated: true,
          refreshToken,
          tokenExpiry: Date.now() + expiresIn * 1000,
          user,
        }),
      logout: () =>
        set({
          accessToken: null,
          currentTeamId: null,
          isAuthenticated: false,
          refreshToken: null,
          sessions: [],
          tokenExpiry: null,
          user: null,
        }),
      refreshToken: null,
      sessions: [],
      setCurrentTeamId: (teamId) => set({ currentTeamId: teamId }),
      tokenExpiry: null,
      updateTokens: (accessToken, refreshToken, expiresIn) =>
        set({
          accessToken,
          refreshToken,
          tokenExpiry: Date.now() + expiresIn * 1000,
        }),
      user: null,
    }),
    { name: 'kratis-auth' },
  ),
)
