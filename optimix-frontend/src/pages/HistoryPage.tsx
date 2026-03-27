import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { clsx } from 'clsx'
import { useHistoryStore, useOptimizerStore } from '../store'
import { historyApi } from '../api'
import type { HistoryEntry } from '../types'

export default function HistoryPage() {
  const { entries, setEntries, removeEntry } = useHistoryStore()
  const { setInputQuery, reset }             = useOptimizerStore()
  const navigate                             = useNavigate()

  const [loading, setLoading]     = useState(false)
  const [search, setSearch]       = useState('')
  const [deletingId, setDeletingId] = useState<number | null>(null)
  const [expandedId, setExpandedId] = useState<number | null>(null)

  useEffect(() => {
    setLoading(true)
    historyApi.list()
      .then(setEntries)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  const handleReRun = (entry: HistoryEntry) => {
    setInputQuery(entry.originalQuery)
    reset()
    navigate('/optimizer')
  }

  const handleDelete = async (id: number) => {
    setDeletingId(id)
    try {
      await historyApi.delete(id)
      removeEntry(id)
    } catch { /* ignore */ }
    finally { setDeletingId(null) }
  }

  const parsePatterns = (raw: string): string[] => {
    try {
      return JSON.parse(raw) as string[]
    } catch {
      return []
    }
  }

  const filtered = entries.filter((e) => {
    if (!search.trim()) return true
    const q = search.toLowerCase()
    return (
      e.originalQuery.toLowerCase().includes(q) ||
      e.patternsApplied.toLowerCase().includes(q)
    )
  })

  const formatDate = (iso: string) => {
    const d = new Date(iso)
    const now = new Date()
    const diffMs = now.getTime() - d.getTime()
    const diffMins  = Math.floor(diffMs / 60_000)
    const diffHours = Math.floor(diffMs / 3_600_000)
    const diffDays  = Math.floor(diffMs / 86_400_000)
    if (diffMins < 1)   return 'just now'
    if (diffMins < 60)  return `${diffMins}m ago`
    if (diffHours < 24) return `${diffHours}h ago`
    if (diffDays < 7)   return `${diffDays}d ago`
    return d.toLocaleDateString()
  }

  const SpeedupBadge = ({ v }: { v: number }) => (
    <span className={clsx(
      'inline-flex items-center px-2 py-0.5 rounded text-xs font-mono font-bold',
      v >= 10 ? 'bg-accent/20 text-accent border border-accent/30' :
      v >= 3  ? 'bg-yellow/20 text-yellow border border-yellow/30' :
                'bg-bg-overlay text-text-muted border border-border'
    )}>
      {v.toFixed(1)}×
    </span>
  )

  return (
    <div className="h-full flex flex-col bg-bg-base">

      {/* Header */}
      <div className="px-6 py-4 border-b border-border bg-bg-surface flex-shrink-0">
        <div className="flex items-center justify-between mb-3">
          <div>
            <h1 className="text-lg font-semibold text-text-primary">Optimization History</h1>
            <p className="text-xs text-text-muted mt-0.5">
              {entries.length} quer{entries.length === 1 ? 'y' : 'ies'} optimized
            </p>
          </div>
        </div>
        <input
          type="text"
          placeholder="Search queries or patterns…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-full bg-bg-raised border border-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder-text-muted focus:outline-none focus:border-accent transition-colors"
        />
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-4">

        {loading && (
          <div className="space-y-2">
            {[1, 2, 3].map((i) => (
              <div key={i} className="skeleton h-20 rounded-xl" />
            ))}
          </div>
        )}

        {!loading && filtered.length === 0 && (
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <div className="text-5xl mb-4 opacity-20">📭</div>
            <p className="text-sm text-text-muted">
              {search ? 'No results match your search' : 'No optimization history yet'}
            </p>
            {!search && (
              <p className="text-xs text-text-disabled mt-1">
                Optimize your first query to see it here
              </p>
            )}
          </div>
        )}

        <div className="space-y-2">
          {filtered.map((entry) => {
            const patterns  = parsePatterns(entry.patternsApplied)
            const isExpanded = expandedId === entry.historyId

            return (
              <div
                key={entry.historyId}
                className="bg-bg-surface border border-border rounded-xl overflow-hidden transition-colors hover:border-border"
              >
                {/* Row header — always visible */}
                <div
                  className="flex items-center gap-3 p-4 cursor-pointer hover:bg-bg-raised transition-colors"
                  onClick={() => setExpandedId(isExpanded ? null : entry.historyId)}
                >
                  <SpeedupBadge v={entry.speedupFactor} />

                  <div className="flex-1 min-w-0">
                    <p className="text-xs text-text-primary font-mono truncate">
                      {entry.originalQuery
                        .replace(/\s+/g, ' ')
                        .trim()
                        .slice(0, 100)}
                      {entry.originalQuery.length > 100 && '…'}
                    </p>
                    <div className="flex items-center gap-3 mt-1">
                      <span className="text-2xs text-text-disabled">{formatDate(entry.createdAt)}</span>
                      {patterns.length > 0 && (
                        <span className="text-2xs text-text-muted">
                          {patterns.length} pattern{patterns.length !== 1 ? 's' : ''}
                        </span>
                      )}
                      <span className="text-2xs text-text-disabled">
                        {entry.originalCost != null ? entry.originalCost.toFixed(0) : '—'} → 
{entry.optimizedCost != null ? entry.optimizedCost.toFixed(0) : '—'} units
                      </span>
                    </div>
                  </div>

                  <div className="flex items-center gap-1.5 flex-shrink-0">
                    <button
                      onClick={(ev) => { ev.stopPropagation(); handleReRun(entry) }}
                      className="text-xs px-2.5 py-1 border border-border rounded hover:border-accent hover:text-accent text-text-muted transition-colors"
                    >
                      Re-run
                    </button>
                    <button
                      onClick={(ev) => { ev.stopPropagation(); handleDelete(entry.historyId) }}
                      disabled={deletingId === entry.historyId}
                      className="text-xs px-2.5 py-1 border border-border rounded hover:border-red hover:text-red text-text-muted transition-colors disabled:opacity-40"
                    >
                      {deletingId === entry.historyId ? '⟳' : 'Delete'}
                    </button>
                    <span className="text-text-disabled text-xs w-4 text-center">
                      {isExpanded ? '▲' : '▼'}
                    </span>
                  </div>
                </div>

                {/* Expanded detail */}
                {isExpanded && (
                  <div className="border-t border-border p-4 space-y-3 animate-fade-in bg-bg-base/50">
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <p className="text-2xs text-text-disabled uppercase tracking-wider mb-2">Original</p>
                        <pre className="text-xs font-mono bg-bg-base border border-border rounded-lg p-3 overflow-x-auto text-text-secondary whitespace-pre-wrap max-h-32 overflow-y-auto">
                          {entry.originalQuery.trim()}
                        </pre>
                      </div>
                      <div>
                        <p className="text-2xs text-text-disabled uppercase tracking-wider mb-2">Optimized</p>
                        <pre className="text-xs font-mono bg-bg-base border border-accent/20 rounded-lg p-3 overflow-x-auto text-accent whitespace-pre-wrap max-h-32 overflow-y-auto">
                          {entry.optimizedQuery.trim()}
                        </pre>
                      </div>
                    </div>

                    {patterns.length > 0 && (
                      <div>
                        <p className="text-2xs text-text-disabled uppercase tracking-wider mb-2">Patterns applied</p>
                        <div className="flex flex-wrap gap-1.5">
                          {patterns.map((p, i) => (
                            <span key={i} className="text-2xs px-2 py-0.5 bg-bg-overlay border border-border rounded-full text-text-muted">
                              {p}
                            </span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
