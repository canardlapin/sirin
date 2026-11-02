"""Negative controls for qualification receipts; these fixtures are never latency evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('qualify', Path(__file__).with_name('qualify.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
BINARY = Path('/fixture/conditional')
RECORD = {'command': [str(BINARY), 'soak', '600', '4', '1000'], 'binary_sha256': gate.BINARY_SHA256,
          'returncode': 0, 'timed_out': False, 'timeout_seconds': 620, 'environment': gate.ENVIRONMENT}
OUTPUT = '''RESULT mode=soak
RESULT soak.seconds=600.0
RESULT soak.workers=4 soak.push_every_us=1000
RESULT soak.events_pushed=1000 soak.push_failures=0 soak.events_received=1000
RESULT soak.alloc_iterations=100000 soak.gc_heap_mb=34.1
RESULT soak.latency_max_ms=49.000 soak.ticker_max_oversleep_ms=48.000
RESULT soak.no_deadlock=PASS
RESULT soak.all_events_received=PASS
RESULT soak.latency_max_below_50ms=PASS
RESULT soak.ticker_max_below_50ms=PASS
RESULT failures=0
'''
PREFLIGHT = {'logical_cpus': 10, 'ready': True,
             'thresholds': {'min_idle_percent': 80, 'max_load_per_cpu': 0.5},
             'samples': [{'ready': True, 'idle_percent': 90, 'load': [1, 1, 1]} for _ in range(6)]}


class QualificationChecks(unittest.TestCase):
    def test_complete_receipt_and_original_assertions_are_required(self):
        gate.check_run(RECORD, OUTPUT, BINARY)
        for output in ('', OUTPUT.replace('soak.no_deadlock=PASS', ''),
                       OUTPUT.replace('latency_max_below_50ms=PASS', 'latency_max_below_50ms=FAIL'),
                       OUTPUT + 'RESULT failures=0\n'):
            with self.subTest(output=output), self.assertRaises((ValueError, KeyError)):
                gate.check_run(RECORD, output, BINARY)

    def test_failed_crashed_shortened_or_substituted_run_is_refused(self):
        for change in ({'returncode': 1}, {'returncode': -6}, {'timed_out': True},
                       {'binary_sha256': 'changed'}, {'timeout_seconds': 900},
                       {'command': [str(BINARY), 'soak', '60', '4', '1000']},
                       {'environment': dict(gate.ENVIRONMENT, GC_MAXIMUM_HEAP_SIZE='256M')}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                gate.check_run(dict(RECORD, **change), OUTPUT, BINARY)

    def test_metrics_cannot_hide_loss_absent_progress_or_an_exceeded_bound(self):
        for before, after in [('seconds=600.0', 'seconds=60.0'), ('workers=4', 'workers=1'),
                              ('events_received=1000', 'events_received=999'),
                              ('push_failures=0', 'push_failures=1'),
                              ('alloc_iterations=100000', 'alloc_iterations=0'),
                              ('latency_max_ms=49.000', 'latency_max_ms=51.000'),
                              ('ticker_max_oversleep_ms=48.000', 'ticker_max_oversleep_ms=nan'),
                              ('gc_heap_mb=34.1', 'gc_heap_mb=129')]:
            with self.subTest(after=after), self.assertRaises(ValueError):
                gate.check_run(RECORD, OUTPUT.replace(before, after), BINARY)

    def test_rounding_does_not_override_the_probes_nanosecond_assertion(self):
        gate.check_run(RECORD, OUTPUT.replace('latency_max_ms=49.000', 'latency_max_ms=50.000'), BINARY)
        with self.assertRaises(ValueError):
            gate.check_run(RECORD, OUTPUT.replace('latency_max_below_50ms=PASS',
                                                  'latency_max_below_50ms=FAIL'), BINARY)

    def test_ready_flag_cannot_override_busy_missing_or_changed_samples(self):
        self.assertTrue(gate.ready(PREFLIGHT))
        for change in ('busy', 'missing', 'threshold', 'load', 'error'):
            record = copy.deepcopy(PREFLIGHT)
            if change == 'busy':
                record['samples'][2]['idle_percent'] = 79
            elif change == 'missing':
                record['samples'].pop()
            elif change == 'threshold':
                record['thresholds']['min_idle_percent'] = 70
            elif change == 'load':
                record['samples'][0]['load'][0] = 6
            else:
                record['error'] = 'top failed'
            with self.subTest(change=change):
                self.assertFalse(gate.ready(record))

    def test_changed_binary_is_refused_before_any_child_is_started(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / 'changed'
            binary.write_bytes(b'not the qualified candidate')
            output = Path(directory) / 'output'
            with patch.object(gate.platform, 'system', return_value='Darwin'), \
                    patch.object(gate.platform, 'machine', return_value='arm64'), \
                    patch.object(gate.subprocess, 'run') as child:
                with self.assertRaises(ValueError):
                    gate.execute(binary, output)
                child.assert_not_called()
            self.assertFalse(output.exists())

    def exercise_runner(self, root, flight_code=0, gate_code=0):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            folder = Path(command[command.index('--out') + 1])
            folder.mkdir()
            if command[1].endswith('check-idle.py'):
                (folder / 'result.json').write_text(json.dumps(PREFLIGHT))
                return subprocess.CompletedProcess(command, flight_code)
            self.assertEqual(command[-5:], [str(BINARY), 'soak', '600', '4', '1000'])
            self.assertEqual({key: kwargs['env'][key] for key in gate.ENVIRONMENT}, gate.ENVIRONMENT)
            (folder / 'result.json').write_text(json.dumps(RECORD))
            (folder / 'stdout.log').write_text(OUTPUT)
            return subprocess.CompletedProcess(command, gate_code)

        with patch.object(gate.platform, 'system', return_value='Darwin'), \
                patch.object(gate.platform, 'machine', return_value='arm64'), \
                patch.object(gate.Path, 'resolve', return_value=BINARY), \
                patch.object(gate, 'digest', return_value=gate.BINARY_SHA256), \
                patch.object(gate.shutil, 'copy2'), patch.object(gate.subprocess, 'run', side_effect=run):
            code = gate.execute(BINARY, root / 'trial')
        return code, calls, json.loads((root / 'trial/result.json').read_text())

    def test_busy_preflight_never_launches_the_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            code, calls, result = self.exercise_runner(Path(directory), flight_code=2)
        self.assertEqual(code, 2)
        self.assertEqual(len(calls), 1)
        self.assertEqual(result['status'], 'host_unavailable')
        self.assertFalse(result['qualification'])

    def test_gate_failure_is_preserved_without_a_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            code, calls, result = self.exercise_runner(Path(directory), gate_code=1)
        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 2)
        self.assertEqual(result['status'], 'gate_failed')
        self.assertFalse(result['qualification'])

    def test_three_independent_preflights_and_gates_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            code, calls, result = self.exercise_runner(Path(directory))
        self.assertEqual(code, 0)
        self.assertEqual(len(calls), 6)
        self.assertEqual(len(result['trials']), 3)
        self.assertTrue(result['qualification'])


if __name__ == '__main__':
    unittest.main()
