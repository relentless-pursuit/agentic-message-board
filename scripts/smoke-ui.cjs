'use strict';
// Browser smoke test for the Agent Board UI. Started by scripts/check.sh against a throwaway server.
// It never starts a workflow run, so no model request is made.
//
// Environment:
//   BOARD_TEST_URL         base URL of the running board (required)
//   BOARD_TEST_TOKEN_FILE  file holding the board admin token (only when the board runs with BOARD_AUTH=token)
//   SMOKE_OUT              folder for failure screenshots (default: target/smoke)
//   CHROME_PATH            Chrome/Chromium executable (default: system Chrome if found)
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

function loadPlaywright() {
  for (const name of ['playwright-core', 'playwright']) {
    try { return require(name); } catch { /* try the next one */ }
  }
  console.error('Playwright is not installed. Run once:  npm install --no-save playwright-core');
  process.exit(3);
}
const { chromium } = loadPlaywright();

const base = process.env.BOARD_TEST_URL;
const tokenFile = process.env.BOARD_TEST_TOKEN_FILE;
const token = tokenFile && fs.existsSync(tokenFile) ? fs.readFileSync(tokenFile, 'utf8').trim() : null;
const out = process.env.SMOKE_OUT || path.resolve('target/smoke');
fs.mkdirSync(out, { recursive: true });

function browserPath() {
  const candidates = [
    process.env.CHROME_PATH,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser',
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
  ];
  return candidates.find(p => p && fs.existsSync(p));
}

const problems = [];      // browser errors seen during the whole run
let passed = 0;

async function step(name, fn) {
  try {
    await fn();
    passed++;
    console.log('  ✓ ' + name);
  } catch (e) {
    console.log('  ✗ ' + name);
    console.log('      ' + String(e.message).split('\n').slice(0, 3).join('\n      '));
    e.stepName = name;
    throw e;
  }
}

