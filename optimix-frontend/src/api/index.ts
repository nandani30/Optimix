import { apiClient } from './client'
import type {
  AuthResponse, LoginRequest, SignupRequest, OtpVerifyRequest,
  Connection, ConnectionForm, ConnectionTestResult,
  OptimizeRequest, OptimizationResult,
  HistoryEntry,
} from '../types'

// ── Auth ──────────────────────────────────────────────────────────────────

export const authApi = {
  signup: (data: SignupRequest) =>
    apiClient.post<{ message: string }>('/api/auth/signup', data).then(r => r.data),

  verifyOtp: (data: OtpVerifyRequest) =>
    apiClient.post<AuthResponse>('/api/auth/verify-otp', data).then(r => r.data),

  resendOtp: (email: string) =>
    apiClient.post<{ message: string }>('/api/auth/resend-otp', { email }).then(r => r.data),

  googleLogin: (googleOauthToken: string) =>
    apiClient.post<AuthResponse>('/api/auth/google', { googleOauthToken }).then(r => r.data),

  login: (data: LoginRequest) =>
    apiClient.post<AuthResponse>('/api/auth/login', data).then(r => r.data),

  logout: () =>
    apiClient.post('/api/auth/logout').then(r => r.data),

  forgotPassword: (email: string) =>
    apiClient.post<{ message: string }>('/api/auth/forgot-password', { email }).then(r => r.data),

  resetPassword: (email: string, otpCode: string, newPassword: string) =>
    apiClient.post<{ message: string }>('/api/auth/reset-password', { email, otpCode, newPassword }).then(r => r.data),
}

// ── Connections ───────────────────────────────────────────────────────────

export const connectionsApi = {
  test: (form: ConnectionForm) =>
    apiClient.post<ConnectionTestResult>('/api/connections/test', form).then(r => r.data),

  save: (form: ConnectionForm) =>
    apiClient.post<{ id: number }>('/api/connections', form).then(r => r.data),

  list: () =>
    apiClient.get<Connection[]>('/api/connections').then(r => r.data),

  delete: (id: number) =>
    apiClient.delete(`/api/connections/${id}`).then(r => r.data),

  testSaved: (id: number) =>
    apiClient.get<ConnectionTestResult>(`/api/connections/${id}/test`).then(r => r.data),
}

// ── Optimization ──────────────────────────────────────────────────────────

export const optimizerApi = {
  optimize: (req: OptimizeRequest) =>
    apiClient.post<OptimizationResult>('/api/optimize', req).then(r => r.data),

  analyze: (req: OptimizeRequest) =>
    apiClient.post<{ cost: number; tables: string[]; issues: string[] }>('/api/analyze', req).then(r => r.data),
}

// ── History ───────────────────────────────────────────────────────────────

export const historyApi = {
  list: () =>
    apiClient.get<HistoryEntry[]>('/api/history').then(r => r.data),

  delete: (id: number) =>
    apiClient.delete(`/api/history/${id}`).then(r => r.data),
}
