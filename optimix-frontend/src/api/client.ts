import axios, { type AxiosError } from 'axios'

export const BASE_URL = 'http://127.0.0.1:7070'

export const apiClient = axios.create({
  baseURL: BASE_URL,
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
})

// Attach JWT from localStorage to every request
apiClient.interceptors.request.use((config) => {
  try {
    const raw = localStorage.getItem('optimix-auth')
    if (raw) {
      const parsed = JSON.parse(raw)
      const token: string | undefined = parsed?.state?.token
      if (token) config.headers.Authorization = `Bearer ${token}`
    }
  } catch {
    // ignore parse errors
  }
  return config
})

// Normalize error responses
apiClient.interceptors.response.use(
  (res) => res,
  (err: AxiosError<{ message?: string }>) => {
    const message =
      err.response?.data?.message ??
      (err.response?.status === 401 ? 'Session expired. Please log in again.' : null) ??
      (err.code === 'ERR_NETWORK' ? 'Cannot connect to Optimix backend. Is it running?' : null) ??
      err.message ??
      'Something went wrong'

    return Promise.reject(new Error(message))
  }
)
