const { contextBridge, ipcRenderer } = require('electron')

/**
 * Exposes a minimal, safe API to the renderer process.
 * Never expose ipcRenderer directly — only wrap specific calls.
 */
contextBridge.exposeInMainWorld('electronAPI', {
  getBackendStatus: () => ipcRenderer.invoke('get-backend-status'),
  openExternal:     (url) => ipcRenderer.invoke('open-external', url),
  platform:         process.platform,
})
