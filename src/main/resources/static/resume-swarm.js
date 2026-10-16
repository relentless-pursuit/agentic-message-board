'use strict';
const s=id=>document.getElementById(id);
let selectedRun=new URLSearchParams(location.search).get('run'),currentRun=null,connected=false,loading=false,submitting=false,renderedReview=null,renderedFinal=null;
let importsInFlight=0,canStartRun=false;
const importingKinds=new Set();
let requestKey=crypto.randomUUID();
s('swarm-token').value=sessionStorage.getItem('boardAdminToken')||'';
const make=(tag,text,css)=>{const el=document.createElement(tag);if(text!==undefined)el.textContent=String(text);if(css)el.className=css;return el;};
function message(text){s('swarm-message').textContent=text;}
function workflowShortcut(text,target){s('dock-message').textContent=text;s('dock-action').href=target;s('begin-review').href=target;}
function updateStartButton(){s('start-swarm').disabled=!connected||!canStartRun||importsInFlight>0||submitting;}
function setInputMethod(kind,method){
  const upload=method==='upload';s(kind+'-upload-panel').hidden=!upload;
  s(kind+'-paste-mode').setAttribute('aria-pressed',String(!upload));s(kind+'-upload-mode').setAttribute('aria-pressed',String(upload));
  const label=kind==='resume'?'CV':'job-description';s(kind+'-input-label').textContent=upload?'Extracted '+label+' text — review and edit':'Paste '+label+' text';
  s(kind+'-input').placeholder=upload?'Text extracted from your file will appear here for review…':kind==='resume'?'Paste your CV text here…':'Paste the target job description here…';
}
async function api(path,options={}){
  const response=await fetch('/api/swarm'+path,{...options,headers:{...options.headers,Authorization:'Bearer '+s('swarm-token').value}});
  if(!response.ok){let text='Request failed ('+response.status+').';try{const data=await response.json();text=data.message||data.detail||data.error||text;}catch(_){}
    if(response.status===401 || response.status===403){connected=false;s('swarm-access').hidden=false;text='Enter a valid board admin token to continue.';}throw new Error(text);}
  return response.json();
}
function jsonRequest(body){return {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)};}
function findings(container,title,result){
  if(!result)return;const box=make('section',undefined,'review-findings');box.append(make('strong',title),make('p',result.summary));
  const ul=make('ul');for(const item of result.items||[])ul.append(make('li',item));box.append(ul);container.append(box);
}
function renderGraph(steps){
  const open=new Set(Array.from(s('swarm-graph').querySelectorAll('details[open]')).map(d=>d.dataset.step));s('swarm-graph').replaceChildren();
  for(const step of steps){
    const card=make('article',undefined,'swarm-agent '+step.status);card.append(make('h3',step.id+'. '+step.name));
    card.append(make('span',step.status.replaceAll('_',' '),'agent-status '+step.status));
    const incoming=step.dependsOn.length?step.dependsOn.map(id=>steps[id-1]?.name||('Agent '+id)).join(', '):'Résumé and/or job inputs';
    const outgoing=steps.filter(other=>other.dependsOn.includes(step.id)).map(other=>other.name);
    card.append(make('p','Input · '+incoming,'hint'));
    if(step.id===9)card.append(make('p','Also receives your approved résumé and motivation answer','hint'));
    if(step.result){card.append(make('p','Output · '+step.result.summary,'agent-output'));if(step.result.items?.length){const list=make('ul',undefined,'agent-items');for(const item of step.result.items.slice(0,3))list.append(make('li',item));card.append(list);}}
    if(outgoing.length)card.append(make('p','Contributes to · '+outgoing.join(', '),'hint'));
    if(step.id===8 && step.result?.question)card.append(make('p','Question · '+step.result.question,'agent-question'));
    if(step.error)card.append(make('p',step.error));
    if(step.usage)card.append(make('p',step.usage.inputTokens+' input · '+step.usage.outputTokens+' output tokens','hint'));
    if(step.provider==='local-demo')card.append(make('p','Local demo · no external model call','hint'));
    const detail=make('details');detail.dataset.step=String(step.id);detail.open=open.has(String(step.id));detail.append(make('summary','Agent details'));
    detail.append(make('p',step.skill+' · '+step.revision.slice(0,12),'hint'));
    if(step.result)detail.append(make('pre',JSON.stringify(step.result,null,2)));card.append(detail);s('swarm-graph').append(card);
  }
}
function resultAt(run,n){return run.steps[n-1].result;}
function proposedEdits(){return resultAt(currentRun,5)?.edits||[];}
function choices(){return Array.from(s('review-edits').children).filter(row=>row.querySelector('input')?.checked).map(row=>({id:row.dataset.edit,after:row.querySelector('textarea').value}));}
function preview(){
  if(!currentRun)return;let text=currentRun.resume;
  const edits=choices().map(choice=>{const offered=proposedEdits().find(e=>e.id===choice.id);return {...choice,before:offered.before,at:text.indexOf(offered.before)};}).sort((a,b)=>b.at-a.at);
  for(const edit of edits)text=text.slice(0,edit.at)+edit.after+text.slice(edit.at+edit.before.length);
  s('approved-preview').textContent=s('custom-resume').value.trim()||text;
}
function renderReview(run){
  if(renderedReview===run.id)return;renderedReview=run.id;s('review-summary').textContent=resultAt(run,8)?.summary||'Review the offered changes.';s('intent-question-text').textContent=resultAt(run,8)?.question||'What interests you most about this role, and what would you like the cover letter to emphasize?';
  s('review-warnings').replaceChildren();findings(s('review-warnings'),'Fit and gaps',resultAt(run,4));findings(s('review-warnings'),'Evidence and claim verifier',resultAt(run,6));findings(s('review-warnings'),'Consistency review',resultAt(run,7));
  s('review-edits').replaceChildren();s('custom-resume').value='';
  for(const edit of resultAt(run,5)?.edits||[]){
    const row=make('article',undefined,'edit-choice');row.dataset.edit=edit.id;
    const label=make('label'),check=make('input');check.type='checkbox';check.disabled=!edit.valid;check.onchange=preview;label.append(check,document.createTextNode(' '+edit.id+' · '+edit.reason));row.append(label);
    row.append(make('p','Original passage','hint'),make('pre',edit.before));
    const afterLabel=make('label','Suggested wording — you can edit it'),after=make('textarea');after.value=edit.after;after.rows=4;after.maxLength=1600;after.disabled=!edit.valid;after.oninput=preview;afterLabel.append(after);row.append(afterLabel);
    row.append(make('p','Evidence: '+edit.evidence,'hint'),make('p',edit.validation,'hint'));s('review-edits').append(row);
  }
  if(!proposedEdits().length)s('review-edits').append(make('p','No precise edits were proposed. Keep the original or paste your own edited résumé.'));
  preview();
}
function renderRun(run){
  currentRun=run;s('add-file').hidden=true;s('swarm-setup').hidden=true;s('swarm-run').hidden=false;s('swarm-run-name').textContent=run.name;
  const phases={queued:'Queued. The agents will start shortly.',running:'Agents are comparing your résumé with the role.',review:'Paused for you: answer the motivation question and review proposed résumé edits.',finalizing:'Your résumé and motivation are approved. The cover-letter drafter and final reviewer are working.',final_review:'All 10 agents finished. Review and approve the final documents.',completed:'Completed. Your approved documents are ready to download.',cancelled:'Cancelled. No new agent stage will start.',failed:'The workflow stopped at a failed agent. Inspect its evidence below.',interrupted:'Interrupted by a server restart. This workflow was not retried.'};
  s('swarm-phase').textContent=phases[run.status]||run.status;
  const shortcuts={review:['Answer one question and review your CV edits','#intent-question'],final_review:['Review your CV and cover letter before approving','#swarm-final-review'],completed:['Your approved documents are ready','#swarm-downloads']};
  const shortcut=shortcuts[run.status]||['Follow the ten agents and their handoffs','#swarm-graph'];workflowShortcut(...shortcut);
  const usage=run.usage;s('swarm-usage').textContent=run.provider==='local-demo'?'Local demo · 10 agents · no external model calls or API cost.':usage.calls+'/10 model calls · '+usage.inputTokens+' input · '+usage.cachedInputTokens+' cached · '+usage.outputTokens+' output tokens · estimated $'+Number(usage.estimatedUsd).toFixed(6)+' · reserved $'+Number(usage.reservedUsd).toFixed(4)+' / $0.10';
  s('swarm-error').hidden=!run.error;s('swarm-error').textContent=run.error||'';
  s('prepare-new-swarm').hidden=!['failed','interrupted','cancelled'].includes(run.status);
  s('cancel-swarm').hidden=['completed','cancelled','failed','interrupted'].includes(run.status);
  s('swarm-review').hidden=run.status!=='review';if(run.status==='review')renderReview(run);
  s('swarm-final-review').hidden=run.status!=='final_review';
  if(run.status==='final_review' && renderedFinal!==run.id){renderedFinal=run.id;s('final-letter').value=resultAt(run,9)?.document||'';s('final-resume').textContent=run.approvedResume;s('final-findings').replaceChildren();findings(s('final-findings'),'Final verifier',resultAt(run,10));}
  s('swarm-downloads').hidden=run.status!=='completed';
  if(run.status==='completed'){
    s('download-resume').textContent=run.approvedResume;s('download-letter').textContent=run.coverLetter;s('download-buttons').replaceChildren();
    for(const file of ['resume.docx','cover-letter.docx','resume.txt','cover-letter.txt']){const button=make('button','Download '+file,'button');button.type='button';button.onclick=()=>download(file).catch(e=>message(e.message));s('download-buttons').append(button);}
  }
  renderGraph(run.steps);
}
async function download(file){
  const response=await fetch('/api/swarm/'+selectedRun+'/documents/'+file,{headers:{Authorization:'Bearer '+s('swarm-token').value}});
  if(!response.ok)throw new Error('Download failed ('+response.status+').');
  const url=URL.createObjectURL(await response.blob()),link=make('a');link.href=url;link.download=file;link.click();setTimeout(()=>URL.revokeObjectURL(url),10000);
}
async function refresh(){
  if(loading)return;loading=true;
  try {
    const history=await api('');s('swarm-history').replaceChildren();
    for(const run of history){const row=make('article',undefined,'swarm-history-row'),link=make('a',run.name);link.href='/resume-swarm.html?run='+run.id;row.append(link,make('span',run.status.replaceAll('_',' ')+' · '+new Date(run.createdAt).toLocaleString(),'hint'));s('swarm-history').append(row);}
    if(!history.length)s('swarm-history').append(make('p','No résumé workflows yet. Loading the sample does not start a run.','hint'));
    if(selectedRun){if(!/^\d+$/.test(selectedRun))throw new Error('Invalid workflow link.');renderRun(await api('/'+selectedRun));}
  }finally{loading=false;}
}
async function connect(){
  sessionStorage.setItem('boardAdminToken',s('swarm-token').value.trim());const options=await api('/options');connected=true;s('swarm-access').hidden=true;s('swarm-content').hidden=false;s('add-file').hidden=Boolean(selectedRun);
  if(!selectedRun)workflowShortcut('Prepare a CV and target role, or load the fictional sample','#swarm-setup');
  s('swarm-model').textContent=options.provider==='local-demo'?'Local demo · 10 agents':'OpenAI · '+options.model;s('swarm-billing').textContent=options.billing;
  const capacity=options.capacity||{canStart:true};canStartRun=options.configured&&capacity.canStart;updateStartButton();if(!selectedRun)renderGraph(options.agents);
  message(options.provider==='local-demo'?'Ready in local demo mode. No model calls or API costs. Loading a sample does not start the workflow.':!capacity.canStart?'Shared budget cannot start another workflow: '+capacity.remainingAttempts+' attempts and $'+Number(capacity.unreservedUsd).toFixed(4)+' unreserved remain.':'Ready. Loading a sample or viewing history does not call a model.');await refresh();
}
async function importSource(kind,useFile){
  if(importingKinds.has(kind))return;importingKinds.add(kind);
  const status=s(kind+'-import-status');for(const suffix of ['-file','-file-import','-url-import'])s(kind+suffix).disabled=true;s(kind+'-input').readOnly=true;importsInFlight++;updateStartButton();
  try {
    const data=new FormData();if(useFile){const file=s(kind+'-file').files[0];if(!file)throw new Error('Choose a file first.');if(file.size>2000000)throw new Error('Choose a file up to 2 MB.');if(!/\.(pdf|docx|txt)$/i.test(file.name))throw new Error('Choose a PDF, DOCX or TXT file.');data.set('file',file);}else{const url=s(kind+'-url').value.trim();if(!url)throw new Error('Enter a public URL first.');data.set('url',url);}
    status.dataset.state='loading';status.textContent='Extracting text…';message('Extracting the document locally. No model is being called.');
    const result=await api('/source',{method:'POST',body:data});s(kind+'-input').value=result.text;requestKey=crypto.randomUUID();
    const limit=kind==='resume'?8000:6000,tooLong=result.truncated||result.text.length>limit;
    const note=tooLong?'Text extracted. Shorten it to '+limit+' characters before starting.':'Text extracted. Review or edit it below before starting.';
    status.dataset.state=tooLong?'error':'success';status.textContent=note;message(note+' No model was called.');
  }catch(e){status.dataset.state='error';status.textContent=e.message;message(e.message);}finally{for(const suffix of ['-file','-file-import','-url-import'])s(kind+suffix).disabled=false;s(kind+'-input').readOnly=false;importingKinds.delete(kind);importsInFlight--;updateStartButton();}
}
s('swarm-access').onsubmit=e=>{e.preventDefault();connect().catch(error=>message(error.message));};
s('load-sample').onclick=async()=>{try{const sample=await (await fetch('/swarm-sample.json')).json();s('swarm-name').value=sample.name;s('resume-input').value=sample.resume;s('jd-input').value=sample.jd;requestKey=crypto.randomUUID();message('Fictional sample loaded. Review the inputs, then click Start to begin the workflow.');}catch(e){message(e.message);}};
for(const kind of ['resume','jd']){
  s(kind+'-paste-mode').onclick=()=>{setInputMethod(kind,'paste');s(kind+'-input').focus();};
  s(kind+'-upload-mode').onclick=()=>setInputMethod(kind,'upload');
  s(kind+'-file').onchange=()=>{if(s(kind+'-file').files.length)importSource(kind,true);};
  s(kind+'-file-import').onclick=()=>importSource(kind,true);s(kind+'-url-import').onclick=()=>importSource(kind,false);
  s(kind+'-input').oninput=()=>{if(!submitting)requestKey=crypto.randomUUID();};
}
s('swarm-start').onsubmit=async e=>{
  e.preventDefault();if(submitting||importsInFlight||!canStartRun)return;submitting=true;updateStartButton();message('Starting the résumé swarm…');
  try{const run=await api('',jsonRequest({requestKey,name:s('swarm-name').value,resume:s('resume-input').value,jd:s('jd-input').value}));selectedRun=String(run.id);history.replaceState(null,'','/resume-swarm.html?run='+run.id);renderRun(run);message('Workflow started. Follow the agent handoffs below.');}
  catch(error){message(error.message);}finally{submitting=false;if(!selectedRun)updateStartButton();}
};
s('custom-resume').oninput=preview;
s('accept-all-edits').onclick=()=>{for(const check of s('review-edits').querySelectorAll('input'))if(!check.disabled)check.checked=true;s('custom-resume').value='';preview();};
s('reject-all-edits').onclick=()=>{for(const check of s('review-edits').querySelectorAll('input'))check.checked=false;s('custom-resume').value='';preview();};
s('swarm-review').onsubmit=async e=>{
  e.preventDefault();const button=s('approve-resume');button.disabled=true;
  try{renderRun(await api('/'+selectedRun+'/approve',jsonRequest({choices:choices(),customResume:s('custom-resume').value,userMotivation:s('intent-answer').value})));message('Résumé and motivation approved. The cover letter and final review are now running.');}
  catch(error){message(error.message);}finally{button.disabled=false;}
};
s('add-file').onclick=()=>{setInputMethod('resume','upload');s('resume-file').click();};
s('swarm-final-review').onsubmit=async e=>{
  e.preventDefault();s('finalize-swarm').disabled=true;
  try{renderRun(await api('/'+selectedRun+'/finalize',jsonRequest({coverLetter:s('final-letter').value})));message('Final documents approved. Downloads are ready.');}
  catch(error){message(error.message);}finally{s('finalize-swarm').disabled=false;}
};
s('cancel-swarm').onclick=async()=>{s('cancel-swarm').disabled=true;try{renderRun(await api('/'+selectedRun+'/cancel',{method:'POST'}));}catch(e){message(e.message);}finally{s('cancel-swarm').disabled=false;}};
s('prepare-new-swarm').onclick=async()=>{
  const previous=currentRun;if(!previous)return;
  selectedRun=null;currentRun=null;renderedReview=null;renderedFinal=null;requestKey=crypto.randomUUID();
  s('swarm-name').value=previous.name;s('resume-input').value=previous.resume;s('jd-input').value=previous.jd;
  s('swarm-run').hidden=true;s('add-file').hidden=false;s('swarm-setup').hidden=false;history.replaceState(null,'','/resume-swarm.html');
  try{await connect();message('Inputs copied into a fresh workflow. Review them, then click Start to make new paid model calls. The earlier run remains in history.');s('swarm-setup').scrollIntoView({behavior:'smooth'});}catch(e){message(e.message);}
};
setInterval(()=>{if(connected && !document.hidden && !submitting)refresh().catch(e=>message(e.message));},2000);
if(location.protocol==='file:'){
  s('swarm-access').hidden=true;message('This is a visual file preview. Start the local board server, then open http://127.0.0.1:8087/resume-swarm.html to run the workflow.');
  workflowShortcut('Open the local board server to run this demo','http://127.0.0.1:8087/resume-swarm.html');s('begin-review').textContent='Open runnable demo ↗';
}else if(s('swarm-token').value)connect().catch(error=>message(error.message));else message('Enter the board admin token to configure or view a workflow.');
