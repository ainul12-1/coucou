const http = require('http');
const { sendViaCDP, resolveTargets } = require('./cdp_controller');
const OriginalCDP = require('chrome-remote-interface');
const QRCode = require('qrcode');

const PORT = 9335;
const CDP_PORTS = [9334, 9333]; // 9334 = IDE, 9333 = Standalone

let activeCdpPort = null;
let currentPendingApproval = null;
let approvalSubscribers = [];

// Locate active CDP port
async function detectCdpPort() {
    for (const port of CDP_PORTS) {
        try {
            const targets = await resolveTargets(port, true);
            if (targets && targets.length > 0) {
                return port;
            }
        } catch (_) {}
    }
    return null;
}

// Background poller for pending approvals in Antigravity
async function checkPendingApproval() {
    activeCdpPort = await detectCdpPort();
    if (!activeCdpPort) {
        if (currentPendingApproval) {
            currentPendingApproval = null;
            notifySubscribers({ type: 'cleared' });
        }
        return;
    }

    try {
        const candidates = await resolveTargets(activeCdpPort, true);
        if (!candidates || candidates.length === 0) return;

        // Try primary candidate
        const target = candidates[0];
        const client = await OriginalCDP({ target: target.webSocketDebuggerUrl });
        const { Runtime } = client;
        await Runtime.enable();

        const checkRes = await Runtime.evaluate({
            expression: `
            (() => {
                const VALID_KEYWORDS = ['submit', 'allow', 'proceed', 'approve', 'confirm', 'yes', 'izinkan', 'lanjutkan'];
                const allButtons = Array.from(document.querySelectorAll('button, [role="button"]')).reverse();
                for (const btn of allButtons) {
                    const rect = btn.getBoundingClientRect();
                    if (rect.width === 0 || rect.height === 0) continue; // hidden
                    if (btn.closest('.part.activitybar, .part.sidebar, .tabs-container, .terminal, .statusbar, .editor-group-header, .monaco-editor')) continue;
                    
                    const text = (btn.textContent || '').trim().toLowerCase();
                    if (!text || text.length > 30) continue;
                    if (text.startsWith('ran') || text.startsWith('running') || text.includes('node ') || text.includes('.js') || text.includes('powershell') || text.includes('alt+') || text.includes('ctrl+')) continue;

                    const isMatch = VALID_KEYWORDS.some(kw => text === kw || text.startsWith(kw));
                    if (isMatch) {
                        let command = text;
                        try {
                            const container = btn.closest('.modal, [role="dialog"], div.border, div.p-4, div.p-3, [class*="rounded"]');
                            if (container) {
                                const descEl = container.querySelector('h1, h2, h3, p, pre, code, span');
                                if (descEl) command = descEl.textContent.trim().slice(0, 100);
                            }
                        } catch (_) {}
                        return { found: true, text, command, id: 'ag-' + text.slice(0, 15) };
                    }
                }
                return { found: false };
            })()
            `,
            returnByValue: true
        });

        await client.close();

        const val = checkRes?.result?.value;
        if (val && val.found) {
            if (activeApprovalId === val.id) return; // Currently already prompting this
            if (lastDecidedId === val.id && Date.now() - lastDecisionTime < 4000) return; // Recently decided, debounce IDE closing animation

            console.log(`[Bridge] Detected pending approval in IDE: "${val.text}" -> "${val.command}"`);
            activeApprovalId = val.id;
            currentPendingApproval = {
                id: val.id,
                tool: 'Antigravity IDE',
                command: val.command || val.text,
                pillId: 'agent_antigravity'
            };
            notifySubscribers({ type: 'pending', data: currentPendingApproval });
            triggerCoucouApproval('Antigravity IDE', currentPendingApproval.command, val.id);
        } else if (currentPendingApproval) {
            console.log('[Bridge] Pending approval cleared in IDE.');
            currentPendingApproval = null;
            activeApprovalId = null;
            notifySubscribers({ type: 'cleared' });
            cancelCoucouApproval();
        }
    } catch (_) {}
}

const { spawn, exec } = require('child_process');
const path = require('path');
const HOOK_EXE = path.join(process.env.LOCALAPPDATA || 'C:\\Users\\ainul\\AppData\\Local', 'Coucou', 'coucou-hook.exe');
let activeHookProcess = null;
let activeApprovalId = null;

function triggerCoucouApproval(tool, command, approvalId) {
    cancelCoucouApproval(); // Clean up any previous hanging process
    try {
        const reqId = 'ag-' + Date.now();
        const payload = JSON.stringify({
            request_id: reqId,
            tool_name: tool || 'Antigravity IDE',
            tool_input: { command: (command || 'Execute action').slice(0, 100) }
        });

        activeHookProcess = spawn(HOOK_EXE, ['PermissionRequest'], { stdio: ['pipe', 'pipe', 'inherit'] });
        activeHookProcess.stdin.write(payload + '\n');
        activeHookProcess.stdin.end();

        // 45s safety timeout
        const timer = setTimeout(() => {
            cancelCoucouApproval();
        }, 45000);

        let output = '';
        activeHookProcess.stdout.on('data', chunk => output += chunk);
        activeHookProcess.on('close', async (code) => {
            clearTimeout(timer);
            activeHookProcess = null;
            activeApprovalId = null;
            try {
                const res = JSON.parse(output || '{}');
                const behavior = res?.hookSpecificOutput?.decision?.behavior;
                console.log(`[Bridge] Coucou decision received: ${behavior}`);
                if (behavior === 'allow') {
                    await performDecision('allow');
                } else if (behavior === 'deny') {
                    await performDecision('deny');
                }
            } catch (_) {}
        });
    } catch (e) {
        console.error('[Bridge] Failed to trigger coucou approval:', e.message);
    }
}

function cancelCoucouApproval() {
    if (activeHookProcess) {
        try { activeHookProcess.kill(); } catch (_) {}
        activeHookProcess = null;
    }
}

function notifySubscribers(event) {
    const payload = `data: ${JSON.stringify(event)}\n\n`;
    approvalSubscribers.forEach(res => {
        try { res.write(payload); } catch (_) {}
    });
}

