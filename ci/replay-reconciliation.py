#!/usr/bin/env python3
"""Three predeclared Native-suite attempts; preserve every failure, never retry to green."""
import datetime
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import time

out = Path('target/alpha-linux-replay')
out.mkdir(parents=True,exist_ok=False)
protocol = {'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            'platform':platform.platform(),'logical_cpus':os.cpu_count(),'attempts':3,
            'command':['sbt','-batch','testNative'],'per_attempt_timeout_seconds':900,
            'seed':'l8Dl-ZJbwAFzUVsay_UCl1zuWxTHh-oHefe7es6XzbN=',
            'qualification':'diagnostic reproduction; any failure fails the job'}
(out/'protocol.json').write_text(json.dumps(protocol,indent=2)+'\n')
results=[]
for index in range(1,4):
    started=time.monotonic()
    record={'attempt':index,'start_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    with (out/f'attempt-{index}.log').open('w') as log:
        proc=subprocess.Popen(protocol['command'],stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        try:
            proc.wait(timeout=900)
            record['timed_out']=False
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGKILL)
            proc.wait()
            record['timed_out']=True
    record.update(returncode=proc.returncode,elapsed_seconds=time.monotonic()-started)
    results.append(record)
    (out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
    print(json.dumps(record),flush=True)
    print((out/f'attempt-{index}.log').read_text()[-10000:],flush=True)
raise SystemExit(0 if all(r['returncode']==0 and not r['timed_out'] for r in results) else 1)
