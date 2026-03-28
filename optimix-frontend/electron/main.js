const { app, BrowserWindow, shell } = require('electron');
const path = require('path');

let mainWindow;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: true,
      contextIsolation: false,
      webSecurity: false // Helps prevent CORS blocks during local dev
    },
  });

  const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
  }

  // 🔴 CRITICAL FIX FOR GOOGLE OAUTH IN ELECTRON 🔴
  // Prevent Electron from throwing Google Login pop-ups into the external OS browser.
  // This forces the Google login to happen inside a native Electron modal!
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.includes('accounts.google.com') || url.includes('oauth') || url.includes('googleusercontent.com')) {
      return { 
        action: 'allow',
        overrideBrowserWindowOptions: {
          width: 500,
          height: 600,
          autoHideMenuBar: true,
          modal: true,
          parent: mainWindow,
          title: "Sign in with Google"
        }
      };
    }
    
    // For any other external links (like docs/GitHub), open in the standard OS browser
    if (url.startsWith('http')) {
      shell.openExternal(url);
      return { action: 'deny' };
    }
    
    return { action: 'allow' };
  });
}

app.whenReady().then(() => {
  createWindow();

  app.on('activate', function () {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', function () {
  if (process.platform !== 'darwin') app.quit();
});