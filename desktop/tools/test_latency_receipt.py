import csv
from pathlib import Path
import tempfile
import unittest
from latency_receipt import validate, GEOMETRY


class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name)
        self.fields = {"status": "complete", "samplesComplete": "true", "targetsMet": "true", "expectedGeometry": GEOMETRY, "observedGeometry": GEOMETRY, "geometryStable": "true", "geometryChecks": "241", "steps": "240", "warmups": "40", "measured": "200", "failure": ""}
        self.rows = []
        for i in range(240):
            self.rows.append({"step": str(i + 1), "phase": "warmup" if i < 40 else "measured", "kind": "action" if i < 20 or 40 <= i < 140 else "scroll", "input_to_present_ms": "10.0", "logical_width": "600", "logical_height": "360", "pixel_width": "1200", "pixel_height": "720"})

    def write(self):
        (self.path / "receipt.txt").write_text("".join(f"{k}={v}\n" for k, v in self.fields.items()))
        with (self.path / "phases.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(self.rows[0]))
            writer.writeheader()
            writer.writerows(self.rows)
        with (self.path / "input-to-present.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=["phase", "kind", "milliseconds"])
            writer.writeheader()
            writer.writerows({"phase": row["phase"], "kind": row["kind"], "milliseconds": row["input_to_present_ms"]} for row in self.rows)
        return validate(self.path)

    def test_complete_fixed_fixture(self):
        self.assertTrue(self.write()[0])

    def test_one_wrong_dpi_sample_refuses(self):
        self.rows[90]["pixel_width"] = "600"
        self.assertFalse(self.write()[0])

    def test_resized_receipt_refuses(self):
        self.fields["geometryStable"] = "false"
        self.assertFalse(self.write()[0])

    def test_counts_cannot_hide_missing_samples(self):
        self.rows.pop()
        self.assertFalse(self.write()[0])

    def test_claimed_pass_cannot_hide_missed_p95(self):
        for row in self.rows[40:47]:
            row["input_to_present_ms"] = "51.0"
        self.assertFalse(self.write()[0])

    def test_nan_refuses(self):
        self.rows[100]["input_to_present_ms"] = "nan"
        self.assertFalse(self.write()[0])


if __name__ == "__main__":
    unittest.main()