// Click approval / denial button
let lastDecisionTime = 0;
let lastDecidedId = null;

async function performDecision(decision) {
    if (!activeCdpPort) activeCdpPort = await detectCdpPort();
    if (!activeCdpPort) return false;

    lastDecisionTime = Date.now();
    lastDecidedId = activeApprovalId;
    console.log(`[Bridge] performDecision executing: ${decision} (activeApproval: ${activeApprovalId})`);

    try {
        const candidates = await resolveTargets(activeCdpPort, true);
        if (!candidates || candidates.length === 0) return false;

        const target = candidates[0];
        const client = await OriginalCDP({ target: target.webSocketDebuggerUrl });
        const { Runtime } = client;
        await Runtime.enable();

        if (decision === 'allow') {
            const res = await Runtime.evaluate({
                expression: `
                (async () => {
                    try {
                        function fireAll(el) {
                            if (!el) return false;
                            try { el.focus(); } catch(_) {}
                            try { el.scrollIntoView({ block: 'nearest' }); } catch(_) {}
                            const opts = { bubbles: true, cancelable: true, view: window };
                            try { el.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch(_) {}
                            try { el.dispatchEvent(new MouseEvent('mousedown', opts)); } catch(_) {}
                            try { el.dispatchEvent(new PointerEvent('pointerup', opts)); } catch(_) {}
                            try { el.dispatchEvent(new MouseEvent('mouseup', opts)); } catch(_) {}
                            el.click();
                            return true;
                        }

                        const allButtons = Array.from(document.querySelectorAll('button, [role="button"]'));

                        // 1. Check for Question Modal / Card with Submit Button
                        const submitBtn = allButtons.find(b => {
                            const rect = b.getBoundingClientRect();
                            if (rect.width === 0 || rect.height === 0) return false;
                            const txt = (b.textContent || '').trim().toLowerCase();
                            return txt.includes('submit');
                        });

                        if (submitBtn) {
                            // Find the modal / card container enclosing submitBtn (do NOT search entire document)
                            let card = submitBtn.parentElement;
                            for (let i = 0; i < 6 && card && card !== document.body; i++) {
                                if (card.children.length > 1 && card.querySelector('button')) {
                                    const nonButtons = Array.from(card.querySelectorAll('div, label, li, span')).filter(el => {
                                        const rect = el.getBoundingClientRect();
                                        return rect.width > 0 && rect.height > 0 && !el.closest('button');
                                    });
                                    if (nonButtons.length > 3) break;
                                }
                                card = card.parentElement;
                            }
                            if (!card) card = submitBtn.parentElement?.parentElement || document.body;

                            // Inside this card ONLY, look for option items
                            let optionNode = Array.from(card.querySelectorAll('*')).find(el => {
                                if (['STYLE', 'SCRIPT'].includes(el.tagName)) return false;
                                if (el.closest('button')) return false;
                                const rect = el.getBoundingClientRect();
                                if (rect.width === 0 || rect.height === 0) return false;
                                const t = (el.textContent || '').trim();
                                return (t.includes('(Recommended)') || t.includes('Recommended')) && el.children.length < 3;
                            });

                            if (!optionNode) {
                                const rows = Array.from(card.querySelectorAll('div[class*="cursor-pointer"], [role="radio"], [role="checkbox"], [role="option"], label, li')).filter(el => {
                                    if (el.closest('button')) return false;
                                    const rect = el.getBoundingClientRect();
                                    if (rect.width === 0 || rect.height === 0) return false;
                                    const t = (el.textContent || '').trim().toLowerCase();
                                    return !t.includes('submit') && !t.includes('skip') && t.length > 2;
                                });
                                if (rows.length > 0) optionNode = rows[0];
                            }

                            if (optionNode) {
                                fireAll(optionNode);
                                if (optionNode.parentElement && optionNode.parentElement !== card) {
                                    fireAll(optionNode.parentElement);
                                }
                                await new Promise(r => setTimeout(r, 200));
                            }

                            // Submit click and Enter
                            submitBtn.disabled = false;
                            submitBtn.removeAttribute('disabled');
                            submitBtn.removeAttribute('aria-disabled');
                            fireAll(submitBtn);

                            submitBtn.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
                            submitBtn.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));

                            return 'submitted_question: opt=' + (optionNode ? (optionNode.textContent || '').slice(0, 40) : 'none');
                        }

                        // 2. Regular tool approval buttons (Allow Always, Allow, Accept Changes, Accept, Proceed, Run, Confirm, Yes, etc.)
                        const APPROVE_KEYWORDS = [
                            'allow always', 'always allow', 'allow',
                            'accept changes', 'accept',
                            'proceed', 'run', 'approve', 'confirm', 'yes',
                            'izinkan', 'lanjutkan', 'save', 'apply'
                        ];

                        for (const kw of APPROVE_KEYWORDS) {
                            for (const btn of allButtons) {
                                const rect = btn.getBoundingClientRect();
                                if (rect.width === 0 || rect.height === 0) continue;
                                if (btn.closest('.part.activitybar, .part.sidebar, .tabs-container, .terminal, .statusbar')) continue;

                                const text = (btn.textContent || '').trim().toLowerCase();
                                if (!text || text.length > 40) continue;

                                if (text === kw || text.startsWith(kw + ' ') || text.startsWith(kw + '\\n') || text.includes(kw)) {
                                    fireAll(btn);
                                    return 'clicked_button: ' + text;
                                }
                            }
                        }

                        return 'no_matching_button';
                    } catch (err) {
                        return 'ERROR: ' + err.message;
                    }
                })()
                `,
                awaitPromise: true,
                returnByValue: true
            });
            console.log('[Bridge] performDecision(allow) evaluation:', res?.result?.value, res?.exceptionDetails || '');
        } else {
            // Deny: Look for Skip, Cancel, Reject, Dismiss
            const res = await Runtime.evaluate({
                expression: `
                (() => {
                    try {
                        function fireAll(el) {
                            if (!el) return false;
                            try { el.focus(); } catch(_) {}
                            const opts = { bubbles: true, cancelable: true, view: window };
                            try { el.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch(_) {}
                            try { el.dispatchEvent(new MouseEvent('mousedown', opts)); } catch(_) {}
                            try { el.dispatchEvent(new PointerEvent('pointerup', opts)); } catch(_) {}
                            try { el.dispatchEvent(new MouseEvent('mouseup', opts)); } catch(_) {}
                            el.click();
                            return true;
                        }

                        const allButtons = Array.from(document.querySelectorAll('button, [role="button"]'));

                        // Check for Skip button first
                        const skipBtn = allButtons.find(b => {
                            const rect = b.getBoundingClientRect();
                            if (rect.width === 0 || rect.height === 0) return false;
                            return (b.textContent || '').trim().toLowerCase().includes('skip');
                        });
                        if (skipBtn) {
                            fireAll(skipBtn);
                            return 'clicked_skip';
                        }

                        const DENY_KEYWORDS = ['cancel', 'deny', 'reject', 'dismiss', 'batal', 'tolak', "don't allow", 'dont allow'];
                        for (const kw of DENY_KEYWORDS) {
                            for (const btn of allButtons) {
                                const rect = btn.getBoundingClientRect();
                                if (rect.width === 0 || rect.height === 0) continue;
                                const text = (btn.textContent || '').trim().toLowerCase();
                                if (!text || text.length > 30) continue;
                                if (text === kw || text.includes(kw)) {
                                    fireAll(btn);
                                    return 'clicked_deny: ' + text;
                                }
                            }
                        }
                        return 'no_matching_deny_button';
                    } catch (err) {
                        return 'ERROR: ' + err.message;
                    }
                })()
                `,
                returnByValue: true
            });
            console.log('[Bridge] performDecision(deny) evaluation:', res?.result?.value, res?.exceptionDetails || '');
        }

        await client.close();
        currentPendingApproval = null;
        notifySubscribers({ type: 'cleared' });
        return true;
    } catch (e) {
        console.error('[Bridge] performDecision error:', e.message);
        return false;
    }
}

