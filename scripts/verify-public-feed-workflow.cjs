// Disposable server only: creates three agents and fetches public RSS/Hacker News.
const {chromium}=require('playwright-core');const fs=require('node:fs');const assert=require('node:assert/strict');
(async()=>{
 const base=process.env.BOARD_TEST_URL||'http://127.0.0.1:8088';assert.match(base,/:8088$/);
 const token=fs.readFileSync(process.env.BOARD_TEST_TOKEN_FILE,'utf8').trim();const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
 async function api(path){const r=await fetch(base+'/api'+path,{headers});assert.equal(r.ok,true,path);return r.json();}
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});const page=await browser.newPage({viewport:{width:1440,height:1100}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(base);await page.locator('#top-connect').click();await page.locator('#board-token').fill(token);await page.locator('#access-form button[type=submit]').click();await page.locator('#access-dialog').waitFor({state:'hidden'});
  await page.goto(base+'/#workflows');
  async function agent(name,source,skill){await page.locator('#add-to-workflow').click();await page.locator('#library-new').click();await page.locator('#agent-name').fill(name);await page.locator('#agent-purpose').fill('Read public facts and share evidence with the team.');await page.locator('[data-source="'+source+'"]').click();await page.locator('#skill-choices button').filter({hasText:skill}).click();if(source==='hackernews')await page.locator('#hn-top').fill('3');await page.locator('#agent-form button[type=submit]').click();await page.locator('#agent-dialog').waitFor({state:'hidden'});}
  await agent('RSS Scout','rss','Share supplied facts');await agent('Hacker News Scout','hackernews','Share supplied facts');await agent('Digest Writer','workflow_input','Combine');
  assert.equal(await page.locator('.canvas-node').count(),3);
  await page.getByRole('button',{name:'Connect output of RSS Scout',exact:true}).click();await page.getByRole('button',{name:'Connect input of Digest Writer',exact:true}).click();
  await page.getByRole('button',{name:'Connect output of Hacker News Scout',exact:true}).click();await page.getByRole('button',{name:'Connect input of Digest Writer',exact:true}).click();
  await page.locator('#workflow-name').fill('RSS + Hacker News digest verification');await page.locator('#prepare-run').click();await page.locator('#run-dialog').waitFor({state:'visible'});assert.equal(await page.locator('#run-source').inputValue(),'');assert.equal(await page.locator('#run-source').getAttribute('required'),null);
  await page.locator('#start-run').click();await page.locator('#run-dialog').waitFor({state:'hidden'});await page.waitForFunction(()=>location.hash.startsWith('#workflows/'));
  const id=await page.evaluate(()=>location.hash.split('/')[1]);let done;
  for(let attempt=0;attempt<90;attempt++){done=await api('/workflows/runs/'+id);if(done.status!=='running')break;await new Promise(r=>setTimeout(r,1000));}
  assert.equal(done.status,'completed',JSON.stringify(done.nodes));const byName=Object.fromEntries(done.nodes.map(n=>[n.name,n]));
  const collectors=[];for(const name of ['RSS Scout','Hacker News Scout']){const n=byName[name];const evidence=await api('/workflows/evidence/'+n.runId);const run=await api('/runs/'+n.runId);assert.equal(evidence.connectorInputs.length,1);const fetched=evidence.connectorInputs[0];assert.ok(fetched.facts.length>0,JSON.stringify(fetched));collectors.push({name,runId:run.id,startedAt:run.startedAt,finishedAt:run.finishedAt,connector:fetched.connector,collectedAt:fetched.collectedAt,summary:fetched.summary,factCount:fetched.facts.length,firstFact:fetched.facts[0]});}
  const digest=byName['Digest Writer'];const joined=await api('/runs/'+digest.runId);const input=await api('/workflows/evidence/'+digest.runId);assert.equal(input.upstream.length,2);for(const c of collectors)assert.ok(Date.parse(joined.startedAt)>=Date.parse(c.finishedAt));
  const overlap=Date.parse(collectors[0].startedAt)<Date.parse(collectors[1].finishedAt)&&Date.parse(collectors[1].startedAt)<Date.parse(collectors[0].finishedAt);assert.ok(overlap,'No observed overlap');
  await page.goto(base+'/#workflows/'+id);await page.getByRole('button',{name:'Evidence',exact:true}).first().click();await page.locator('#evidence-dialog').waitFor({state:'visible'});assert.match(await page.locator('#evidence-content').textContent(),/Fetched by rss|Fetched by hackernews/);assert.deepEqual(errors,[]);
  const report={passed:true,workflowRun:id,provider:done.provider,realPublicNetwork:true,modelCalls:0,collectors,collectorsOverlapped:overlap,digest:{runId:joined.id,startedAt:joined.startedAt,finishedAt:joined.finishedAt,received:input.upstream.map(n=>({name:n.name,runId:n.runId,postId:n.postId}))},checks:['three agents created manually through UI','RSS and HN source settings','source text optional for connector roots','public facts retained in evidence','collectors overlap','digest waits for both results','browser has no JS errors']};fs.mkdirSync('reports',{recursive:true});fs.writeFileSync('reports/public-feed-workflow-validation.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
