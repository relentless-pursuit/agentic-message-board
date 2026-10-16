const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const elements=new Map();
class Element{constructor(){this.children=[];this.value='';this.textContent='';this.hidden=false;this.disabled=false;}append(...children){this.children.push(...children);}replaceChildren(...children){this.children=[...children];}}
for(const id of ['dashboard-token','dashboard-message','dashboard-access','dashboard-content','current-job','job-name','job-status','stage-queued','stage-running','stage-finished','job-progress','job-details','job-error','cancel-job','job-result','job-instructions','job-usage','job-artifacts','active-count','success-count','recent-count','dashboard-jobs','connection'])elements.set(id,new Element());
const id='12345678-1234-4234-8234-123456789012';
let job={id,name:'Presentation job',status:'queued',skill:{displayName:'Presentations',version:'1'},model:'test-luna',format:'pptx',timeLimit:120,createdAt:new Date().toISOString(),inputs:['input.png'],instructions:'Create a PPT',artifacts:[]};
let requests=[],deny=false;
const context=vm.createContext({
  document:{getElementById:id=>elements.get(id),createElement:()=>new Element(),hidden:false},
  window:{location:{search:'?job='+id},history:{replaceState(){}}},URLSearchParams,encodeURIComponent,
  sessionStorage:{getItem:()=>'',setItem(){}},Date,URL,setTimeout,setInterval(){},console,
  fetch:async(url,options={})=>{requests.push({url,method:options.method||'GET'});
    if(deny)return {ok:false,status:401,json:async()=>({})};
    if(options.method==='POST'){job={...job,status:'cancelled'};return {ok:true,json:async()=>job};}
    return {ok:true,json:async()=>structuredClone(url.endsWith('/'+id)?job:[job])};
  }
});
vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/skill-dashboard.js'),'utf8'),context);
(async()=>{
  assert.equal(elements.get('dashboard-access').hidden,false,'No saved token must show the connection form');
  elements.get('dashboard-token').value='test-token';await vm.runInContext('refreshDashboard()',context);
  assert.equal(elements.get('job-status').textContent,'queued');assert.match(elements.get('job-progress').textContent,/queued/);assert.equal(elements.get('active-count').textContent,1);
  job.status='running';await vm.runInContext('refreshDashboard()',context);assert.match(elements.get('job-progress').textContent,/runner is working/);assert.equal(elements.get('cancel-job').hidden,false);
  await elements.get('cancel-job').onclick();assert.equal(job.status,'cancelled');assert.equal(elements.get('cancel-job').hidden,true);
  job.status='success';job.artifacts=[{index:0,name:'slides.pptx',bytes:100}];job.usage={inputTokens:12,cachedInputTokens:0,outputTokens:3};job.result='Created the slides.';
  await vm.runInContext('refreshDashboard()',context);assert.equal(elements.get('success-count').textContent,1);assert.match(elements.get('job-progress').textContent,/completed/);assert.match(elements.get('job-usage').textContent,/12 input/);
  assert.match(elements.get('job-artifacts').children[0].textContent,/Download slides.pptx/);assert.equal(elements.get('job-result').textContent,'Created the slides.');
  job.status='failed';job.error='Renderer failed';job.artifacts=[];await vm.runInContext('refreshDashboard()',context);assert.equal(elements.get('job-error').hidden,false);assert.equal(elements.get('job-error').textContent,'Renderer failed');
  deny=true;await vm.runInContext('refreshDashboard()',context);assert.equal(elements.get('dashboard-access').hidden,false);assert.equal(elements.get('dashboard-content').hidden,true);
  assert(requests.filter(r=>r.method==='POST').every(r=>r.url.endsWith('/cancel')),'Dashboard must never submit a model job');
  console.log('Passed: selected-run dashboard, live queued/running/completed/failed states, usage, downloads, cancellation and expired authentication. No model calls.');
})().catch(error=>{console.error(error);process.exitCode=1;});
