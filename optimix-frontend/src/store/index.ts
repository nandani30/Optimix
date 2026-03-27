import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { HistoryEntry } from '../types'
import type { User } from '../types'

// ── Types ─────────────────────────────────────────────────────────────────


export interface Connection {
  connectionId: number
  profileName: string
  host: string
  port: number
  databaseName: string
  mysqlUsername: string
  createdAt: string
}


// ── Auth Store ────────────────────────────────────────────────────────────
interface AuthState {
  user: User | null
  token: string | null
  isAuthenticated: boolean
  setAuth: (user: User, token: string) => void
  clearAuth: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      token: null,
      isAuthenticated: false,
      setAuth: (user, token) => {
        // Wipe localStorage first so stale data doesn't persist
        localStorage.removeItem('optimix-auth')
        set({ user, token, isAuthenticated: true })
      },
      clearAuth: () => {
        localStorage.removeItem('optimix-auth')
        localStorage.removeItem('optimix-connections')
        set({ user: null, token: null, isAuthenticated: false })
      },
    }),
    { name: 'optimix-auth' }
  )
)

// ── Connection Store ──────────────────────────────────────────────────────
interface ConnectionState {
  connections: Connection[]
  activeConnectionId: number | null
  setConnections: (c: Connection[]) => void
  addConnection: (c: Connection) => void
  removeConnection: (id: number) => void
  setActiveConnectionId: (id: number | null) => void
}

export const useConnectionStore = create<ConnectionState>()(
  persist(
    (set) => ({
      connections: [],
      activeConnectionId: null,
      setConnections: (connections) => set({ connections }),
      addConnection: (c) => set((s) => ({ connections: [...s.connections, c] })),
      removeConnection: (id) => set((s) => ({
        connections: s.connections.filter(c => c.connectionId !== id),
        activeConnectionId: s.activeConnectionId === id ? null : s.activeConnectionId,
      })),
      setActiveConnectionId: (id) => set({ activeConnectionId: id }),
    }),
    { name: 'optimix-connections' }
  )
)

// ── Optimizer Store ───────────────────────────────────────────────────────
import type { OptimizationResult } from '../types'

interface OptimizerState {
  inputQuery: string
  result: OptimizationResult | null
  isLoading: boolean
  error: string | null
  setInputQuery: (q: string) => void
  setResult: (r: OptimizationResult) => void
  setLoading: (v: boolean) => void
  setError: (e: string) => void
  reset: () => void
}

export const useOptimizerStore = create<OptimizerState>()((set) => ({
  inputQuery: '',
  result:     null,
  isLoading:  false,
  error:      null,
  setInputQuery: (inputQuery) => set({ inputQuery }),
  setResult:     (result)     => set({ result, isLoading: false, error: null }),
  setLoading:    (isLoading)  => set({ isLoading }),
  setError:      (error)      => set({ error, isLoading: false }),
  reset:         ()           => set({ result: null, error: null, isLoading: false }),
}))

// ── History Store ─────────────────────────────────────────────────────────
interface HistoryState {
  entries: HistoryEntry[]
  setEntries: (e: HistoryEntry[]) => void
  addEntry: (e: HistoryEntry) => void
  removeEntry: (id: number) => void  
}

export const useHistoryStore = create<HistoryState>()((set) => ({
  entries: [],
  setEntries: (entries) => set({ entries }),
  addEntry:   (e)       => set((s) => ({ entries: [e, ...s.entries] })),
  removeEntry: (id) =>
    set((s) => ({
      entries: s.entries.filter(e => e.historyId !== id)
    })),
}))
