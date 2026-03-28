// ── Auth ──────────────────────────────────────────────────────────────────

export interface User {
  userId: number
  email: string
  fullName: string
  profilePictureUrl?: string | null
  authMethod: 'email' | 'google'
  emailVerified: boolean
}

export interface AuthResponse {
  token: string
  user: User
}

export interface SignupRequest {
  fullName: string
  email: string
  password: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface OtpVerifyRequest {
  email: string
  otpCode: string
}

// ── Connections ───────────────────────────────────────────────────────────

export interface Connection {
  connectionId: number
  profileName: string
  host: string
  port: number
  databaseName: string
  mysqlUsername: string
  createdAt: string
  lastUsed?: string | null
}

export interface ConnectionForm {
  profileName: string
  host: string
  port: number
  databaseName: string
  mysqlUsername: string
  mysqlPassword: string
}

export interface ConnectionTestResult {
  success: boolean
  message: string
  mysqlVersion?: string
  tableCount?: number
}

// ── Optimization ──────────────────────────────────────────────────────────

export interface OptimizeRequest {
  query: string
  connectionId: number | null
}

export interface PatternApplication {
  patternId: string
  patternName: string
  tier: 'TIER1' | 'TIER2' | 'TIER3'
  problem: string
  transformation: string
  benefit: string
  estimatedSpeedup: number
  beforeSnippet: string
  afterSnippet: string
  // Added fields to match the Java backend
  impactLevel?: 'HIGH' | 'MEDIUM' | 'LOW' | string
  impactReason?: string
}

export interface IndexRecommendation {
  tableName: string
  columns: string[]
  reason: string
  createStatement: string
  estimatedImprovement: number
  // Added field to match the Java backend
  confirmed?: boolean
}

export interface PlanNode {
  operation: string
  table?: string
  condition?: string
  estimatedCost: number
  estimatedRows: number
  children?: PlanNode[]
}

export interface ExecutionPlan {
  root: PlanNode
}

export interface OptimizationResult {
  originalQuery: string
  optimizedQuery: string
  originalCost?: number
  optimizedCost?: number
  speedupFactor: number
  patternsApplied: PatternApplication[]
  indexRecommendations: IndexRecommendation[]
  originalPlan?: ExecutionPlan
  optimizedPlan?: ExecutionPlan
  joinOrderExplanation: string
  summary: string
}

// ── History ───────────────────────────────────────────────────────────────

export interface HistoryEntry {
  historyId: number
  connectionId: number | null
  originalQuery: string
  optimizedQuery: string
  originalCost?: number
  optimizedCost?: number
  speedupFactor: number
  patternsApplied: string   // JSON string from backend
  createdAt: string
}

// ── UI state ──────────────────────────────────────────────────────────────

export type AuthScreen = 'login' | 'signup' | 'otp' | 'forgot' | 'reset'
export type ResultTab  = 'query' | 'explain' | 'cost' | 'indexes' | 'plan'
export type AppPage    = 'optimizer' | 'connections' | 'history' | 'dashboard'