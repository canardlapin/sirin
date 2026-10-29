from pathlib import Path
import datetime,json,subprocess,time
base=Path(__file__).resolve().parent
binary_paths=[str(Path.cwd()/"target/gc-investigation/bin/repro-immix-trap"),str(Path.cwd()/"target/upstream-evaluation/bin/gc-repro-fa4a9e6")]
deadline=time.monotonic()+260
captured=set()
while time.monotonic()<deadline:
    dirs=list(base.glob("standalone-*-w1-*"))
    for directory in dirs:
        stderr=directory/"stderr.log"
        if directory.name in captured or (directory/"result.json").exists() or not stderr.exists():continue
        text=stderr.read_text()
        if "Waiting for 1 thread(s) to reach safepoint" not in text:continue
        label=directory.name.split("-")[1]
        binary=binary_paths[0 if label=="stock" else 1]
        listing=subprocess.run(["/bin/ps","-axo","pid=,args="],capture_output=True,text=True,check=True).stdout
        matches=[line.strip().split(None,1)[0] for line in listing.splitlines() if len(line.strip().split(None,1))==2 and line.strip().split(None,1)[1].startswith(binary+" ")]
        if len(matches)!=1:continue
        captured.add(directory.name)
        command=["/usr/bin/sample",matches[0],"1","1","-file",str(directory/"native-stall.sample.txt")]
        record={"command":command,"utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"trigger":"GC warning already recorded before profiler attach","pre_attach_stderr":text}
        try:
            run=subprocess.run(command,capture_output=True,text=True,timeout=4)
            record.update(returncode=run.returncode,stdout=run.stdout,stderr=run.stderr)
        except subprocess.TimeoutExpired as error:
            record.update(error="sample timeout",stdout=str(error.stdout),stderr=str(error.stderr))
        (directory/"sample-attachment.json").write_text(json.dumps(record,indent=2)+"\n")
        print(json.dumps({"sampled":directory.name,"record":record}),flush=True)
    if len(list(base.glob("standalone-*-w1-*/result.json")))==6:break
    time.sleep(0.2)
print(json.dumps({"captured":sorted(captured)}),flush=True)
