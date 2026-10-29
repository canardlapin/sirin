"""Independent frozen camera workload/latency acceptance; no quiet-host claim."""
import csv
import json
import math
from pathlib import Path
import sys


def validate(directory, mode):
    assert mode in ("worker", "input")
    directory = Path(directory)
    fields = dict(line.split("=", 1) for line in (directory / "receipt.txt").read_text().splitlines())
    expected_count = 960 if mode == "worker" else 240
    assert fields["complete"] == "true" and int(fields["samples"]) == expected_count
    assert fields["inputToPresent"] == ("false" if mode == "worker" else "true")
    rows = list(csv.DictReader((directory / "frames.csv").open()))
    assert len(rows) == expected_count
    scopes = ("1.0", "1.25", "1.5", "2.0") if mode == "worker" else ("native",)
    summaries = []
    for scope in scopes:
        scoped = [r for r in rows if r["scale"] == scope] if mode == "worker" else rows
        assert len(scoped) == 240
        if mode == "input":
            geometries = {tuple(int(r[k]) for k in ("logical_width", "logical_height", "pixel_width", "pixel_height")) for r in scoped}
            assert len(geometries) == 1
            lw, lh, pw, ph = next(iter(geometries))
            assert (lw, lh) == (320, 180) and 0 < pw <= 640 and 0 < ph <= 360 and pw / lw == ph / lh
        for offset, kind in ((0, "pan"), (120, "zoom")):
            samples = scoped[offset:offset + 120]
            assert len(samples) == 120
            values = []
            for index, row in enumerate(samples):
                assert row["kind"] == kind and int(row["index"]) == index
                assert row["phase"] == ("warmup" if index < 20 else "measured")
                elapsed = float(row["milliseconds" if mode == "worker" else "input_to_present_ms"])
                assert math.isfinite(elapsed) and elapsed >= 0
                if index >= 20:
                    assert all(int(row[key]) == 0 for key in ("compiled", "mesh_upload", "asset_upload"))
                    values.append(elapsed)
                if mode == "worker":
                    assert 1 <= int(row["variants"]) <= 2 and 0 < int(row["work"]) <= 64000000
                    assert 0 <= int(row["reused_pixels"]) <= int(320 * float(scope)) * int(180 * float(scope))
            values.sort()
            summaries.append(dict(scope=scope, kind=kind, n=len(values), p50_ms=values[49], p95_ms=values[94], p99_ms=values[98], max_ms=values[-1], passed=values[94] <= 50 and values[98] <= 100))
    return dict(mode=mode, samples=len(rows), passed=all(r["passed"] for r in summaries), quiet_qualified=False, groups=summaries)


if __name__ == "__main__":
    result = validate(Path(sys.argv[1]), sys.argv[2])
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["passed"] else 1)