// Stop current task / generation in Antigravity IDE
async function stopAgent(port) {
    try {
        const candidates = await resolveTargets(port, true);
        if (!candidates || candidates.length === 0) return false;
        const target = candidates[0];
        const client = await OriginalCDP({ target: target.webSocketDebuggerUrl });
        const { Runtime } = client;
        await Runtime.enable();

        const res = await Runtime.evaluate({
            expression: `
            (() => {
                const all = Array.from(document.querySelectorAll('button, [role="button"]'));
                // 1. Check for real Stop button
                const stopBtn = all.find(b => {
                    if (b.offsetParent === null) return false;
                    const aria = (b.getAttribute('aria-label') || '').toLowerCase();
                    const text = (b.textContent || '').trim().toLowerCase();
                    const title = (b.getAttribute('title') || '').toLowerCase();
                    return aria.includes('stop') || aria.includes('cancel') || text === 'stop' || text === 'cancel' || title.includes('stop') || b.querySelector('.codicon-debug-stop, .codicon-primitive-square');
                });
                if (stopBtn) {
                    stopBtn.click();
                    return true;
                }
                // 2. Fallback: Skip / Cancel button
                const skipBtn = all.find(b => {
                    if (b.offsetParent === null) return false;
                    const t = (b.textContent || '').trim().toLowerCase();
                    return t === 'skip' || t === 'batal' || t === 'cancel';
                });
                if (skipBtn) {
                    skipBtn.click();
                    return true;
                }
                return false;
            })()
            `,
            returnByValue: true
        });

        await client.close();
        return !!res?.result?.value;
    } catch (e) {
        console.error('[Bridge] stopAgent error:', e.message);
        return false;
    }
}

const fs = require('fs');
const os = require('os');

let latestScreenshotBuffer = null;
const SCREENSHOT_PATH = path.join(os.homedir(), 'Pictures', 'antigravity_screenshot.jpg');
const DOMAIN_CONFIG_PATH = path.join(__dirname, 'domain.txt');
let publicBaseUrl = null;

function loadDomainConfig() {
    try {
        if (fs.existsSync(DOMAIN_CONFIG_PATH)) {
            const raw = fs.readFileSync(DOMAIN_CONFIG_PATH, 'utf8');
            const lines = raw.split(/\r?\n/).map(l => l.trim());
            const domainLine = lines.find(l => l && !l.startsWith('#'));
            if (domainLine) {
                let formatted = domainLine.replace(/\/+$/, '');
                if (!formatted.startsWith('http://') && !formatted.startsWith('https://')) {
                    formatted = 'https://' + formatted;
                }
                if (formatted.includes('domainkamu.com') || formatted.includes('example.com')) {
                    console.log('[Bridge] Contoh domain terdeteksi di domain.txt, menggunakan mode IP lokal.');
                    return null;
                }
                return formatted;
            }
        }
    } catch (_) {}
    return null;
}

publicBaseUrl = loadDomainConfig();

function getLocalIp() {
    try {
        const ifaces = os.networkInterfaces();
        for (const name of Object.keys(ifaces)) {
            for (const net of ifaces[name]) {
                if (net.family === 'IPv4' && !net.internal) {
                    return net.address;
                }
            }
        }
    } catch (_) {}
    return '127.0.0.1';
}

function getActiveTranscriptInfo() {
    try {
        const brainDir = path.join(os.homedir(), '.gemini', 'antigravity-ide', 'brain');
        if (!fs.existsSync(brainDir)) return null;
        const dirs = fs.readdirSync(brainDir, { withFileTypes: true })
            .filter(d => d.isDirectory())
            .map(d => {
                const p = path.join(brainDir, d.name, '.system_generated', 'logs', 'transcript.jsonl');
                return { p, mtime: fs.existsSync(p) ? fs.statSync(p).mtimeMs : 0 };
            })
            .sort((a, b) => b.mtime - a.mtime);
        
        if (dirs.length === 0 || dirs[0].mtime === 0) return null;
        const transcriptPath = dirs[0].p;
        const content = fs.readFileSync(transcriptPath, 'utf8');
        const lineCount = content.trim().split('\n').length;
        return { path: transcriptPath, lineCount };
    } catch (_) {
        return null;
    }
}

