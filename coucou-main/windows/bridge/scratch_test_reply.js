const fs = require('fs');
const path = require('path');
const os = require('os');

function getActiveTranscriptInfo() {
    const brainDir = path.join(os.homedir(), '.gemini', 'antigravity-ide', 'brain');
    if (!fs.existsSync(brainDir)) return null;
    const dirs = fs.readdirSync(brainDir, { withFileTypes: true })
        .filter(d => d.isDirectory())
        .map(d => {
            const p = path.join(brainDir, d.name, '.system_generated', 'logs', 'transcript.jsonl');
            return { p, mtime: fs.existsSync(p) ? fs.statSync(p).mtimeMs : 0 };
        })
        .sort((a,b) => b.mtime - a.mtime);
    
    if (dirs.length === 0 || dirs[0].mtime === 0) return null;
    const transcriptPath = dirs[0].p;
    const lines = fs.readFileSync(transcriptPath, 'utf8').trim().split('\n');
    return {
        path: transcriptPath,
        lineCount: lines.length
    };
}

function findLatestModelResponseAfter(transcriptPath, initialLineCount) {
    if (!fs.existsSync(transcriptPath)) return null;
    const lines = fs.readFileSync(transcriptPath, 'utf8').trim().split('\n');
    for (let i = lines.length - 1; i >= initialLineCount; i--) {
        try {
            const entry = JSON.parse(lines[i]);
            if (entry.source === 'MODEL' && entry.type === 'PLANNER_RESPONSE' && entry.content && entry.content.trim()) {
                let clean = entry.content.replace(/<thought>[\s\S]*?<\/thought>/gi, '').trim();
                if (clean) return clean;
            }
        } catch (_) {}
    }
    return null;
}

const info = getActiveTranscriptInfo();
console.log('Active info:', info);
const res = findLatestModelResponseAfter(info.path, info.lineCount - 100);
console.log('Found:', res ? res.slice(0, 100) : 'none');
