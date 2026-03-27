import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuthStore, useHistoryStore, useConnectionStore, useOptimizerStore } from '../store'
import { historyApi } from '../api'
import type { HistoryEntry } from '../types'

export default function DashboardPage() {
  const { user }          = useAuthStore()
  const { entries, setEntries } = useHistoryStore()
  const { connections }   = useConnectionStore()
  const navigate          = useNavigate()

  useEffect(() => {
  historyApi.list()
    .then((data) => setEntries(data))
    .catch(() => {})
}, [])

  const total       = entries.length
  const avgSpeedup  = total ? entries.reduce((s,e) => s+e.speedupFactor,0)/total : 0
  const bestSpeedup = total ? Math.max(...entries.map(e=>e.speedupFactor)) : 0
  const firstName   = user?.fullName?.split(' ')[0] ?? 'there'
  const hour        = new Date().getHours()
  const greeting    = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening'
  const recent      = entries.slice(0,6)

  return (
    <div className="h-full overflow-y-auto bg-bg-base">
      <div className="max-w-2xl mx-auto px-6 py-6">

        <div className="mb-6">
          <h1 className="text-xl font-bold text-text-primary">{greeting}, {firstName} 👋</h1>
          <p className="text-sm text-text-muted mt-0.5">Here's your Optimix activity</p>
        </div>

        {/* Stats */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
          {[
            { label: 'Queries optimized', value: total,                        icon: '⚡', color: 'text-accent' },
            { label: 'Average speedup',   value: total ? `${avgSpeedup.toFixed(1)}×` : '—', icon: '📈', color: 'text-blue' },
            { label: 'Best result',       value: total ? `${bestSpeedup.toFixed(1)}×` : '—', icon: '🏆', color: 'text-yellow' },
            { label: 'Connections',       value: connections.length,           icon: '🔌', color: 'text-purple' },
          ].map(s => (
            <div key={s.label} className="bg-bg-surface border border-border rounded-xl p-4">
              <div className="flex items-center justify-between mb-1">
                <span>{s.icon}</span>
                <span className={`text-xl font-bold font-mono ${s.color}`}>{s.value}</span>
              </div>
              <p className="text-xs text-text-muted">{s.label}</p>
            </div>
          ))}
        </div>

        {/* Quick actions */}
        <div className="grid grid-cols-2 gap-3 mb-6">
          <button onClick={() => navigate('/optimizer')}
            className="flex items-center gap-3 p-4 bg-bg-surface border border-border rounded-xl hover:border-accent transition-colors text-left group">
            <span className="text-2xl group-hover:scale-110 transition-transform">⚡</span>
            <div>
              <p className="text-sm font-semibold text-text-primary">Optimize a Query</p>
              <p className="text-xs text-text-muted">Paste any MySQL SELECT</p>
            </div>
          </button>
          <button onClick={() => navigate('/connections')}
            className="flex items-center gap-3 p-4 bg-bg-surface border border-border rounded-xl hover:border-blue transition-colors text-left group">
            <span className="text-2xl group-hover:scale-110 transition-transform">🔌</span>
            <div>
              <p className="text-sm font-semibold text-text-primary">
                {connections.length === 0 ? 'Add a Database' : `${connections.length} Connection${connections.length>1?'s':''}`}
              </p>
              <p className="text-xs text-text-muted">Connect to MySQL for real stats</p>
            </div>
          </button>
        </div>

        {/* History */}
        {recent.length > 0 ? (
          <>
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-xs font-semibold text-text-muted uppercase tracking-wider">Recent optimizations</h2>
              <button onClick={() => navigate('/history')} className="text-xs text-accent hover:underline">View all →</button>
            </div>
            <div className="space-y-1.5">
              {recent.map(e => <HistoryRow key={e.historyId} entry={e}/>)}
            </div>
          </>
        ) : (
          <div className="flex flex-col items-center py-14 text-center border border-dashed border-border rounded-xl">
            <span className="text-5xl mb-3 opacity-20">⚡</span>
            <p className="text-sm font-medium text-text-muted">No optimizations yet</p>
            <p className="text-xs text-text-disabled mt-1 mb-5">Click an example in the Optimizer to get started</p>
            <button onClick={() => navigate('/optimizer')}
              className="px-5 py-2 bg-accent hover:bg-accent/90 text-black text-sm font-semibold rounded-lg transition-colors">
              Go to Optimizer →
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

function HistoryRow({ entry }: { entry: HistoryEntry }) {
  const navigate = useNavigate()
  const { setInputQuery, reset } = useOptimizerStore()
  const speedup = entry.speedupFactor
  const color = speedup >= 10 ? 'text-accent' : speedup >= 3 ? 'text-yellow' : 'text-text-secondary'

  return (
    <button onClick={() => { setInputQuery(entry.originalQuery); reset(); navigate('/optimizer') }}
      className="w-full flex items-center gap-3 p-3 bg-bg-surface border border-border rounded-xl hover:bg-bg-raised text-left transition-colors">
      <span className={`text-sm font-mono font-bold w-12 flex-shrink-0 ${color}`}>{speedup ? speedup.toFixed(1) : '—'}×</span>
      <p className="flex-1 text-xs font-mono text-text-secondary truncate">{entry.originalQuery.replace(/\s+/g,' ').trim()}</p>
      <span className="text-2xs text-text-disabled flex-shrink-0">{new Date(entry.createdAt).toLocaleDateString()}</span>
    </button>
  )
}
