const { app, BrowserWindow, ipcMain, shell } = require('electron')
const path   = require('path')
const { spawn, execSync, spawnSync } = require('child_process')

const isDev = process.env.NODE_ENV === 'development'
const PORT  = 7070

let mainWindow  = null
let backendProc = null

// ── Kill any process on port 7070 before starting ─────────────────────────
function killPort() {
  try {
    if (process.platform === 'win32') {
      spawnSync('cmd', ['/c', `for /f "tokens=5" %a in ('netstat -aon ^| find ":${PORT}"') do taskkill /F /PID %a`], { shell: true })
    } else {
      spawnSync('sh', ['-c', `lsof -ti:${PORT} | xargs kill -9 2>/dev/null; true`])
    }
    // Small delay to let OS release the port
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500)
  } catch { /* ignore */ }
}

// ── Find Java ──────────────────────────────────────────────────────────────
function findJava() {
  if (process.env.JAVA_HOME) {
    return path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  }
  try {
    const result = execSync(process.platform === 'win32' ? 'where java' : 'which java', { encoding: 'utf8' })
    return result.trim().split('\n')[0]
  } catch { return 'java' }
}

function getJarPath() {
  if (isDev) {
    return path.join(__dirname, '..', '..', 'optimix-backend', 'target', 'optimix-backend-1.0.0.jar')
  }
  return path.join(process.resourcesPath, 'backend', 'optimix-backend.jar')
}

// ── Start backend ──────────────────────────────────────────────────────────
function startBackend() {
  killPort() // Always kill port before starting

  const jar  = getJarPath()
  const java = findJava()
  console.log('[backend] Starting:', java, '-jar', jar)

  backendProc = spawn(java, ['-jar', jar, '-Xms64m', '-Xmx512m'], {
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env },
  })

  backendProc.stdout.on('data', data => {
    const line = data.toString().trim()
    console.log('[backend]', line)
    if (line.includes('listening on') || line.includes('started in') || line.includes('Javalin started')) {
      console.log('[backend] ✓ Ready')
    }
  })

  backendProc.stderr.on('data', data => {
    const line = data.toString().trim()
    if (line && !line.startsWith('WARNING')) {
      console.error('[backend stderr]', line)
    }
  })

  backendProc.on('exit', (code, signal) => {
    if (code !== null && code !== 0) {
      console.log(`[backend] Exited with code ${code}. Restarting in 3s...`)
      setTimeout(startBackend, 3000)
    }
  })
}

// ── Create window ──────────────────────────────────────────────────────────
function createWindow() {
  mainWindow = new BrowserWindow({
    width:  1280,
    height: 800,
    minWidth:  900,
    minHeight: 600,
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    backgroundColor: '#09090b',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false, // Allow loading local resources in dev
    },
  })

  const url = isDev ? 'http://localhost:5173' : `file://${path.join(__dirname, '../dist/index.html')}`
  mainWindow.loadURL(url)

  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
    if (isDev) mainWindow.webContents.openDevTools({ mode: 'detach' })
  })

  mainWindow.on('closed', () => { mainWindow = null })

  // Open external links in system browser (important for Google OAuth)
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://') || url.startsWith('http://')) {
      shell.openExternal(url)
      return { action: 'deny' }
    }
    return { action: 'allow' }
  })
}

// ── App lifecycle ──────────────────────────────────────────────────────────
app.whenReady().then(() => {
  startBackend()
  // Give backend 2s head start, then open window
  setTimeout(createWindow, 2000)

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', () => {
  if (backendProc) {
    backendProc.removeAllListeners('exit')
    backendProc.kill('SIGTERM')
    backendProc = null
  }
  killPort()
})

// ── IPC handlers ───────────────────────────────────────────────────────────
ipcMain.handle('open-external', (_, url) => shell.openExternal(url))
ipcMain.handle('get-app-version', () => app.getVersion())
