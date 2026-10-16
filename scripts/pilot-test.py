#!/usr/bin/env python3
"""Exercise ten agents via the authenticated local API. --live explicitly uses OpenAI."""
import argparse, json, time, urllib.request, urllib.error
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--live',action='store_true')
parser.add_argument('--url',default='http://127.0.0.1:8087')
parser.add_argument('--token-file',default='data/runtime/secrets/initial-admin-token')
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
token=(root/args.token_file).read_text().strip()
def call(path,data=None):
    req=urllib.request.Request(args.url+'/api'+path,data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=60) as response:return json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit('Local API error '+str(error.code)+': '+error.read(1500).decode())
def wait(batch,expected):
    until=time.monotonic()+180
    while time.monotonic()<until:
        result=call('/benchmarks/'+batch)
        counts={k.lower():v for k,v in result['runs'].items()}
        if counts['total']==expected and counts['pending']==0:return result
        time.sleep(0.5)
    raise SystemExit('Pilot timed out; inspect /api/queue and agent run records')
started=time.monotonic()
if args.live and not call('/model-usage').get('keyConfigured'):
    raise SystemExit('OPENAI_API_KEY is missing. Save the key in data/secrets/openai-api-key or the server environment first.')
batch=call('/benchmarks',{'agents':10,'live':args.live})['batch']
first=wait(batch,10)
call('/benchmarks/'+batch+'/repeat',{'round':1,'live':args.live})
repeat=wait(batch,20)
result={'provider':'openai' if args.live else 'template','generatedObservations':True,
        'elapsedSeconds':round(time.monotonic()-started,3),'firstPass':first,'unchangedRepeat':repeat}
out=root/'reports'/('pilot-openai.json' if args.live else 'pilot-template.json')
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(result,indent=2,default=str)+'\n')
print(json.dumps(result,indent=2,default=str))
if any(k.lower()=='failed' and int(v)>0 for k,v in repeat['runs'].items()):
    raise SystemExit('Pilot includes failed runs; see saved report')
