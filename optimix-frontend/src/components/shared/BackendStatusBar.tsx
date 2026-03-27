import { useBackendStatus } from '../../hooks/useBackendStatus'
import { clsx } from 'clsx'

export default function BackendStatusBar() {
  const { status, retry } = useBackendStatus()

  if (status === 'online') return null

  return (
    <div className={clsx(
      'flex items-center justify-between gap-3 px-4 py-2 text-xs border-b z-50',
      status === 'checking'
        ? 'bg-yellow/10 border-yellow/20 text-yellow'
        : 'bg-red/10 border-red/20 text-red'
    )}>
      <div className="flex items-center gap-2">
        {status === 'checking' ? (
          <><span className="animate-spin-slow inline-block">⟳</span><span>Starting backend…</span></>
        ) : (
          <><span>⚠</span><span>Backend is starting up, please wait a moment…</span></>
        )}
      </div>
      {status === 'offline' && (
        <button onClick={retry} className="px-2 py-0.5 border border-red/30 rounded hover:bg-red/10 transition-colors">
          Retry
        </button>
      )}
    </div>
  )
}
