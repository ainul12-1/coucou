const OriginalCDP = require('chrome-remote-interface');

async function inspectChat() {
    try {
        const res = await fetch('http://127.0.0.1:9334/json/list');
        const targets = await res.json();
        const target = targets.find(t => t.type === 'page' || (t.url && t.url.includes('workbench')));
        if (!target) return;
        const client = await OriginalCDP({ target: target.webSocketDebuggerUrl });
        const { Runtime } = client;
        await Runtime.enable();

        const evalRes = await Runtime.evaluate({
            expression: `
            (() => {
                // Find all textareas and look for their container
                const textareas = Array.from(document.querySelectorAll('textarea'));
                const chatArea = textareas.find(t => t.placeholder && (t.placeholder.includes('Ask') || t.placeholder.includes('Chat') || t.placeholder.includes('Send') || t.placeholder.includes('Type') || t.placeholder.includes('Ctrl+Enter') || t.placeholder.includes('Enter')));
                
                let container = chatArea ? chatArea.closest('[class*="chat"], [class*="pane"], [class*="view"]') : null;
                
                // Also look for markdown containers
                const md = Array.from(document.querySelectorAll('.rendered-markdown'));
                const mdInfos = md.map(m => {
                    let parent = m.parentElement;
                    return {
                        tag: m.tagName,
                        cls: m.className,
                        parentCls: parent ? parent.className : '',
                        text: (m.innerText || '').slice(0, 100)
                    };
                });

                // Check for transcript or log files in disk as another reliable method!
                return {
                    chatAreaPlaceholder: chatArea ? chatArea.placeholder : null,
                    mdCount: md.length,
                    mdInfos: mdInfos.slice(-5)
                };
            })()
            `,
            returnByValue: true
        });

        console.log(JSON.stringify(evalRes.result.value, null, 2));
        await client.close();
    } catch (e) {
        console.error('Error:', e.message);
    }
}

inspectChat();
