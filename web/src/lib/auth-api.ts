import type {
  AuthTokensResponse,
  LoginRequest,
  RefreshTokenRequest,
  RegisterUserRequest,
} from '@/types/auth-types'

import { useAuthStore } from '@/store/auth-store'

const API_BASE_URL = import.meta.env.VITE_API_URL || ''

export class ApiError extends Error {
  data?: unknown
  status: number

  constructor(message: string, status: number, data?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.data = data
  }
}

export async function fetchWithAuth(url: string, options: RequestInit = {}): Promise<Response> {
  const { accessToken } = useAuthStore.getState()

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string>),
  }

  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`
  }

  const response = await window.fetch(`${API_BASE_URL}${url}`, {
    ...options,
    headers,
  })

  if (!response.ok) {
    const errorData = await response.json().catch(() => null)
    throw new ApiError(errorData?.message || `HTTP ${response.status}`, response.status, errorData)
  }

  return response
}

export async function login(data: LoginRequest): Promise<AuthTokensResponse> {
  const response = await fetchWithAuth('/api/v1/auth/login', {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function refreshAccessToken(data: RefreshTokenRequest): Promise<AuthTokensResponse> {
  const response = await fetchWithAuth('/api/v1/auth/refresh', {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}

export async function register(data: RegisterUserRequest): Promise<AuthTokensResponse['user']> {
  const response = await fetchWithAuth('/api/v1/auth/register', {
    body: JSON.stringify(data),
    method: 'POST',
  })
  return response.json()
}
