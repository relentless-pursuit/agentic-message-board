'use strict';
const byId=id=>document.getElementById(id);
const activeStatuses=['queued','running'];
let selectedId=new URLSearchParams(window.location.search).get('job'),connected=false,refreshing=false,cancelling=false,currentJob=null;
byId('dashboard-token').value=sessionStorage.getItem('boardAdminToken')||'';
function notify(text){byId('dashboard-message').textContent=text;}
async function request(path,options={}){
  const response=await fetch('/api/skill-jobs'+path,{...options,headers:{...options.headers,Authorization:'Bearer '+byId('dashboard-token').value}});
  if(!response.ok){let detail='Request failed ('+response.status+')';try{const body=await response.json();detail=body.error||body.message||detail;}catch(_){}
    if(response.status===401 || response.status===403){connected=false;byId('dashboard-access').hidden=false;byId('dashboard-content').hidden=true;detail='Connect with your board admin token to view this dashboard.';}
    throw new Error(detail);
  }return response.json();
}
function field(list,label,value){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=value;list.append(dt,dd);}
function isJobId(id){return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(id||'');}
async function downloadArtifact(job,file){
  const response=await fetch('/api/skill-jobs/'+encodeURIComponent(job.id)+'/artifacts/'+file.index,{headers:{Authorization:'Bearer '+byId('dashboard-token').value}});
  if(!response.ok)throw new Error('Download failed ('+response.status+').');
  const url=URL.createObjectURL(await response.blob()),link=document.createElement('a');link.href=url;link.download=file.name.split(/[\\/]/).pop();link.click();setTimeout(()=>URL.revokeObjectURL(url),10000);
}
function renderJob(job){
  currentJob=job;byId('current-job').hidden=false;byId('job-name').textContent=job.name;
  const status=byId('job-status');status.textContent=job.status;status.className='job-status '+(['queued','running','success','failed','timed_out','cancelled','interrupted'].includes(job.status)?job.status:'');
  const active=activeStatuses.includes(job.status);
  byId('stage-queued').className=job.status==='queued'?'current':'complete';
  byId('stage-running').className=job.status==='running'?'current':job.status==='success'?'complete':'';
  byId('stage-finished').className=active?'':job.status==='success'?'complete':'current';
  byId('stage-finished').textContent=active?'Finished':job.status==='success'?'Completed':job.status.replaceAll('_',' ');
  byId('job-progress').textContent=job.status==='queued'?'Your job is queued. This dashboard will update when the runner starts.':job.status==='running'?'The runner is working on your task. Results will appear here when it finishes.':job.status==='success'?'Your job completed. Download its deliverables below.':'This run ended with status: '+job.status.replaceAll('_',' ')+'.';
  const details=byId('job-details');details.replaceChildren();field(details,'Skill',job.skill.displayName+' · '+job.skill.version);field(details,'Model',job.model);field(details,'Output',job.format.toUpperCase());field(details,'Time limit',job.timeLimit+' seconds');field(details,'Submitted',new Date(job.createdAt).toLocaleString());if(job.finishedAt)field(details,'Finished',new Date(job.finishedAt).toLocaleString());field(details,'Inputs',job.inputs.join(', ')||'None');field(details,'Run ID',job.id);
  byId('job-error').hidden=!job.error;byId('job-error').textContent=job.error||'';byId('cancel-job').hidden=!active;byId('cancel-job').disabled=cancelling;
  byId('job-result').textContent=job.result||(active?'Waiting for the runner’s final result…':'No final result was reported.');byId('job-instructions').textContent=job.instructions;
  byId('job-usage').textContent=job.usage?job.usage.inputTokens+' input · '+job.usage.cachedInputTokens+' cached input · '+job.usage.outputTokens+' output tokens':active?'Token usage will appear when reported by Codex.':'Token usage was not reported for this run.';
  const artifacts=byId('job-artifacts');artifacts.replaceChildren();
  for(const file of job.artifacts){const button=document.createElement('button');button.className='button';button.textContent='Download '+file.name+' ('+file.bytes+' bytes)';button.onclick=()=>downloadArtifact(job,file).catch(e=>notify(e.message));artifacts.append(button);}
  if(!job.artifacts.length){const empty=document.createElement('p');empty.textContent=active?'Waiting for deliverables…':'No downloadable files were produced.';artifacts.append(empty);}
}
function renderHistory(jobs){
  byId('active-count').textContent=jobs.filter(j=>activeStatuses.includes(j.status)).length;byId('success-count').textContent=jobs.filter(j=>j.status==='success').length;byId('recent-count').textContent=jobs.length;
  const list=byId('dashboard-jobs');list.replaceChildren();
  for(const job of jobs){const item=document.createElement('article');item.className='dashboard-run'+(job.id===selectedId?' selected':'');const summary=document.createElement('div'),name=document.createElement('strong'),description=document.createElement('p');name.textContent=job.name+' · '+job.status;description.textContent=job.skill.displayName+' · '+new Date(job.createdAt).toLocaleString();summary.append(name,description);const link=document.createElement('a');link.className='button';link.href='/skill-dashboard.html?job='+encodeURIComponent(job.id);link.textContent=job.id===selectedId?'Viewing this run':'View run';item.append(summary,link);list.append(item);}
  if(!jobs.length){const empty=document.createElement('p');empty.textContent='No runs yet. Configure a skill job to get started.';list.append(empty);}
}
async function refreshDashboard(){
  if(refreshing)return;refreshing=true;
  try {
    const jobs=await request('');connected=true;byId('dashboard-access').hidden=true;byId('dashboard-content').hidden=false;
    if(!selectedId && jobs.length){selectedId=(jobs.find(j=>j.status==='running')||jobs.find(j=>j.status==='queued')||jobs[0]).id;window.history.replaceState(null,'','/skill-dashboard.html?job='+encodeURIComponent(selectedId));}
    renderHistory(jobs);byId('connection').textContent='Live · updated '+new Date().toLocaleTimeString();notify('');
    if(selectedId){if(!isJobId(selectedId))throw new Error('This job link is invalid. Choose a run from Recent runs.');const job=jobs.find(j=>j.id===selectedId)||await request('/'+encodeURIComponent(selectedId));renderJob(job);}
    else {byId('current-job').hidden=true;currentJob=null;}
  }catch(error){notify(error.message);byId('connection').textContent='Update unavailable · retrying';if(!currentJob)byId('current-job').hidden=true;}
  finally{refreshing=false;}
}
byId('dashboard-access').onsubmit=event=>{event.preventDefault();sessionStorage.setItem('boardAdminToken',byId('dashboard-token').value);refreshDashboard();};
byId('cancel-job').onclick=async()=>{if(!currentJob || cancelling)return;cancelling=true;byId('cancel-job').disabled=true;try{await request('/'+encodeURIComponent(currentJob.id)+'/cancel',{method:'POST'});await refreshDashboard();}catch(error){notify(error.message);}finally{cancelling=false;byId('cancel-job').disabled=false;}};
setInterval(()=>{if(connected && !document.hidden)refreshDashboard();},2000);
if(byId('dashboard-token').value){connected=true;refreshDashboard();}else{byId('dashboard-access').hidden=false;byId('connection').textContent='Connection required';notify('Connect with your board admin token. Your existing browser session is reused automatically.');}
