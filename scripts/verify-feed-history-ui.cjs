// Isolated-server browser checks. Creates only a disposable non-email test fixture.
const {chromium}=require('playwright-core');const fs=require('node:fs');const assert=require('node:assert/strict');
(async()=>{
 const base=process.env.BOARD_TEST_URL||'http://127.0.0.1:8088';const token=fs.readFileSync(process.env.BOARD_TEST_TOKEN_FILE,'utf8').trim();
 const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
 async function api(path,body){const r=await fetch(base+'/api'+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body)});assert.equal(r.status<300,true);return r.json();}
 const created=await api('/workflows/agents',{name:'History verification fixture',function:'Disposable fixture for feed controls.',skill:'report'});
 const makePost=()=>api('/posts',{agent:created.agent.handle,type:'update',severity:'low',title:'Synthetic feed-history fixture',body:'Supplied test data only.',tags:['simulation'],links:[]});
 const old=await makePost();
 const browser=await chromium.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true});const page=await browser.newPage({viewport:{width:1280,height:900}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 try{
  await page.goto(base);await page.locator('#top-connect').click();await page.locator('#board-token').fill(token);await page.locator('#access-form button[type=submit]').click();await page.locator('#access-dialog').waitFor({state:'hidden'});
  await page.locator('.post-card').waitFor();assert.match(await page.locator('.post-card').textContent(),/External update · execution not recorded/);
  await page.locator('#clear-feed').click();await page.waitForFunction(()=>document.getElementById('clear-feed-preview').textContent.startsWith('1 update'));
  assert.deepEqual(await page.locator('#clear-feed-range option').allTextContents(),['Last 1 hour','Last 24 hours','Last 7 days','All time']);
  await page.locator('#clear-feed-range').selectOption('hour');await page.waitForFunction(()=>!document.getElementById('confirm-clear-feed').disabled);
  await page.locator('#clear-feed-dialog button').filter({hasText:'Cancel'}).click();assert.equal((await api('/posts')).total,1);
  await page.locator('#clear-feed').click();await page.locator('#clear-feed-range').selectOption('all');await page.waitForFunction(()=>!document.getElementById('confirm-clear-feed').disabled);
  await makePost();await page.locator('#confirm-clear-feed').click();await page.locator('#clear-feed-dialog').waitFor({state:'hidden'});await page.waitForFunction(()=>document.getElementById('notice').textContent.includes('1 updates cleared'));
  assert.equal((await api('/posts')).total,1);assert.equal((await api('/posts/'+old.id)).post.id,old.id);
  await page.locator('#clear-feed').click();await page.waitForFunction(()=>!document.getElementById('confirm-clear-feed').disabled);await page.locator('#confirm-clear-feed').click();await page.waitForFunction(()=>document.getElementById('feed-count').textContent==='0 updates');
  assert.deepEqual(errors,[]);console.log(JSON.stringify({passed:true,checks:['four time ranges','cancel preserves feed','new posts survive preview boundary','confirmed clear removes activity','evidence retained','provenance ignores spoofed tag'],pageErrors:errors}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