function findLatestModelResponseAfter(transcriptPath, initialLineCount) {
    if (!fs.existsSync(transcriptPath)) return null;
    try {
        const content = fs.readFileSync(transcriptPath, 'utf8');
        const lines = content.trim().split('\n');
        for (let i = lines.length - 1; i >= initialLineCount; i--) {
            try {
                const entry = JSON.parse(lines[i]);
                if (entry.source === 'MODEL' && entry.type === 'PLANNER_RESPONSE' && entry.content && entry.content.trim()) {
                    let clean = entry.content.replace(/<thought>[\s\S]*?<\/thought>/gi, '').trim();
                    if (clean) return clean;
                }
            } catch (_) {}
        }
    } catch (_) {}
    return null;
}

async function handleChatPrompt(promptText, port) {
    const cmd = (promptText || '').trim();
    const cmdLower = cmd.toLowerCase();

    if (cmdLower.startsWith('/domain')) {
        const raw = cmd.slice(7).trim();
        if (!raw) {
            return publicBaseUrl 
                ? `[DOMAIN] **Domain Cloudflare aktif saat ini:**\n\`${publicBaseUrl}\`\n\nUntuk mengganti: ketik \`/domain namadomainkamu.com\``
                : `[INFO] Belum ada domain publik yang diatur.\n\nContoh penggunaan:\n\`/domain coucou.domainkamu.com\``;
        }
        let formatted = raw;
        if (!formatted.startsWith('http://') && !formatted.startsWith('https://')) {
            formatted = 'https://' + formatted;
        }
        formatted = formatted.replace(/\/+$/, '');
        publicBaseUrl = formatted;
        try {
            fs.writeFileSync(DOMAIN_CONFIG_PATH, formatted, 'utf8');
        } catch (_) {}
        return `[OK] **Domain Cloudflare berhasil diatur!**\n\nBase URL: \`${formatted}\`\nLink Foto: \`${formatted}/screenshot.jpg\`\nCoucou Custom URL: \`${formatted}/v1\`\n\nSekarang semua link dan obrolan bisa diakses langsung dari HP kamu di luar rumah!`;
    }

    if (cmdLower === '/stop') {
        const stopped = port ? await stopAgent(port) : false;
        return stopped 
            ? '[STOP] **Proses Antigravity IDE berhasil dihentikan!**' 
            : '[INFO] Tidak ada proses aktif yang perlu dihentikan.';
    }
    if (cmdLower === '/ss' || cmdLower === '/screenshot') {
        if (!port) return '[ERROR] Antigravity IDE tidak terhubung via port 9334.';
        const b64 = await captureScreenshot(port);
        if (!b64) return '[ERROR] Gagal mengambil tangkapan layar IDE.';

        const imgBuffer = Buffer.from(b64, 'base64');
        latestScreenshotBuffer = imgBuffer;

        try {
            fs.writeFileSync(SCREENSHOT_PATH, imgBuffer);
            exec(`start "" "${SCREENSHOT_PATH}"`);
        } catch (_) {}

        const localIp = getLocalIp();
        const timestamp = Date.now();
        const urlMobile = (publicBaseUrl ? `${publicBaseUrl}/screenshot.jpg` : `http://${localIp}:${PORT}/screenshot.jpg`) + `?t=${timestamp}`;

        return `![Tangkapan Layar IDE](${urlMobile})\n\n[SCREENSHOT] Tangkapan layar Antigravity IDE (${new Date().toLocaleTimeString('id-ID')}).`;
    }
    if (['/test-approval', '/permission', '/permisi', '/test'].includes(cmdLower)) {
        triggerCoucouApproval('Antigravity IDE', 'powershell -Command "Write-Output Testing Permission Approval"', 'ag-manual-test-' + Date.now());
        return '[INFO] **Permintaan persetujuan telah dikirim ke Coucou desktop dan HP!**\n\nSilakan cek popup di desktop dan notifikasi approval di HP Anda untuk memilih Allow atau Deny.';
    }
    if (['/allow', '/yes', '/acc', '/izinkan', '/ok'].includes(cmdLower)) {
        const done = await performDecision('allow');
        return done 
            ? '[OK] **Berhasil mengizinkan aksi di Antigravity IDE!**' 
            : '[INFO] Tidak ada aksi/dialog persetujuan yang aktif.';
    }
    if (['/deny', '/reject', '/batal', '/tolak', '/no'].includes(cmdLower)) {
        const done = await performDecision('deny');
        return done 
            ? '[DENY] **Aksi di Antigravity IDE telah dibatalkan / ditolak!**' 
            : '[INFO] Tidak ada aksi/dialog persetujuan yang aktif.';
    }
    if (cmdLower === '/ping') {
        return '[PONG] Bridge aktif dan terhubung ke Antigravity IDE.';
    }
    if (['/latest', '/lastet', '/latets', '/last', '/jawab', '/jawaban', '/ans', '/terakhir'].includes(cmdLower)) {
        const info = getActiveTranscriptInfo();
        const lastAns = info && info.path ? findLatestModelResponseAfter(info.path, 0) : null;
        return lastAns 
            ? lastAns 
            : '[INFO] Belum ada respon terbaru dari Antigravity IDE.';
    }
    if (cmdLower === '/status') {
        return `[STATUS] **Status Antigravity IDE:**\n- CDP Port: ${port || 'Terputus (9334)'}\n- Bridge Port: 9335\n- Status: Siap Menerima Perintah`;
    }
    if (cmdLower === '/help') {
        return `[BANTUAN] **Daftar Perintah Bot:**\n\n` +
            `- \`/ss\` atau \`/screenshot\` : Ambil tangkapan layar IDE dan tampilkan fotonya langsung\n` +
            `- \`/latest\` atau \`/jawab\` : Ambil jawaban/respon utuh terakhir dari AI di IDE\n` +
            `- \`/stop\` : Hentikan generasi / proses agen di IDE\n` +
            `- \`/allow\` atau \`/yes\` : Izinkan & jalankan perintah persetujuan (Run/Allow)\n` +
            `- \`/deny\` atau \`/batal\` : Tolak perintah persetujuan\n` +
            `- \`/status\` : Cek status koneksi CDP & Bridge\n` +
            `- \`/ping\` : Tes koneksi cepat\n\n` +
            `*Ketik pesan biasa tanpa tanda / untuk langsung berdiskusi dengan Antigravity AI.*`;
    }

    if (!promptText || !port) {
        return '[ERROR] Antigravity IDE tidak terhubung atau pesan kosong.';
    }

    // 1. Snapshot transcript state before sending
    const initInfo = getActiveTranscriptInfo();
    const initLineCount = initInfo ? initInfo.lineCount : 0;
    const transcriptPath = initInfo ? initInfo.path : null;

    // 2. Type & send message into Antigravity IDE composer
    await sendViaCDP(promptText, port);

    // 3. Wait & poll for real AI response in transcript (up to 80 seconds)
    const MAX_WAIT_MS = 80000;
    const POLL_INTERVAL = 1000;
    const startTime = Date.now();

    while (Date.now() - startTime < MAX_WAIT_MS) {
        await new Promise(r => setTimeout(r, POLL_INTERVAL));
        if (transcriptPath) {
            const reply = findLatestModelResponseAfter(transcriptPath, initLineCount);
            if (reply) {
                return reply;
            }
        }
    }

    // If still generating or timeout reached
    return `[PROSES] **Pesan telah dikirim ke Antigravity IDE dan masih diproses.**\n\nJika proses membutuhkan waktu lebih lama, ketik \`/latest\` kapan saja untuk menarik jawaban AI begitu selesai, atau ketik \`/ss\` untuk melihat layar!`;
}

