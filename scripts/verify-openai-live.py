#!/usr/bin/env python3
"""Explicit, paid acceptance checks on a disposable copy. Never run from ordinary tests."""
import os,json,time,uuid,pathlib,urllib.request,urllib.error,sys
if os.environ.get('BOARD_LIVE_TESTS')!='1':raise SystemExit('Set BOARD_LIVE_TESTS=1 to authorize the bounded live acceptance run.')
root=pathlib.Path(__file__).resolve().parents[1];base=os.environ.get('BOARD_TEST_URL','http://127.0.0.1:8088')
assert base.endswith(':8088'),'Use the disposable acceptance server only'
token=(root/'target/acceptance/data/secrets/initial-admin-token').read_text().strip()
def api(path,body=None,method=None):
 data=None if body is None else json.dumps(body).encode();req=urllib.request.Request(base+'/api'+path,data=data,method=method or ('GET' if body is None else 'POST'),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=60) as r:return json.load(r)
 except urllib.error.HTTPError as e:raise RuntimeError(str(e.code)+' '+e.read().decode()[:1000])
for _ in range(30):
 try:before=api('/model-usage');break
 except Exception:time.sleep(1)
else:raise RuntimeError('Acceptance server did not start')
baseline=json.loads((root/'reports/openai-migration-before.json').read_text())['usage']
report={'beforeUsage':baseline,'attemptStartUsage':before,'preflight':api('/workflows/preflight',{}),'agents':[],'definitions':[],'workflows':[]}
path=root/'reports/openai-live-validation.json'
def save():path.write_text(json.dumps(report,indent=2))
roles=[('issue','Issue Analyst','issue-analysis'),('navigator','Repository Navigator','repository-navigation'),('impact','Change Impact Analyst','change-impact'),('tests','Test Planner','test-planning'),('risk','Risk Reviewer','risk-review'),('releases','Release Reporter','release-reporting'),('rss','RSS Scout','rss-analysis'),('hn','Hacker News Scout','hackernews-analysis'),('evidence','Evidence Reviewer','evidence-review'),('editor','Briefing Editor','briefing')]
actors={}
if '--resume' in sys.argv:
 report=json.loads(path.read_text());report.setdefault('failedAttempts',[]).extend([w for w in report['workflows'] if w['status']!='completed']);report['workflows']=[w for w in report['workflows'] if w['status']=='completed'];report['preflight']=api('/workflows/preflight',{});actors={node:(handle,skill) for (node,name,skill),handle in zip(roles,report['agents'])};save()
else:
 for node,name,skill in roles:
  a=api('/workflows/agents',{'name':name,'function':'Execute the '+name+' skill on selected source evidence.','skill':skill,'connector':'workflow_input','settings':{}})['agent'];actors[node]=(a['handle'],skill);report['agents'].append(a['handle']);save()
params={'repository':'psf/requests','issue':7634,'revision':'611c6162cbc4ac2020a2f91c7cfa4f3abf9bbb60','rssUrl':'https://github.blog/changelog/feed/','hnTop':3}
def run(name,ids,edges):
 if any(w['name']==name and w['status']=='completed' for w in report['workflows']):
  print('Previously verified',name,flush=True);return
 nodes=[{'id':n,'agent':actors[n][0],'skill':actors[n][1],'x':(i%3)*.33,'y':(i//3)*190} for i,n in enumerate(ids)]
 d=api('/workflows',{'id':None,'name':'Verification · '+name,'nodes':nodes,'edges':[{'from':a,'to':b,'output':'result','input':'upstreamResults'} for a,b in edges]});report['definitions'].append(d['id']);save()
 r=api('/workflows/'+d['id']+'/runs',{'requestKey':str(uuid.uuid4()),'source':'','provider':'openai','parameters':params});entry={'name':name,'id':r['id'],'status':'running'};report['workflows'].append(entry);save();print('Started',name,r['id'],flush=True)
 end=time.monotonic()+600
 while time.monotonic()<end:
  r=api('/workflows/runs/'+r['id'])
  if r['status']!='running':break
  time.sleep(1)
 entry.update(status=r['status'],nodes=[])
 for n in r['nodes']:
  item={'node':n['node'],'name':n['name'],'status':n['status'],'runId':n.get('runId'),'error':n.get('error')}
  if n.get('runId'):
   receipt=api('/runs/'+str(n['runId']));evidence=api('/workflows/evidence/'+str(n['runId']));item.update(engine=receipt['engine'],startedAt=receipt['startedAt'],finishedAt=receipt['finishedAt'],report=evidence.get('report'))
  entry['nodes'].append(item)
 save();print('Finished',name,r['status'],flush=True)
 if r['status']!='completed':raise AssertionError(json.dumps([(n['name'],n['status'],n.get('error')) for n in r['nodes']]))
 for item in entry['nodes']:assert item['engine']=='openai' and item['report']['responses']
 byid={n['node']:n for n in entry['nodes']}
 for a,b in edges:assert byid[b]['startedAt']>=byid[a]['finishedAt']
 entry['joinOrderingVerified']=True
 report['afterUsage']=api('/model-usage');save()
run('GitHub impact report',['issue','impact','tests','editor'],[('issue','impact'),('impact','tests'),('issue','editor'),('impact','editor'),('tests','editor')])
run('Technology digest',['rss','hn','editor'],[('rss','editor'),('hn','editor')])
roots=['issue','navigator','releases','rss','hn'];ids=roots+['impact','tests','risk','evidence','editor']
edges=[('issue','impact'),('navigator','impact'),('impact','tests'),('impact','risk')]+[(n,'evidence') for n in ids if n not in ['evidence','editor']]+[('evidence','editor')]
run('Ten-agent engineering briefing',ids,edges)
report['afterUsage']=api('/model-usage');report['additionalEstimatedUsd']=report['afterUsage']['ESTIMATED_USD']-baseline['ESTIMATED_USD'];report['additionalReservedUsd']=report['afterUsage']['RESERVED_USD']-baseline['RESERVED_USD'];assert report['additionalReservedUsd']<=1.000001
report['passed']=True;save();print(json.dumps({'passed':True,'additionalEstimatedUsd':report['additionalEstimatedUsd'],'additionalReservedUsd':report['additionalReservedUsd']}),flush=True)
