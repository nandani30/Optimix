import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore, useConnectionStore } from '../../store'
import { authApi } from '../../api'
import { clsx } from 'clsx'
import BackendStatusBar from '../shared/BackendStatusBar'

const NAV = [
  { to: '/optimizer',   icon: '⚡', label: 'Optimizer' },
  { to: '/connections', icon: '🔌', label: 'Connections' },
  { to: '/history',     icon: '📜', label: 'History' },
  { to: '/dashboard',   icon: '◈',  label: 'Dashboard' },
]

export default function AppLayout() {
  const { user, clearAuth } = useAuthStore()
  // Force sidebar re-render when user changes (important after Google login)
  const userKey = user?.email ?? 'no-user'
  const { connections, activeConnectionId } = useConnectionStore()
  const navigate = useNavigate()

  const activeConn = connections.find(c => c.connectionId === activeConnectionId)
  const initials = user?.fullName
    ? user.fullName.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
    : '?'

  const handleLogout = async () => {
    try { await authApi.logout() } catch { /* ignore */ }
    clearAuth(); navigate('/auth')
  }

  return (
    <div key={userKey} className="flex flex-col h-screen bg-bg-base overflow-hidden">
      <BackendStatusBar />

      <div className="flex flex-1 overflow-hidden">

        {/* ── Sidebar ─────────────────────────────────────────────── */}
        <aside className="w-48 flex-shrink-0 flex flex-col bg-bg-surface border-r border-border">

          {/* Logo */}
          <div className="px-4 pt-5 pb-4 flex items-center gap-2.5 border-b border-border">
            <div className="w-7 h-7 rounded-lg bg-accent/10 border border-accent/20 flex items-center justify-center text-sm flex-shrink-0">⚡</div>
            <span className="font-bold text-text-primary tracking-tight text-[15px]">Optimix</span>
          </div>

          {/* Nav */}
          <nav className="flex-1 px-2 py-3 space-y-0.5">
            {NAV.map(({ to, icon, label }) => (
              <NavLink key={to} to={to}
                className={({ isActive }) => clsx(
                  'flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm transition-all',
                  isActive
                    ? 'bg-accent/10 text-accent font-semibold'
                    : 'text-text-secondary hover:bg-bg-raised hover:text-text-primary'
                )}>
                <span className="text-sm">{icon}</span>
                {label}
              </NavLink>
            ))}
          </nav>

          {/* Active DB indicator — subtle, not intrusive */}
          {activeConn && (
            <div className="mx-3 mb-2 px-3 py-2 bg-accent/5 border border-accent/15 rounded-lg">
              <div className="flex items-center gap-1.5">
                <div className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse flex-shrink-0" />
                <span className="text-2xs text-accent font-medium truncate">{activeConn.profileName}</span>
              </div>
              <p className="text-2xs text-text-muted mt-0.5 font-mono truncate">{activeConn.databaseName}</p>
            </div>
          )}

          {/* User */}
          <div className="border-t border-border p-3">
            <div className="flex items-center gap-2 mb-2">
              {user?.profilePictureUrl ? (
                <img src={user.profilePictureUrl} alt="" className="w-7 h-7 rounded-full object-cover ring-1 ring-border flex-shrink-0" />
              ) : (
                <div className="w-7 h-7 rounded-full bg-bg-overlay border border-border flex items-center justify-center text-xs font-bold text-text-secondary flex-shrink-0">
                  {initials}
                </div>
              )}
              <div className="min-w-0">
                <p className="text-xs font-medium text-text-primary truncate">{user?.fullName}</p>
                <p className="text-2xs text-text-muted truncate">{user?.email}</p>
              </div>
            </div>
            <button onClick={handleLogout}
              className="w-full text-left text-xs text-text-muted hover:text-red transition-colors px-1 py-1 rounded">
              Sign out
            </button>
          </div>
        </aside>

        {/* ── Main ──────────────────────────────────────────────────── */}
        <main className="flex-1 min-w-0 overflow-hidden">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