(async () => {
  const started = Date.now();
  const browser = await chromium.launch({ executablePath: browserPath(), headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.setDefaultTimeout(8000);
  page.on('pageerror', e => problems.push('script error: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') problems.push('console error: ' + m.text()); });
  page.on('response', r => {
    if (r.status() >= 400 && ![401, 403].includes(r.status())) problems.push(r.status() + ' ' + r.url());
  });
  const auth = token ? { Authorization: 'Bearer ' + token } : {};
  const api = async p => (await page.request.get(base + '/api' + p, { headers: auth })).json();

  try {
    await step('Board loads', async () => {
      await page.goto(base);
      await page.locator('#feed-filter').waitFor();
      assert.match(await page.title(), /Agent Board/);
      assert.equal(await page.locator('nav[aria-label="Workspace"] a').count(), 4);
    });

    await step('Removed controls are gone (save/restore view, save/template, example loaders)', async () => {
      for (const id of ['save-view', 'restore-view', 'save-workflow', 'copy-workflow', 'feed-example', 'workflow-example'])
        assert.equal(await page.locator('#' + id).count(), 0, '#' + id + ' is still on the page');
      assert.equal(await page.locator('#clear-view').count(), 1);
      assert.equal(await page.locator('#clear-feed').count(), 1);
    });

    await step('Removed example endpoint answers 404', async () => {
      const r = await page.request.post(base + '/api/workflows/example', { headers: auth });
      assert.ok([404, 405].includes(r.status()), 'status was ' + r.status());
    });

    const access = await api('/access');
    if (access.mode === 'open') {
      await step('Opens ready to use: no token needed on this computer', async () => {
        await page.waitForFunction(() => /no token needed/.test(document.getElementById('connection-state').textContent));
        assert.ok(await page.locator('#top-connect').isHidden(), 'the Connect button should be hidden');
      });
      await step('Other web sites and other hosts cannot use open access', async () => {
        const body = { name: 'Intruder', function: 'x', skill: 'briefing' };
        const from = async headers => (await page.request.post(base + '/api/workflows/agents', { headers: { 'Content-Type': 'application/json', ...headers }, data: body })).status();
        assert.ok([401, 403].includes(await from({ Origin: 'https://evil.example' })), 'a cross-site request was accepted');
        assert.ok([401, 403].includes(await from({ Origin: 'http://127.0.0.1:1' })), 'another local port was accepted');
        assert.ok([401, 403].includes(await from({ Authorization: 'Bearer ab_not-a-real-token-000000000' })), 'a wrong token was accepted');
        assert.ok(!(await api('/workflows/agents')).some(a => a.agent.name === 'Intruder'));
      });
    } else {
      await step('Connect with the board token', async () => {
        assert.ok(token, 'the board needs a token: set BOARD_TEST_TOKEN_FILE');
        await page.locator('#top-connect').click();
        await page.locator('#board-token').fill(token);
        await page.locator('#access-form button[type=submit]').click();
        await page.locator('#access-dialog').waitFor({ state: 'hidden' });
        await page.waitForFunction(() => document.getElementById('top-connect').textContent === 'Connected');
      });
    }

    await step('Feed: search, reset filters and the clear-feed dialog', async () => {
      await page.locator('#feed-query').fill('zzz-no-such-update');
      await page.locator('#feed-filter button[type=submit]').click();
      await page.waitForFunction(() => document.getElementById('feed-count').textContent === '0 updates');
      await page.locator('#clear-view').click();
      await page.waitForFunction(() => document.getElementById('feed-query').value === '');
      await page.locator('#clear-feed').click();
      await page.locator('#clear-feed-dialog').waitFor({ state: 'visible' });
      await page.waitForFunction(() => document.getElementById('clear-feed-preview').textContent.length > 0);
      await page.locator('#clear-feed-dialog button', { hasText: 'Cancel' }).click();
      await page.locator('#clear-feed-dialog').waitFor({ state: 'hidden' });
    });

    let skills = [];
    await step('Agents page lists the reusable skills from the server', async () => {
      await page.goto(base + '/#agents');
      await page.locator('#skill-library .agent-card').first().waitFor();
      skills = await page.locator('#skill-library .agent-card h3').allTextContents();
      const served = (await api('/workflows/skills')).filter(s => !s.operation).map(s => s.name);
      assert.deepEqual([...skills].sort(), [...served].sort(), 'page and /api/workflows/skills disagree');
      assert.ok(skills.length >= 3, 'need at least three skills to test a workflow, found ' + skills.length);
    });

    const names = ['Smoke Agent A', 'Smoke Agent B', 'Smoke Agent C'];
    let models = [];
    await step('Create agent starts with a dropdown of every reusable skill and a model choice', async () => {
      const options = await api('/workflows/options');
      models = options.models.map(m => m.id);
      assert.ok(models.length >= 2, 'expected several models, got ' + models);
      await page.locator('#new-agent').click();
      await page.locator('#agent-dialog').waitFor({ state: 'visible' });
      const listed = await page.locator('#agent-skill option').allTextContents();
      assert.deepEqual([...listed].sort(), [...skills].sort(), 'the skill dropdown should list every reusable skill');
      await page.locator('#agent-skill').selectOption({ label: skills[2] });
      await page.waitForFunction(n => document.getElementById('skill-preview').textContent.includes(n), skills[2]);
      assert.equal(await page.locator('#agent-model').inputValue(), options.model, 'the default model should be preselected');
      assert.equal(await page.locator('#agent-model option').count(), models.length);
      await page.locator('#agent-dialog [data-close]').first().click();
    });

    await step('Input sources offered when creating an agent come from the server', async () => {
      const served = (await api('/connectors')).connectors.filter(c => (c.formSupported || c.name === 'workflow_input') && !(c.events || []).length).map(c => c.label);
      await page.locator('#new-agent').click();
      const shown = await page.locator('#source-choices button').allTextContents();
      assert.deepEqual([...shown].sort(), [...served].sort(), 'page and /api/connectors disagree');
      assert.ok(shown.length >= 4, 'expected several input sources, got ' + shown);
      await page.locator('#agent-dialog [data-close]').first().click();
    });

    await step('Create three agents, each from a different skill, with different models', async () => {
      for (let i = 0; i < names.length; i++) {
        await page.locator('#new-agent').click();
        await page.locator('#agent-skill').selectOption({ label: skills[i] });
        await page.locator('#agent-name').fill(names[i]);
        await page.locator('#agent-purpose').fill('Smoke test agent ' + (i + 1));
        await page.locator('#agent-model').selectOption(models[i % models.length]);
        await page.locator('#agent-form button[type=submit]').click();
        await page.locator('#agent-dialog').waitFor({ state: 'hidden' });
        await page.waitForFunction(n => document.querySelectorAll('#agent-library .agent-card').length === n, i + 1);
      }
      const made = (await api('/workflows/agents')).filter(a => names.includes(a.agent.name));
      assert.equal(made.length, 3);
      assert.equal(new Set(made.map(a => a.skill)).size, 3, 'agents should use three different skills');
      for (let i = 0; i < names.length; i++)
        assert.equal(made.find(a => a.agent.name === names[i]).model, models[i % models.length], names[i] + ' has the wrong model');
      assert.match(await page.locator('#agent-library .agent-card').filter({ hasText: names[1] }).textContent(), new RegExp('Model: ' + models[1]));
    });

    await step('An agent can read an RSS feed through the generic input form', async () => {
      await page.locator('#new-agent').click();
      await page.locator('#agent-skill').selectOption({ label: skills[0] });
      await page.locator('#agent-name').fill('Smoke Feed Agent');
      await page.locator('#agent-purpose').fill('Read a feed');
      await page.locator('#source-choices button', { hasText: 'RSS' }).click();
      await page.locator('#source-settings textarea[data-key="feeds"]').fill('https://github.blog/changelog/feed/');
      await page.locator('#agent-form button[type=submit]').click();
      await page.locator('#agent-dialog').waitFor({ state: 'hidden' });
      const feed = (await api('/workflows/agents')).find(a => a.agent.name === 'Smoke Feed Agent');
      assert.ok(feed, 'the feed agent was not created');
      assert.deepEqual(feed.agent.connectors, ['rss']);
      assert.equal(feed.skill, (await api('/workflows/skills')).find(k => k.name === skills[0]).id);
    });

    await step('"Create agent with this skill" preselects that skill', async () => {
      await page.locator('#skill-library .agent-card').nth(1).getByRole('button', { name: 'Create agent with this skill' }).click();
      assert.equal(await page.locator('#agent-skill option:checked').textContent(), skills[1]);
      await page.locator('#agent-dialog [data-close]').first().click();
    });

    await step('Agent profile shows the skill it was built from', async () => {
      await page.locator('#agent-library .agent-card').filter({ hasText: names[0] })
        .getByRole('button', { name: 'Profile' }).click();
      await page.locator('#evidence-dialog').waitFor({ state: 'visible' });
      assert.match(await page.locator('#evidence-content').textContent(), /Reusable skill/);
      await page.locator('#evidence-dialog [data-close]').click();
    });

    await step('Build a workflow from the first agent', async () => {
      await page.locator('#agent-library .agent-card').filter({ hasText: names[0] })
        .getByRole('button', { name: 'Build with this agent' }).click();
      await page.locator('.canvas-node').first().waitFor();
      assert.equal(await page.locator('.canvas-node').count(), 1);
      assert.ok(await page.getByRole('button', { name: 'Connect ' + names[0] + ' to another agent' }).isDisabled(),
        'Connect should be disabled while the workflow has one agent');
    });

    await step('Add the other two agents', async () => {
      for (const n of names.slice(1)) {
        await page.locator('#add-to-workflow').click();
        await page.locator('#library-choices button').filter({ hasText: n }).click();
        await page.locator('#library-dialog').waitFor({ state: 'hidden' });
      }
      assert.equal(await page.locator('.canvas-node').count(), 3);
    });

    const connectTo = async (from, to) => {
      await page.getByRole('button', { name: 'Connect ' + from + ' to another agent' }).click();
      await page.locator('#connect-choices button').filter({ hasText: to }).click();
    };

    await step('Connect A → B and B → C with the Connect button', async () => {
      await connectTo(names[0], names[1]);
      await page.waitForFunction(() => document.querySelectorAll('.connection-chip').length === 1);
      await connectTo(names[1], names[2]);
      await page.waitForFunction(() => document.querySelectorAll('.connection-chip').length === 2);
      await page.waitForFunction(() => document.querySelectorAll('#connections path').length === 2);
    });

    await step('A loop (C → A) is refused', async () => {
      await page.getByRole('button', { name: 'Connect ' + names[2] + ' to another agent' }).click();
      const loop = page.locator('#connect-choices button').filter({ hasText: names[0] });
      assert.ok(await loop.isDisabled(), 'C → A should be disabled');
      await page.locator('#connect-dialog [data-close]').click();
    });

    await step('The workflow saves itself', async () => {
      await page.waitForFunction(() => document.getElementById('canvas-status').textContent === 'Saved');
      const saved = (await api('/workflows')).find(w => w.nodes.length === 3);
      assert.ok(saved, 'no saved workflow with three agents');
      assert.equal(saved.edges.length, 2);
    });

    await step('After a reload the saved workflow comes back with its connections', async () => {
      await page.reload();
      await page.goto(base + '/#workflows');
      await page.waitForFunction(() => document.querySelectorAll('#workflow-picker option').length > 1);
      await page.locator('#workflow-picker').selectOption({ index: 1 });
      await page.waitForFunction(() => document.querySelectorAll('.canvas-node').length === 3);
      await page.waitForFunction(() => document.querySelectorAll('.connection-chip').length === 2);
    });

    await step('Clicking a connected agent in Connect removes the connection', async () => {
      await connectTo(names[0], names[1]);
      await page.waitForFunction(() => document.querySelectorAll('.connection-chip').length === 1);
      await page.waitForFunction(() => document.getElementById('canvas-status').textContent === 'Saved');
    });

    await step('Run dialog opens (nothing is started)', async () => {
      await page.locator('#prepare-run').click();
      await page.locator('#run-dialog').waitFor({ state: 'visible' });
      const label = await page.locator('#run-model').textContent();
      assert.match(label, /OpenAI/);
      assert.ok(models.slice(0, 2).every(m => label.includes(m)), 'run dialog should name the models in use: ' + label);
      const keys = await page.locator('#run-params input').evaluateAll(els => els.map(e => e.dataset.key + '=' + e.value));
      assert.ok(keys.includes('repository='), 'the repository field should be asked for and start empty: ' + keys);
      assert.ok(keys.every(k => k.endsWith('=')), 'no run parameter may be pre-filled: ' + keys);
      await page.locator('#run-dialog [data-close]').click();
    });

    await step('Run history page loads', async () => {
      await page.goto(base + '/#runs');
      await page.locator('#run-list').waitFor();
      await page.waitForFunction(() => !document.getElementById('runs-page').hidden);
    });

    await step('Every page fits a phone screen (390px, no sideways scrolling)', async () => {
      await page.setViewportSize({ width: 390, height: 844 });
      for (const hash of ['feed', 'agents', 'workflows', 'runs']) {
        await page.goto(base + '/#' + hash);
        await page.waitForTimeout(400);
        const fits = await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
        assert.ok(fits, '#' + hash + ' is wider than the screen');
      }
    });

    await step('No browser errors or failed requests during the run', async () => {
      assert.deepEqual(problems, []);
    });

    console.log('\n' + passed + ' checks passed in ' + ((Date.now() - started) / 1000).toFixed(1) + 's');
    await browser.close();
  } catch (e) {
    const shot = path.join(out, 'failure.png');
    try { await page.screenshot({ path: shot, fullPage: true }); console.log('\n  Screenshot: ' + shot); } catch { /* page may be gone */ }
    if (problems.length) console.log('  Browser problems seen:\n    ' + [...new Set(problems)].slice(0, 8).join('\n    '));
    await browser.close().catch(() => {});
    process.exit(1);
  }
})().catch(e => { console.error(e); process.exit(1); });
