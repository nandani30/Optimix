import { Component, type ReactNode, type ErrorInfo } from 'react'

interface Props {
  children: ReactNode
  fallback?: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

/**
 * React Error Boundary — catches unhandled render errors and shows
 * a friendly message instead of a blank white screen.
 *
 * Usage:
 *   <ErrorBoundary>
 *     <SomeComponent />
 *   </ErrorBoundary>
 */
export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] Uncaught error:', error, info.componentStack)
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null })
  }

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) return this.props.fallback

      return (
        <div className="h-full flex flex-col items-center justify-center gap-4 p-8 text-center bg-bg-base">
          <div className="w-14 h-14 rounded-2xl bg-red/10 border border-red/20 flex items-center justify-center text-2xl">
            💥
          </div>
          <div>
            <h2 className="text-base font-semibold text-text-primary mb-1">
              Something went wrong
            </h2>
            <p className="text-sm text-text-muted max-w-sm">
              An unexpected error occurred in this part of the application.
            </p>
            {this.state.error && (
              <pre className="mt-3 text-2xs font-mono text-red bg-red/5 border border-red/10 rounded-lg p-3 text-left max-w-sm overflow-x-auto">
                {this.state.error.message}
              </pre>
            )}
          </div>
          <button
            onClick={this.handleReset}
            className="px-4 py-2 bg-bg-raised border border-border rounded-lg text-sm text-text-secondary hover:text-text-primary hover:border-text-muted transition-colors"
          >
            Try again
          </button>
        </div>
      )
    }

    return this.props.children
  }
}
