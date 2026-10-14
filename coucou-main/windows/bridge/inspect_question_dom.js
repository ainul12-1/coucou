const { resolveTargets } = require('./cdp_controller');
const OriginalCDP = require('chrome-remote-interface');

(async () => {
    try {
        const targets = await resolveTargets(9334, true);
        if (!targets.length) return console.log('no target');
        const client = await OriginalCDP({ target: targets[0].webSocketDebuggerUrl });
        const res = await client.Runtime.evaluate({
            expression: `
            (() => {
                // Find any radio buttons or checkboxes
                const inputs = Array.from(document.querySelectorAll('input[type="radio"], input[type="checkbox"]'));
                return {
                    radioOrCheckboxes: inputs.map(i => ({
                        type: i.type,
                        name: i.name,
                        checked: i.checked,
                        visible: i.getBoundingClientRect().width > 0,
                        parentText: i.parentElement?.textContent?.slice(0, 50),
                        parentClass: i.parentElement?.className?.slice(0, 50)
                    }))
                };
            })()
            `,
            returnByValue: true
        });
        console.log(JSON.stringify(res.result.value, null, 2));
        await client.close();
    } catch(e) {
        console.error(e.message);
    }
})();
