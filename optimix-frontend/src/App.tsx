import { HashRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useAuthStore } from './store'
import ErrorBoundary from './components/shared/ErrorBoundary'
import AppLayout from './components/layout/AppLayout'
import AuthPage from './pages/AuthPage'
import OptimizerPage from './pages/OptimizerPage'
import ConnectionsPage from './pages/ConnectionsPage'
import HistoryPage from './pages/HistoryPage'
import DashboardPage from './pages/DashboardPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)
  return isAuthenticated ? <>{children}</> : <Navigate to="/auth" replace />
}

export default function App() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated)

  return (
    <ErrorBoundary>
      <HashRouter>
        <Routes>
          {/* Public */}
          <Route
            path="/auth"
            element={isAuthenticated ? <Navigate to="/optimizer" replace /> : <AuthPage />}
          />

          {/* Protected — inside AppLayout (sidebar) */}
          <Route
            path="/"
            element={
              <RequireAuth>
                <AppLayout />
              </RequireAuth>
            }
          >
            <Route index element={<Navigate to="/optimizer" replace />} />
            <Route path="optimizer"   element={<ErrorBoundary><OptimizerPage /></ErrorBoundary>} />
            <Route path="connections" element={<ErrorBoundary><ConnectionsPage /></ErrorBoundary>} />
            <Route path="history"     element={<ErrorBoundary><HistoryPage /></ErrorBoundary>} />
            <Route path="dashboard"   element={<ErrorBoundary><DashboardPage /></ErrorBoundary>} />
          </Route>

          {/* Catch-all */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </HashRouter>
    </ErrorBoundary>
  )
}
