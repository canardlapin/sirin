#!/usr/bin/env python3
"""Verify frozen receipt integrity and preserved outcome denominators, not qualification."""
import hashlib,json
from pathlib import Path

def main():
    root=Path(__file__).resolve().parent
    manifest=json.loads((root/"manifest.json").read_text())
    for name,want in manifest["sha256"].items():
        assert hashlib.sha256((root/name).read_bytes()).hexdigest()==want,name
    summary=json.loads((root/"summary.json").read_text())
    assert summary["qualification"] is False
    assert len(summary["cases"])==12
    expected={(s,v,i) for s in ["stall","standalone"] for v in ["stock","pinned"] for i in range(1,4)}
    assert {(r["suite"],r["runtime"],r["trial"]) for r in summary["cases"]}==expected
    for row in summary["cases"]:
        d=root/row["case"];r=json.loads((d/"result.json").read_text())
        assert r["returncode"]==row["returncode"]
        assert r["binary_sha256"]==row["binary_sha256"]
        assert r["timeout_seconds"]==55 and r["timed_out"] is False
        assert r["environment"]=={"GC_MAXIMUM_HEAP_SIZE":"128M","SCALANATIVE_GC_SYNC_TIMEOUT_MS":"15000"}
        assert r["command"][1:]==(["soak","40","1","0"] if row["suite"]=="stall" else ["40","1","2048","true"])
        if r["returncode"]==-6:
            assert row["safepoint_warning"]
            assert row["watchdog_abort"] if row["suite"]=="stall" else row["gc_sync_abort"]
        else:
            assert r["returncode"]==0 and row["result_complete"]
    for label,total in summary["totals"].items():
        suite,version=label.split("-")
        rows=[r for r in summary["cases"] if r["suite"]==suite and r["runtime"]==version]
        assert total=={"runs":len(rows),"completed":sum(r["returncode"]==0 for r in rows),"safepoint_aborts":sum(r["returncode"]==-6 and r["safepoint_warning"] for r in rows)}
    sample=root/"standalone-pinned-w1-2"
    attach=json.loads((sample/"sample-attachment.json").read_text())
    assert "Waiting for 1 thread(s)" in attach["pre_attach_stderr"] and attach["returncode"]==0
    stacks=(sample/"native-stall.sample.txt").read_text()
    assert all(s in stacks for s in ["Synchronizer_yield","sigwait","Synchronizer_acquireGeneration","sleepInterruptible"])
    conditional=summary["conditional_control"]
    assert conditional["runs"]==3 and conditional["completed"]==3 and conditional["qualification"] is False
    assert len(conditional["cases"])==3
    for row in conditional["cases"]:
        d=root/row["case"];r=json.loads((d/"result.json").read_text())
        assert r["returncode"]==0 and r["timed_out"] is False and r["timeout_seconds"]==55
        assert r["binary_sha256"]=="6f5f70726310c2e2e23502c4a1a6c94f2bcb37645e74583d0740c21a457e4c56"
        assert r["command"][1:]==["40","1","2048","true"]
        assert r["environment"]=={"GC_MAXIMUM_HEAP_SIZE":"128M","SCALANATIVE_GC_SYNC_TIMEOUT_MS":"15000"}
        assert "DONE elapsed_ms=" in (d/"stdout.log").read_text()
        assert "not reaching safepoint" not in (d/"stderr.log").read_text()
    checks=json.loads((root/"required-checks/result.json").read_text())
    assert len(checks)==2 and all(r["returncode"]==0 and r["timed_out"] is False for r in checks)
    assert json.loads((root/"required-checks/test-counts.json").read_text())=={"testJVM":299,"testNative":299}
    pre=json.loads((root/"preflight/result.json").read_text())
    assert len(pre["samples"])==6 and pre["ready"] is False
    print(json.dumps({"receipt_integrity":"pass","preserved_cases":12,"safepoint_aborts":sum(r["returncode"]==-6 for r in summary["cases"]),"conditional_completed":3,"qualification":False}))

if __name__=="__main__":main()