// Capture screenshot of Antigravity IDE
async function captureScreenshot(port) {
    try {
        const candidates = await resolveTargets(port, true);
        if (!candidates || candidates.length === 0) return null;
        const target = candidates[0];
        const client = await OriginalCDP({ target: target.webSocketDebuggerUrl });
        const { Page } = client;
        await Page.enable();
        const screenshot = await Page.captureScreenshot({ format: 'jpeg', quality: 70 });
        await client.close();
        return screenshot.data;
    } catch (e) {
        console.error('[Bridge] captureScreenshot error:', e.message);
        return null;
    }
}


// HTTP Server
const server = http.createServer(async (req, res) => {
    // Enable CORS for local access
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        return res.end();
    }

    const url = new URL(req.url, `http://${req.headers.host}`);

    // GET /screenshot.jpg or /screenshot
    if (url.pathname === '/screenshot.jpg' || url.pathname === '/screenshot') {
        if (!latestScreenshotBuffer && fs.existsSync(SCREENSHOT_PATH)) {
            try { latestScreenshotBuffer = fs.readFileSync(SCREENSHOT_PATH); } catch (_) {}
        }
        if (!latestScreenshotBuffer) {
            res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
            return res.end('Belum ada screenshot yang diambil. Ketik /ss di chat terlebih dahulu.');
        }
        res.writeHead(200, {
            'Content-Type': 'image/jpeg',
            'Content-Length': latestScreenshotBuffer.length,
            'Cache-Control': 'no-cache, no-store, must-revalidate'
        });
        return res.end(latestScreenshotBuffer);
    }

    // POST /upload (Photo upload from Coucou Android - supports up to 5 photos)
    if (url.pathname === '/upload' && req.method === 'POST') {
        res.setHeader('Access-Control-Allow-Origin', '*');
        res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', async () => {
            try {
                const buffer = Buffer.concat(chunks);
                const uploadDir = path.join(__dirname, 'uploads');
                if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir, { recursive: true });
                const randSuffix = Math.random().toString(36).slice(2, 7);
                const filename = `photo_${Date.now()}_${randSuffix}.jpg`;
                const filepath = path.join(uploadDir, filename);
                fs.writeFileSync(filepath, buffer);
                console.log(`[Bridge] Received uploaded photo: ${filepath} (${buffer.length} bytes)`);

                const fileUrl = `${publicBaseUrl || ('http://' + req.headers.host)}/uploads/${filename}`;
                const fileUri = `file:///${filepath.replace(/\\/g, '/')}`;

                // Respond immediately so phone does not timeout
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({
                    success: true,
                    filename,
                    path: filepath,
                    url: fileUrl,
                    fileUri: fileUri
                }));
            } catch (err) {
                console.error('[Bridge] Upload error:', err.message);
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // GET or HEAD /uploads/:filename
    if (url.pathname.startsWith('/uploads/') && (req.method === 'GET' || req.method === 'HEAD')) {
        const file = path.basename(url.pathname);
        const filepath = path.join(__dirname, 'uploads', file);
        if (fs.existsSync(filepath)) {
            const ext = path.extname(filepath).toLowerCase();
            const mime = ext === '.apk' ? 'application/vnd.android.package-archive' : 'image/jpeg';
            const stat = fs.statSync(filepath);
            res.writeHead(200, {
                'Content-Type': mime,
                'Content-Length': stat.size,
                'Content-Disposition': `attachment; filename="${file}"`,
                'Accept-Ranges': 'bytes',
                'Cache-Control': 'no-cache, no-store, must-revalidate',
                'Pragma': 'no-cache',
                'Expires': '0',
                'Access-Control-Allow-Origin': '*'
            });
            if (req.method === 'HEAD') return res.end();
            return fs.createReadStream(filepath).pipe(res);
        }
        res.writeHead(404, { 'Access-Control-Allow-Origin': '*' });
        return res.end('Not found');
    }

    // GET /settings or /settings.html (macOS Sidebar Settings UI)
    if (url.pathname === '/settings' || url.pathname === '/settings.html') {
        const settingsPath = path.join(__dirname, '..', 'dist', 'settings.html');
        if (fs.existsSync(settingsPath)) {
            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
            return fs.createReadStream(settingsPath).pipe(res);
        }
    }

    // GET /assets/:file (Serve Vite assets)
    if (url.pathname.startsWith('/assets/')) {
        const assetFile = path.basename(url.pathname);
        const assetPath = path.join(__dirname, '..', 'dist', 'assets', assetFile);
        if (fs.existsSync(assetPath)) {
            const ext = path.extname(assetPath).toLowerCase();
            const mimeTypes = {
                '.js': 'application/javascript; charset=utf-8',
                '.css': 'text/css; charset=utf-8',
                '.svg': 'image/svg+xml',
                '.png': 'image/png',
                '.wav': 'audio/wav',
                '.json': 'application/json'
            };
            res.writeHead(200, {
                'Content-Type': mimeTypes[ext] || 'application/octet-stream',
                'Access-Control-Allow-Origin': '*'
            });
            return fs.createReadStream(assetPath).pipe(res);
        }
    }

    // GET /domain (Return current configured public domain)
    if (url.pathname === '/domain' && req.method === 'GET') {
        const pubHost = publicBaseUrl ? publicBaseUrl.replace(/^https?:\/\//, '').replace(/\/.*$/, '') : null;
        res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
        return res.end(JSON.stringify({ domain: pubHost, url: publicBaseUrl }));
    }

    // GET /health
    if (url.pathname === '/health') {
        const port = await detectCdpPort();
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ status: 'ok', connected: !!port, port: port || null }));
    }

    // GET /events (Server-Sent Events for Approvals)
    if (url.pathname === '/events') {
        res.writeHead(200, {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive'
        });
        approvalSubscribers.push(res);
        if (currentPendingApproval) {
            res.write(`data: ${JSON.stringify({ type: 'pending', data: currentPendingApproval })}\n\n`);
        }
        req.on('close', () => {
            approvalSubscribers = approvalSubscribers.filter(s => s !== res);
        });
        return;
    }

    // GET /sessions (List past & current conversations from Antigravity IDE brain)
    if (url.pathname === '/sessions' && req.method === 'GET') {
        try {
            const brainDir = path.join(process.env.USERPROFILE || 'C:\\Users\\ainul', '.gemini', 'antigravity-ide', 'brain');
            if (!fs.existsSync(brainDir)) {
                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({ sessions: [] }));
            }
            const dirs = fs.readdirSync(brainDir).filter(f => {
                try { return fs.statSync(path.join(brainDir, f)).isDirectory(); } catch (_) { return false; }
            });
            const sessions = [];
            for (const d of dirs) {
                const logFile = path.join(brainDir, d, '.system_generated', 'logs', 'transcript.jsonl');
                if (!fs.existsSync(logFile)) continue;
                try {
                    const stat = fs.statSync(logFile);
                    let title = 'Percakapan ' + d.slice(0, 8);
                    let messageCount = 0;
                    const content = fs.readFileSync(logFile, 'utf8');
                    const lines = content.trim().split('\n');
                    for (const l of lines) {
                        try {
                            const entry = JSON.parse(l);
                            if (entry.type === 'USER_INPUT') {
                                messageCount++;
                                if (title.startsWith('Percakapan ')) {
                                    const match = entry.content.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/);
                                    const raw = match ? match[1].trim() : entry.content.trim();
                                    if (raw) title = raw.split('\n')[0].slice(0, 60);
                                }
                            } else if (entry.type === 'PLANNER_RESPONSE' && entry.content) {
                                messageCount++;
                            }
                        } catch (_) {}
                    }
                    sessions.push({
                        id: d,
                        title,
                        messageCount,
                        updatedAt: stat.mtimeMs,
                        dateStr: new Date(stat.mtimeMs).toLocaleString('id-ID', { dateStyle: 'short', timeStyle: 'short' })
                    });
                } catch (_) {}
            }
            sessions.sort((a, b) => b.updatedAt - a.updatedAt);
            res.writeHead(200, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({ sessions: sessions.slice(0, 50) }));
        } catch (err) {
            console.error('[Bridge] /sessions error:', err.message);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({ error: err.message }));
        }
    }

    // GET /sessions/:id/messages (Get messages for a specific session)
    if (url.pathname.startsWith('/sessions/') && url.pathname.endsWith('/messages') && req.method === 'GET') {
        try {
            const parts = url.pathname.split('/');
            const sessionId = parts[2];
            const logFile = path.join(process.env.USERPROFILE || 'C:\\Users\\ainul', '.gemini', 'antigravity-ide', 'brain', sessionId, '.system_generated', 'logs', 'transcript.jsonl');
            if (!fs.existsSync(logFile)) {
                res.writeHead(404, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({ error: 'Session not found' }));
            }
            const content = fs.readFileSync(logFile, 'utf8');
            const lines = content.trim().split('\n');
            const messages = [];
            let idx = 0;
            for (const l of lines) {
                try {
                    const e = JSON.parse(l);
                    if (e.type === 'USER_INPUT') {
                        const match = e.content.match(/<USER_REQUEST>([\s\S]*?)<\/USER_REQUEST>/);
                        const text = match ? match[1].trim() : e.content.trim();
                        if (text) {
                            messages.push({ id: `msg-${idx++}`, role: 'user', text });
                        }
                    } else if (e.type === 'PLANNER_RESPONSE' && e.content) {
                        messages.push({ id: `msg-${idx++}`, role: 'assistant', text: e.content.trim() });
                    }
                } catch (_) {}
            }
            res.writeHead(200, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({ sessionId, messages: messages.slice(-40) }));
        } catch (err) {
            console.error('[Bridge] /sessions/:id/messages error:', err.message);
            res.writeHead(500, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({ error: err.message }));
        }
    }

    // POST /sessions/new (Start a new conversation in Antigravity)
    if (url.pathname === '/sessions/new' && req.method === 'POST') {
        try {
            const port = await detectCdpPort();
            if (port) {
                // Focus chat input or send marker
                await handleChatPrompt('/new', port);
            }
            res.writeHead(200, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({ success: true, message: 'Percakapan baru siap.' }));
        } catch (err) {
            res.writeHead(500, { 'Content-Type': 'application/json' });
            return res.end(JSON.stringify({ error: err.message }));
        }
    }

    // GET /models or /v1/models (OpenAI-compatible model list for Coucou and Phone)
    if (url.pathname === '/models' || url.pathname === '/v1/models') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({
            object: 'list',
            data: [
                { id: 'antigravity-ide', object: 'model', created: Date.now(), owned_by: 'antigravity' }
            ]
        }));
    }

    // POST /chat/completions or /v1/chat/completions (OpenAI-compatible chat for Coucou and Phone)
    if ((url.pathname === '/chat/completions' || url.pathname === '/v1/chat/completions') && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const data = JSON.parse(body || '{}');
                const messages = data.messages || [];
                const lastUserMsg = [...messages].reverse().find(m => m.role === 'user');
                const promptText = lastUserMsg ? lastUserMsg.content : (data.prompt || '');
                const port = await detectCdpPort();

                const replyText = await handleChatPrompt(promptText, port);
                const isStream = data.stream === true;

                if (isStream) {
                    res.writeHead(200, {
                        'Content-Type': 'text/event-stream',
                        'Cache-Control': 'no-cache',
                        'Connection': 'keep-alive'
                    });

                    const chunk = {
                        id: 'chatcmpl-' + Date.now(),
                        object: 'chat.completion.chunk',
                        created: Math.floor(Date.now() / 1000),
                        model: 'antigravity-ide',
                        choices: [{
                            index: 0,
                            delta: { content: replyText },
                            finish_reason: null
                        }]
                    };
                    res.write(`data: ${JSON.stringify(chunk)}\n\n`);

                    const doneChunk = {
                        id: 'chatcmpl-' + Date.now(),
                        object: 'chat.completion.chunk',
                        created: Math.floor(Date.now() / 1000),
                        model: 'antigravity-ide',
                        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }]
                    };
                    res.write(`data: ${JSON.stringify(doneChunk)}\n\n`);
                    res.write('data: [DONE]\n\n');
                    res.end();
                } else {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({
                        id: 'chatcmpl-' + Date.now(),
                        object: 'chat.completion',
                        created: Math.floor(Date.now() / 1000),
                        model: 'antigravity-ide',
                        choices: [{
                            index: 0,
                            message: { role: 'assistant', content: replyText },
                            finish_reason: 'stop'
                        }]
                    }));
                }
            } catch (e) {
                console.error('[Bridge] chat/completions error:', e.message);
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: e.message }));
            }
        });
        return;
    }

    // GET /pending (poll current pending approval)
    if (url.pathname === '/pending') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        return res.end(JSON.stringify({ pending: currentPendingApproval }));
    }

    // POST /chat (Send text prompt to Antigravity IDE)
    if (url.pathname === '/chat' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const data = JSON.parse(body || '{}');
                const text = data.text || '';
                if (!text) {
                    res.writeHead(400, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: 'Missing text' }));
                }

                const port = await detectCdpPort();
                if (!port) {
                    res.writeHead(503, { 'Content-Type': 'application/json' });
                    return res.end(JSON.stringify({ error: 'Antigravity IDE is not running with --remote-debugging-port' }));
                }

                const replyText = await handleChatPrompt(text, port);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({ success: true, text: replyText, message: replyText }));
            } catch (err) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // POST /decide (Allow or Deny)
    if (url.pathname === '/decide' && req.method === 'POST') {
        let body = '';
        req.on('data', chunk => body += chunk);
        req.on('end', async () => {
            try {
                const data = JSON.parse(body || '{}');
                const decision = data.decision || 'allow';
                const success = await performDecision(decision);
                res.writeHead(200, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({ success }));
            } catch (err) {
                res.writeHead(500, { 'Content-Type': 'application/json' });
                return res.end(JSON.stringify({ error: err.message }));
            }
        });
        return;
    }

    // GET / or /pair (Web Pairing Dashboard with 2 QR Codes: Local & Public)
    if ((url.pathname === '/' || url.pathname === '/pair') && req.method === 'GET') {
        const localIp = getLocalIp();
        const fp = url.searchParams.get('fp') || 'a'.repeat(64);
        const token = url.searchParams.get('token') || ('tok_' + Buffer.from(os.hostname()).toString('hex').slice(0, 16) + '00000000');
        const name = encodeURIComponent(os.hostname() || 'Coucou PC');

        const localLink = `coucou://pair?v=1&host=${localIp}&port=47821&fp=${fp}&token=${token}&name=${name}`;
        let pubHost = null;
        let publicLink = null;
        if (publicBaseUrl) {
            pubHost = publicBaseUrl.replace(/^https?:\/\//, '').replace(/\/.*$/, '');
            publicLink = `coucou://pair?v=1&host=${pubHost}&port=443&fp=${fp}&token=${token}&name=${name}`;
        }

        try {
            const localQrSvg = await QRCode.toString(localLink, { type: 'svg', margin: 1, width: 220, color: { dark: '#ffffff', light: '#0f172a' } });
            const publicQrSvg = publicLink ? await QRCode.toString(publicLink, { type: 'svg', margin: 1, width: 220, color: { dark: '#ffffff', light: '#0f172a' } }) : null;

            const html = `<!DOCTYPE html>
<html lang="id">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Coucou Bridge - Pairing & Status</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: #0b0c0e; color: #f1f5f9; padding: 24px; min-height: 100vh; display: flex; flex-direction: column; align-items: center; }
        .container { max-width: 900px; width: 100%; }
        .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; border-bottom: 1px solid #1e293b; padding-bottom: 16px; flex-wrap: wrap; gap: 12px; }
        .header h1 { font-size: 22px; font-weight: 700; color: #38bdf8; }
        .status-badge { background: #064e3b; color: #34d399; padding: 6px 12px; border-radius: 9999px; font-size: 13px; font-weight: 600; border: 1px solid #059669; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 24px; margin-bottom: 32px; }
        .card { background: #131722; border: 1px solid #1e293b; border-radius: 12px; padding: 24px; display: flex; flex-direction: column; align-items: center; text-align: center; }
        .card-tag { font-size: 12px; font-weight: 700; padding: 4px 10px; border-radius: 6px; text-transform: uppercase; margin-bottom: 12px; }
        .tag-local { background: #1e3a8a; color: #60a5fa; border: 1px solid #2563eb; }
        .tag-public { background: #701a75; color: #f472b6; border: 1px solid #db2777; }
        .card h2 { font-size: 18px; margin-bottom: 8px; color: #ffffff; }
        .card p { font-size: 13px; color: #94a3b8; line-height: 1.5; margin-bottom: 16px; min-height: 40px; }
        .qr-box { background: #0f172a; padding: 12px; border-radius: 10px; border: 1px solid #334155; margin-bottom: 16px; width: 220px; height: 220px; display: flex; align-items: center; justify-content: center; }
        .qr-box svg { width: 100%; height: 100%; display: block; border-radius: 6px; }
        .link-input { width: 100%; background: #090d16; border: 1px solid #334155; color: #cbd5e1; padding: 8px 12px; font-size: 11px; border-radius: 6px; margin-bottom: 12px; text-overflow: ellipsis; }
        .btn { background: #2563eb; color: #ffffff; border: none; padding: 8px 16px; border-radius: 6px; font-size: 13px; font-weight: 600; cursor: pointer; transition: 0.2s; width: 100%; }
        .btn:hover { background: #1d4ed8; }
        .btn-secondary { background: #334155; color: #f8fafc; }
        .btn-secondary:hover { background: #475569; }
        .download-box { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 20px; text-align: center; margin-bottom: 24px; }
        .download-btn { display: inline-block; background: #10b981; color: #ffffff; padding: 10px 20px; border-radius: 8px; text-decoration: none; font-weight: 700; font-size: 14px; margin-top: 10px; }
        .download-btn:hover { background: #059669; }
        .info-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; font-size: 12px; color: #94a3b8; margin-top: 16px; }
        .info-item { background: #131722; padding: 10px; border-radius: 6px; border: 1px solid #1e293b; }
        .info-item strong { color: #f1f5f9; display: block; margin-bottom: 2px; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div>
                <h1>Coucou Bridge - Pairing Hub</h1>
                <p style="font-size: 13px; color: #94a3b8; margin-top: 4px;">Pilih metode pairing di bawah ini untuk menghubungkan aplikasi Coucou Android ke PC.</p>
            </div>
            <div class="status-badge">[STATUS] Bridge Siap (Port 9335)</div>
        </div>

        <div class="grid">
            <!-- Kartu 1: Lokal -->
            <div class="card">
                <span class="card-tag tag-local">Metode 1: Wi-Fi Lokal</span>
                <h2>Pairing Wi-Fi Rumah</h2>
                <p>Gunakan QR ini jika HP dan Laptop Anda terhubung ke jaringan Wi-Fi lokal yang sama.</p>
                <div class="qr-box">${localQrSvg}</div>
                <input class="link-input" type="text" readonly value="${localLink}" onclick="this.select()">
                <button class="btn btn-secondary" onclick="navigator.clipboard.writeText('${localLink}'); this.innerText='[TERSALIN] Tautan Dicopy!'; setTimeout(()=>this.innerText='Salin Tautan Lokal', 2000)">Salin Tautan Lokal</button>
            </div>

            <!-- Kartu 2: Publik -->
            <div class="card">
                <span class="card-tag tag-public">Metode 2: Cloudflare Publik</span>
                <h2>Pairing Di Luar Rumah</h2>
                <p>Gunakan QR ini jika Anda ingin mengakses Coucou dari luar rumah menggunakan paket data HP.</p>
                <div class="qr-box">
                    ${publicQrSvg ? publicQrSvg : '<div style="color:#64748b; font-size:12px; padding:12px;">Domain publik belum diset.<br><br>Atur domain di <b>domain.txt</b> atau ketik <code>/domain namadomain.com</code> di chat.</div>'}
                </div>
                <input class="link-input" type="text" readonly value="${publicLink || 'Domain publik belum diatur'}" onclick="this.select()">
                <button class="btn ${publicLink ? '' : 'btn-secondary'}" ${publicLink ? '' : 'disabled'} onclick="navigator.clipboard.writeText('${publicLink || ''}'); this.innerText='[TERSALIN] Tautan Dicopy!'; setTimeout(()=>this.innerText='Salin Tautan Publik', 2000)">Salin Tautan Publik</button>
            </div>
        </div>

        <div class="download-box">
            <h3 style="font-size: 16px; margin-bottom: 6px;">Unduh Aplikasi Coucou Android</h3>
            <p style="font-size: 13px; color: #94a3b8;">APK versi terbaru sudah siap dengan fitur pengambilan foto kamera langsung dan screenshot HD.</p>
            <a class="download-btn" href="/uploads/coucou-v3.apk" download>[UNDUH APK] Coucou Android (Versi Terbaru)</a>
        </div>

        <div class="info-grid">
            <div class="info-item">
                <strong>Alamat Lokal IP:</strong>
                <span>http://${localIp}:9335</span>
            </div>
            <div class="info-item">
                <strong>Domain Cloudflare:</strong>
                <span>${publicBaseUrl || 'Belum diatur'}</span>
            </div>
            <div class="info-item">
                <strong>Port CDP IDE:</strong>
                <span>9334 (Chrome DevTools)</span>
            </div>
        </div>
    </div>
</body>
</html>`;

            res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
            return res.end(html);
        } catch (e) {
            res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
            return res.end('Error generating pairing dashboard: ' + e.message);
        }
    }

    res.writeHead(404);
    res.end();
});

server.listen(PORT, '0.0.0.0', () => {
    const localIp = getLocalIp();
    console.log(`[Coucou Antigravity Bridge] listening on port ${PORT}`);
    console.log(`[Coucou Antigravity Bridge] Akses Lokal: http://${localIp}:${PORT}`);
    if (publicBaseUrl) {
        console.log(`[Coucou Antigravity Bridge] Akses Publik (Cloudflare): ${publicBaseUrl}`);
    } else {
        console.log(`[Coucou Antigravity Bridge] Mode Publik belum diset (lihat domain.example.txt atau ketik /domain di chat)`);
    }
    // Start approval poller
    setInterval(checkPendingApproval, 1500);
});
