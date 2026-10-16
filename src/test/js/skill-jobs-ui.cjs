const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const path=require('node:path');
const elements=new Map(),redirects=[];
class Element {
  constructor(id='',tag='div'){this.id=id;this.tag=tag;this.children=[];this.value='';this.textContent='';this.files=[];this.listeners={};}
  append(...children){this.children.push(...children);if(this.tag==='select' && !this.value && children[0])this.value=children[0].value;}
  replaceChildren(...children){this.children=[];if(this.tag==='select')this.value='';this.append(...children);}
  addEventListener(name,callback){this.listeners[name]=callback;}
}
for(const id of ['job-form','job-name','instructions','starter-task','token','message','run-status','run','skill','skill-detail','format','model','seconds','runtime','model-note','catalog','builder','jobs','access','refresh','files','recent-builds'])elements.set(id,new Element(id,['skill','format','model','seconds'].includes(id)?'select':'div'));
const skill={id:'existing-skill',revision:'123456789012345',displayName:'Presentations',name:'Presentations',version:'1',origin:'OpenAI installed bundle',description:'Existing skill',availability:'Available',runnable:true,formats:['pptx'],starterTask:{name:'Engineering update',instructions:'Create a deck from supplied evidence.'}};
let availableSkills=[skill];
let jobs=[],submissions=0,poll,rejectSubmission=false;
const context=vm.createContext({
  document:{getElementById:id=>elements.get(id),createElement:tag=>new Element('',tag),hidden:false},
  sessionStorage:{getItem:()=>'',setItem:()=>{}},
  window:{location:{search:'?skill=existing-skill',assign:url=>redirects.push(url)}},encodeURIComponent,URLSearchParams,
  FormData:class {set(){} delete(){}},Date,Array,URL,console,setTimeout,
  setInterval:callback=>{poll=callback;},
  fetch:async(url,options={})=>{
    if(url.endsWith('/options'))return {ok:true,json:async()=>({skills:availableSkills,runtime:{available:true,models:[{id:'test-luna',label:'Test model'}],version:'Fake runtime',authentication:'Test',modelsFetchedAt:'today'},timeLimits:[60,120,300]})};
    if(options.method==='POST'){
      if(rejectSubmission)return {ok:false,status:409,json:async()=>({message:'Skill changed; refresh choices.'})};
      submissions++;const job={id:'one-job',name:'PPT creation',status:'queued',skill,model:'test-luna',format:'pptx',timeLimit:120,inputs:[],instructions:'Create a presentation',createdAt:new Date().toISOString(),artifacts:[]};jobs=[job];return {ok:true,json:async()=>job};
    }
    return {ok:true,json:async()=>structuredClone(jobs)};
  }
});
vm.runInContext(fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/skill-jobs.js'),'utf8'),context);
(async()=>{
  await vm.runInContext('load()',context);
  assert.equal(elements.get('run').disabled,true,'Loading skills must require an explicit model choice');
  assert.equal(submissions,0,'Loading published skills must not start a model');
  assert.equal(elements.get('job-name').value,'Engineering update');
  assert.equal(elements.get('skill').value,skill.id,'Gallery link selects the exact installed skill');
  const event={preventDefault(){}};
  await elements.get('job-form').onsubmit(event);assert.equal(submissions,0,'Missing model must prevent submission');
  elements.get('model').value='test-luna';elements.get('model').onchange();
  elements.get('instructions').value='My own project evidence';await vm.runInContext('load()',context);
  assert.equal(elements.get('instructions').value,'My own project evidence','Refresh must preserve edited instructions');
  assert.equal(elements.get('run').disabled,false,'Valid model and skill enable Run');
  rejectSubmission=true;await elements.get('job-form').onsubmit(event);assert.equal(redirects.length,0,'Rejected submissions must stay on the form');assert.match(elements.get('run-status').textContent,/Skill changed/);rejectSubmission=false;
  await elements.get('job-form').onsubmit(event);
  assert.equal(submissions,1);assert.equal(elements.get('run').disabled,true,'Queued job must keep Run disabled');
  assert.match(elements.get('run-status').textContent,/Opening your dashboard/);assert.deepEqual(redirects,['/skill-dashboard.html?job=one-job']);
  await elements.get('job-form').onsubmit(event);
  assert.equal(submissions,1,'Repeated clicks must not start duplicate jobs');
  jobs[0].status='running';await vm.runInContext('navigating=false; refreshJobs()',context);
  assert.equal(redirects.at(-1),'/skill-dashboard.html?job=one-job','An existing active runner must open its dashboard');assert.equal(elements.get('run').disabled,true);
  jobs[0].status='success';jobs[0].artifacts=[{index:0,name:'result.pptx',bytes:100}];jobs[0].usage={inputTokens:12,cachedInputTokens:0,outputTokens:3};
  await vm.runInContext('refreshJobs()',context);
  assert.equal(elements.get('run').disabled,false,'Finished job must re-enable Run');
  assert(elements.get('jobs').children[0].children.some(child=>child.textContent==='Open dashboard'));
  elements.get('job-form').listeners.invalid({target:{labels:[{textContent:'Job name'}]}});
  assert.match(elements.get('run-status').textContent,/Please complete Job name/);
  availableSkills=[{...skill,id:'replacement-skill'}];await vm.runInContext('load()',context);
  assert.equal(elements.get('skill').value,'','A removed skill must not silently switch to another skill');
  assert.equal(elements.get('run').disabled,true);assert.match(elements.get('run-status').textContent,/no longer available/);
  console.log('Passed: accepted-job redirect, existing-run redirect, rejected-submit feedback, duplicate prevention, history links and field validation. No model calls.');
})().catch(error=>{console.error(error);process.exitCode=1;});
