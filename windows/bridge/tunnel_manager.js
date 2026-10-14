const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn, execSync } = require('child_process');
const https = require('https');

let activeTunnelProc = null;
let activeTunnelUrl = null;
let activeTunnelHost = null;
let isDownloading = false;
let downloadProgress = 0;

const BIN_DIR = path.join(__dirname, 'bin');
const LOCAL_BIN = path.join(BIN_DIR, 'cloudflared.exe');

function ensureBinDir() {
    if (!fs.existsSync(BIN_DIR)) {
        try { fs.mkdirSync(BIN_DIR, { recursive: true }); } catch (_) {}
    }
}

function findCloudflaredBinary() {
    // 1. Local bridge/bin/cloudflared.exe
    if (fs.existsSync(LOCAL_BIN)) return LOCAL_BIN;

    // 2. User Downloads folder
    const userDownloads = path.join(os.homedir(), 'Downloads', 'cloudflared.exe');
    if (fs.existsSync(userDownloads)) return userDownloads;

    // 3. AppData Coucou bin
    const appDataBin = path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local'), 'Coucou', 'bin', 'cloudflared.exe');
    if (fs.existsSync(appDataBin)) return appDataBin;

    // 4. In system PATH
    try {
        const out = execSync('where.exe cloudflared.exe', { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim().split('\r\n')[0];
        if (out && fs.existsSync(out)) return out;
    } catch (_) {}

    return null;
}

function downloadBinary() {
    return new Promise((resolve, reject) => {
        if (isDownloading) return reject(new Error('Sedang mengunduh cloudflared...'));
        ensureBinDir();
        isDownloading = true;
        downloadProgress = 0;

        const downloadUrl = 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe';

        function fetchWithRedirect(url) {
            https.get(url, (res) => {
                if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                    return fetchWithRedirect(res.headers.location);
                }
                if (res.statusCode !== 200) {
                    isDownloading = false;
                    return reject(new Error(`Download gagal: HTTP status ${res.statusCode}`));
                }

                const totalBytes = parseInt(res.headers['content-length'] || '0', 10);
                let receivedBytes = 0;
                const file = fs.createWriteStream(LOCAL_BIN);

                res.on('data', (chunk) => {
                    receivedBytes += chunk.length;
                    if (totalBytes > 0) {
                        downloadProgress = Math.round((receivedBytes / totalBytes) * 100);
                    }
                });

                res.pipe(file);

                file.on('finish', () => {
                    file.close(() => {
                        isDownloading = false;
                        downloadProgress = 100;
                        resolve(LOCAL_BIN);
                    });
                });

                file.on('error', (err) => {
                    fs.unlink(LOCAL_BIN, () => {});
                    isDownloading = false;
                    reject(err);
                });
            }).on('error', (err) => {
                isDownloading = false;
                reject(err);
            });
        }

        fetchWithRedirect(downloadUrl);
    });
}

async function startQuickTunnel(targetPort = 9335) {
    if (activeTunnelProc) {
        return { url: activeTunnelUrl, host: activeTunnelHost, running: true, isQuick: true };
    }

    let binPath = findCloudflaredBinary();
    if (!binPath) {
        console.log('[TunnelManager] cloudflared belum terpasang, mengunduh otomatis...');
        binPath = await downloadBinary();
        console.log('[TunnelManager] cloudflared berhasil diunduh ke:', binPath);
    }

    return new Promise((resolve, reject) => {
        const proc = spawn(binPath, ['tunnel', '--url', `http://127.0.0.1:${targetPort}`], {
            stdio: ['ignore', 'pipe', 'pipe']
        });

        activeTunnelProc = proc;
        let resolved = false;

        const handleData = (data) => {
            const s = data.toString();
            const match = s.match(/https:\/\/[a-z0-9-]+\.trycloudflare\.com/i);
            if (match && !resolved) {
                resolved = true;
                activeTunnelUrl = match[0];
                activeTunnelHost = activeTunnelUrl.replace(/^https?:\/\//i, '').replace(/\/+$/, '');
                console.log('[TunnelManager] Quick Tunnel Aktif:', activeTunnelUrl);
                resolve({ url: activeTunnelUrl, host: activeTunnelHost, running: true, isQuick: true });
            }
        };

        proc.stdout.on('data', handleData);
        proc.stderr.on('data', handleData);

        proc.on('close', (code) => {
            console.log('[TunnelManager] cloudflared keluar dengan code:', code);
            activeTunnelProc = null;
            activeTunnelUrl = null;
            activeTunnelHost = null;
            if (!resolved) {
                reject(new Error(`Tunnel gagal dijalankan (exit code: ${code})`));
            }
        });

        proc.on('error', (err) => {
            activeTunnelProc = null;
            if (!resolved) reject(err);
        });

        // Timeout 25 detik
        setTimeout(() => {
            if (!resolved) {
                resolved = true;
                if (!activeTunnelUrl) {
                    try { proc.kill(); } catch (_) {}
                    reject(new Error('Timeout menunggu URL trycloudflare.com'));
                }
            }
        }, 25000);
    });
}

function stopTunnel() {
    if (activeTunnelProc) {
        try { activeTunnelProc.kill(); } catch (_) {}
        activeTunnelProc = null;
    }
    activeTunnelUrl = null;
    activeTunnelHost = null;
    return { running: false };
}

function getStatus() {
    return {
        running: !!activeTunnelProc,
        url: activeTunnelUrl,
        host: activeTunnelHost,
        isDownloading,
        downloadProgress,
        hasBinary: !!findCloudflaredBinary()
    };
}

module.exports = {
    findCloudflaredBinary,
    downloadBinary,
    startQuickTunnel,
    stopTunnel,
    getStatus
};
