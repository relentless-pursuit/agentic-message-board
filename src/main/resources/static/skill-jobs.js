'use strict';
const $=id=>document.getElementById(id),form=$('job-form');
let choices=[],busy=false,submitting=false,runtimeReady=false,activeJobId=null,navigating=false;
let requestedSkillId=new URLSearchParams(window.location.search).get('skill'),initialLoad=true;
$('token').value=sessionStorage.getItem('boardAdminToken')||'';
function message(text){$('message').textContent=text;$('run-status').textContent=text;}
function openDashboard(id){if(navigating)return;navigating=true;window.location.assign('/skill-dashboard.html?job='+encodeURIComponent(id));}
function updateRunButton(){
  $('run').disabled=submitting || !!activeJobId || !runtimeReady || !selection()?.runnable || !$('model').value;
  $('run').textContent=submitting?'Submitting…':activeJobId?'Job in progress…':'Run selected skill';
}
async function api(path,options={}){
  const response=await fetch('/api/skill-jobs'+path,{...options,headers:{...options.headers,Authorization:'Bearer '+$('token').value}});
  if(!response.ok){let text='Request failed ('+response.status+')';try{const body=await response.json();text=body.error||body.message||text;}catch(_){}throw new Error(text);}
  return response.json();
}
function option(select,value,label,disabled=false){const item=document.createElement('option');item.value=value;item.textContent=label;item.disabled=disabled;select.append(item);}
function selection(){return choices.find(s=>s.id===$('skill').value);}
function changeSkill(){const skill=selection();$('format').replaceChildren();$('skill-detail').textContent='';$('starter-task').disabled=!skill?.starterTask?.instructions;updateRunButton();if(!skill)return;
  for(const format of skill.formats)option($('format'),format,format.toUpperCase());
  $('skill-detail').textContent=skill.displayName+' · '+skill.origin+' · version '+skill.version+' · '+skill.description;
}
function useStarter(){const task=selection()?.starterTask;if(!task)return;$('job-name').value=task.name||'';$('instructions').value=task.instructions||'';}
async function load(){
  sessionStorage.setItem('boardAdminToken',$('token').value);const previous=selection()?.id,previousModel=$('model').value;
  const data=await api('/options');choices=data.skills;
  $('skill').replaceChildren();option($('skill'),'','Choose an installed skill');for(const skill of choices)option($('skill'),skill.id,skill.displayName+(skill.runnable?'':' — unavailable'),!skill.runnable);
  const wanted=previous||requestedSkillId,missing=!!wanted&&!choices.some(s=>s.id===wanted && s.runnable);
  const first=choices.find(s=>s.runnable);$('skill').value=missing?'':wanted||first?.id||'';changeSkill();
  if(initialLoad && !missing && !$('job-name').value && !$('instructions').value)useStarter();initialLoad=false;
  $('model').replaceChildren();option($('model'),'','Choose a model for this run');for(const model of data.runtime.models)option($('model'),model.id,model.label);
  if(data.runtime.models.some(m=>m.id===previousModel))$('model').value=previousModel;
  else $('model').value='';
  $('seconds').replaceChildren();for(const seconds of data.timeLimits)option($('seconds'),seconds,seconds+' seconds');$('seconds').value='120';
  $('runtime').textContent=data.runtime.version+' · '+data.runtime.authentication+' · '+(data.runtime.available?'Runtime ready':'Runtime unavailable: check Codex executable, login, models and bundled Python/Node.');
  $('model-note').textContent='Local catalog updated '+(data.runtime.modelsFetchedAt||'unknown')+'. Model access is confirmed when a job runs.';
  runtimeReady=data.runtime.available;updateRunButton();
  $('catalog').replaceChildren();for(const skill of choices){const item=document.createElement('li');item.textContent=skill.displayName+' · '+skill.origin+' · '+skill.version+' — '+skill.availability;$('catalog').append(item);}
  $('builder').hidden=false;message(missing?'The selected skill is no longer available. Choose an installed skill; no replacement was selected.':'Choose a model, review the suggested task and add your source material. Run starts a model job.');await refreshJobs();
}
function paragraph(parent,text){const p=document.createElement('p');p.textContent=text;parent.append(p);}
async function refreshJobs(){
  if(busy)return;busy=true;
  try {const jobs=await api('');$('jobs').replaceChildren();const active=jobs.find(j=>j.status==='running')||jobs.find(j=>j.status==='queued');activeJobId=active?.id||null;updateRunButton();
    if(active){openDashboard(active.id);return;}
    if(!jobs.length)paragraph($('jobs'),'No skill builds yet.');
    for(const job of jobs){const article=document.createElement('article');article.className='skill-job';const title=document.createElement('h3');title.textContent=job.name+' · '+job.status;article.append(title);
      paragraph(article,job.skill.displayName+' · '+job.model+' · '+new Date(job.createdAt).toLocaleString());
      const link=document.createElement('a');link.className='button';link.href='/skill-dashboard.html?job='+encodeURIComponent(job.id);link.textContent='Open dashboard';article.append(link);
      $('jobs').append(article);
    }
  } finally {busy=false;}
}
$('access').onsubmit=event=>{event.preventDefault();load().catch(e=>message(e.message));};
$('refresh').onclick=()=>load().catch(e=>message(e.message));$('skill').onchange=()=>{requestedSkillId=null;changeSkill();};$('model').onchange=updateRunButton;$('starter-task').onclick=useStarter;
form.addEventListener('invalid',event=>{message('Please complete '+(event.target.labels?.[0]?.textContent.trim().split('\n')[0]||'the required fields')+' before running.');},true);
form.onsubmit=async event=>{event.preventDefault();if(submitting)return;if(activeJobId){openDashboard(activeJobId);return;}const skill=selection();if(!skill?.runnable)return;
  if(!$('model').value){message('Choose a model for this run.');return;}
  const files=Array.from($('files').files);if(files.length>5 || files.reduce((n,f)=>n+f.size,0)>20000000){message('Upload at most five files, 20 MB total.');return;}
  const data=new FormData(form);data.set('revision',skill.revision);if(!files.length)data.delete('files');submitting=true;updateRunButton();message('Submitting your skill job…');
  try {const job=await api('',{method:'POST',body:data});activeJobId=job.id;message('“'+job.name+'” submitted. Opening your dashboard…');openDashboard(job.id);}catch(e){message(e.message);}finally{submitting=false;updateRunButton();}
};
setInterval(()=>{if(!$('builder').hidden && !document.hidden && !submitting && !navigating)refreshJobs().catch(e=>message(e.message));},2000);
if($('token').value)load().catch(e=>message(e.message));
