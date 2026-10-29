#!/usr/bin/env python3
"""Replay bounded correctness controls; a failed trial is retained, never retried."""
import argparse,hashlib,json,os
from pathlib import Path
import subprocess,sys

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo",type=Path,default=Path(__file__).resolve().parents[4])
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--suite",choices=["all","stall","standalone","conditional"],default="all")
    a=p.parse_args();repo=a.repo.resolve();out=a.out.resolve()
    binaries={
      ("stall","stock"):("target/gc-investigation/bin/original-immix-trap","070bae673e7d87ca1914054c29cf25c79a82f7cd1e56f9a88522c99425d1d16f"),
      ("stall","pinned"):("target/upstream-evaluation/bin/threads-fa4a9e6","0df4e42482b3eec593cb0c4856440903de9a7b07db1ae2534935dea8f867f10d"),
      ("standalone","stock"):("target/gc-investigation/bin/repro-immix-trap","5d9f1bb14a5065fbe82cfd07d3807ad37dd4fba6649634fa00ba8b82e66eddea"),
      ("standalone","pinned"):("target/upstream-evaluation/bin/gc-repro-fa4a9e6","3dd4496c1f0a87ecf16831accf371dfd16a76e00390ecbdda0f88af4be1bac90"),
      ("conditional","stock"):("target/gc-investigation/bin/repro-immix-conditional","6f5f70726310c2e2e23502c4a1a6c94f2bcb37645e74583d0740c21a457e4c56")}
    suites=["stall","standalone","conditional"] if a.suite=="all" else [a.suite]
    for suite in suites:
        for version in (["stock"] if suite=="conditional" else ["stock","pinned"]):
            path,want=binaries[suite,version]
            if hashlib.sha256((repo/path).read_bytes()).hexdigest()!=want:
                raise SystemExit("binary provenance mismatch: "+path)
    out.mkdir(parents=True,exist_ok=False)
    env=os.environ.copy()
    for k in list(env):
        if k.startswith(("GC_","SCALANATIVE_GC_")):del env[k]
    env.update(GC_MAXIMUM_HEAP_SIZE="128M",SCALANATIVE_GC_SYNC_TIMEOUT_MS="15000")
    results=[]
    for suite in suites:
        for trial in range(1,4):
            for version in (["stock"] if suite=="conditional" else ["stock","pinned"]):
                name=f"{suite}-{version}-w1-{trial}"
                args=["soak","40","1","0"] if suite=="stall" else ["40","1","2048","true"]
                binary=binaries[suite,version][0]
                command=[sys.executable,"-B",str(repo/"probes/gc-repro/run.py"),"--out",str(out/name),"--timeout","55","--",str(repo/binary),*args]
                run=subprocess.run(command,cwd=repo,env=env)
                results.append({"case":name,"runner_exit":run.returncode,"pause_qualification":False})
    (out/"replay-summary.json").write_text(json.dumps(results,indent=2)+"\n")
    return int(any(r["runner_exit"]!=0 for r in results))

if __name__=="__main__":raise SystemExit(main())
