import { useState, useEffect, useCallback } from 'react'
import { BASE_URL } from '../api/client'

type BackendStatus = 'checking' | 'online' | 'offline'

/**
 * Polls the backend health endpoint to check if the Java server is running.
 * Shows a status indicator in the UI so users know if the backend is up.
 *
 * Retries every 3 seconds when offline (backend may be starting up).
 */
export function useBackendStatus() {
  const [status,  setStatus]  = useState<BackendStatus>('checking')
  const [retries, setRetries] = useState(0)

  const check = useCallback(async () => {
    try {
      const res = await fetch(`${BASE_URL}/health`, {
        signal: AbortSignal.timeout(3000),
      })
      setStatus(res.ok ? 'online' : 'offline')
    } catch {
      setStatus('offline')
      setRetries((r) => r + 1)
    }
  }, [])

  useEffect(() => {
    check()
  }, [check])

  // Retry every 3 seconds when offline
  useEffect(() => {
    if (status === 'offline') {
      const timer = setTimeout(check, 3000)
      return () => clearTimeout(timer)
    }
  }, [status, retries, check])

  return { status, retry: check }
}
