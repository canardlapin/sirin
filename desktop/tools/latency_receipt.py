"""Independent acceptance of the frozen host receipt, including negative controls."""
import csv
import math
from pathlib import Path

GEOMETRY = "600x360logical/1200x720physical"
DIMENSIONS = ("600", "360", "1200", "720")


def validate(directory: Path) -> tuple[bool, str]:
    try:
        fields = dict(line.split("=", 1) for line in (directory / "receipt.txt").read_text().splitlines())
        expected = {"status": "complete", "samplesComplete": "true", "targetsMet": "true", "expectedGeometry": GEOMETRY, "observedGeometry": GEOMETRY, "geometryStable": "true", "steps": "240", "warmups": "40", "measured": "200", "failure": ""}
        if any(fields.get(key) != value for key, value in expected.items()) or int(fields["geometryChecks"]) <= 0:
            return False, "host receipt failed or fixture/counts differ"
        with (directory / "input-to-present.csv").open(newline="") as stream:
            raw = list(csv.DictReader(stream))
        with (directory / "phases.csv").open(newline="") as stream:
            phases = list(csv.DictReader(stream))
        if len(raw) != 240 or len(phases) != 240:
            return False, "incomplete raw/phase samples"
        groups = {}
        for index, (sample, phase) in enumerate(zip(raw, phases)):
            expected_phase = "warmup" if index < 40 else "measured"
            expected_kind = "action" if index < 20 or 40 <= index < 140 else "scroll"
            if (sample["phase"], sample["kind"]) != (expected_phase, expected_kind):
                return False, "sample class/order differs from frozen fixture"
            if phase["phase"] != sample["phase"] or phase["kind"] != sample["kind"] or int(phase["step"]) != index + 1:
                return False, "phase/raw sample mismatch"
            if tuple(phase[key] for key in ("logical_width", "logical_height", "pixel_width", "pixel_height")) != DIMENSIONS:
                return False, "sample geometry differs from frozen fixture"
            milliseconds = float(sample["milliseconds"])
            if not math.isfinite(milliseconds) or milliseconds < 0 or float(phase["input_to_present_ms"]) != milliseconds:
                return False, "invalid or inconsistent input latency"
            groups.setdefault((expected_phase, expected_kind), []).append(milliseconds)
        for kind, p95, p99 in (("action", 50, 100), ("scroll", 100, 200)):
            values = sorted(groups[("measured", kind)])
            if values[94] > p95 or values[98] > p99:
                return False, f"{kind} percentile target missed"
        return True, "frozen geometry, samples and percentile targets verified"
    except (OSError, ValueError, KeyError, TypeError) as error:
        return False, f"invalid receipt: {error}"
