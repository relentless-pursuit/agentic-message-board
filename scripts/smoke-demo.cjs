'use strict';
// Browser check for the example team: start the board with  --demo  (scripts/check.sh --demo does this).
// It must show ten agents, each bound to a different reusable skill by its own YAML file, and the ten-agent workflow.
// Nothing is run, so no model request is made.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
let chromium;
for (const name of ['playwright-core', 'playwright']) { try { ({ chromium } = require(name)); break; } catch { /* next */ } }
if (!chromium) { console.error('Playwright is not installed. Run once:  npm install --no-save playwright-core'); process.exit(3); }

const base = process.env.BOARD_TEST_URL;
const out = process.env.SMOKE_OUT || path.resolve('target/smoke');
fs.mkdirSync(out, { recursive: true });
const browserPath = [process.env.CHROME_PATH, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/usr/bin/google-chrome', '/usr/bin/chromium']
  .find(p => p && fs.existsSync(p));
const problems = [];
let passed = 0;
async function step(name, fn) {
  try { await fn(); passed++; console.log('  ✓ ' + name); }
  catch (e) { console.log('  ✗ ' + name + '\n      ' + String(e.message).split('\n').slice(0, 3).join('\n      ')); throw e; }
}

(async () => {
  const browser = await chromium.launch({ executablePath: browserPath, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  page.setDefaultTimeout(8000);
  page.on('pageerror', e => problems.push('script error: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') problems.push('console error: ' + m.text()); });
  page.on('response', r => { if (r.status() >= 400 && ![401, 403].includes(r.status())) problems.push(r.status() + ' ' + r.url()); });
  const api = async p => (await page.request.get(base + '/api' + p)).json();
  try {
    await step('The ten example agents are loaded from YAML, each with its own skill', async () => {
      await page.goto(base + '/#agents');
      await page.waitForFunction(() => document.querySelectorAll('#agent-library .agent-card').length === 10);
      const agents = await api('/workflows/agents');
      const skills = (await api('/workflows/skills')).filter(s => !s.operation).map(s => s.id);
      assert.equal(agents.length, 10);
      assert.deepEqual([...new Set(agents.map(a => a.skill))].sort(), [...skills].sort(), 'every reusable skill should have one agent');
    });
    await step('The ten-agent workflow is ready: 10 agents, 10 connections', async () => {
      await page.goto(base + '/#workflows');
      await page.waitForFunction(() => [...document.querySelectorAll('#workflow-picker option')].some(o => /Ten-agent/.test(o.textContent)));
      await page.locator('#workflow-picker').selectOption({ label: 'Ten-agent engineering briefing' });
      await page.waitForFunction(() => document.querySelectorAll('.canvas-node').length === 10);
      await page.waitForFunction(() => document.querySelectorAll('.connection-chip').length === 10);
      await page.waitForFunction(() => document.querySelectorAll('#connections path').length === 10);
    });
    await step('The run dialog asks for every source the ten agents need, with nothing pre-filled', async () => {
      await page.locator('#prepare-run').click();
      await page.locator('#run-dialog').waitFor({ state: 'visible' });
      const keys = await page.locator('#run-params input').evaluateAll(els => els.map(e => e.dataset.key + '=' + e.value));
      assert.deepEqual([...keys].sort(), ['hnTop=', 'issue=', 'repository=', 'revision=', 'rssUrl=']);
      await page.locator('#run-dialog [data-close]').click();
    });
    await step('No browser errors or failed requests', async () => { assert.deepEqual(problems, []); });
    console.log('\n' + passed + ' checks passed');
    await browser.close();
  } catch (e) {
    try { await page.screenshot({ path: path.join(out, 'failure-demo.png'), fullPage: true }); } catch { /* gone */ }
    if (problems.length) console.log('  Browser problems seen:\n    ' + [...new Set(problems)].slice(0, 8).join('\n    '));
    await browser.close().catch(() => {});
    process.exit(1);
  }
})().catch(e => { console.error(e); process.exit(1); });
