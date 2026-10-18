import copy
import json
from pathlib import Path
import tempfile
import zipfile
import unittest
from unittest.mock import patch
import evidence as e
from coverage_contract import PROOFS


class EvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.repo = self.root / 'repo'; self.repo.mkdir()
        self.run = self.root / 'run'; self.run.mkdir()
        for relative in e.REQUIRED_ARTIFACTS:
            path = self.repo / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'fixture')
        (self.repo / e.REQUIRED_ARTIFACTS[2]).write_bytes(b'helper')
        self.candidate = self.repo / e.REQUIRED_ARTIFACTS[0]
        with zipfile.ZipFile(self.candidate, 'w') as archive:
            archive.writestr('assets/appmod/classes.dex', b'helper')
        self.candidate_bytes = self.candidate.read_bytes()
        inventory = self.repo / 'docs/app-control/evidence/appmod-coverage-inventory.json'
        inventory.parent.mkdir(parents=True)
        inventory.write_text((e.REPO / 'docs/app-control/evidence/appmod-coverage-inventory.json').read_text())
        pins = json.loads((e.REPO / 'docs/app-control/evidence/appmod-dependency-pins.json').read_text())
        e.write_json(inventory.parent / 'appmod-dependency-pins.json', pins)
        e.write_json(self.run / 'dependency-verification.json', {pin['coordinate']: {'jarSha256': pin['jarSha256'], 'pomSha256': pin['pomSha256']} for pin in pins['dependencies']})
        self.manifest = dict(schema='hud-appmod-run/2', status='PASS', source={'source.kt': 'bound'}, artifacts={p: e.sha256(self.repo / p) for p in e.REQUIRED_ARTIFACTS}, scenarios={}, evidence={}, jvm={}, coverage={})
        (self.run / 'gradle.log').write_text('BUILD SUCCESSFUL')
        (self.run / 'host-contract.log').write_text('\n'.join(name + ' (contract) ... ok' for name in e.HOST_CASES) + '\nRan ' + str(len(e.HOST_CASES)) + ' tests\nOK\n')
        self.manifest['host'] = dict(status='PASS', log='host-contract.log', tests=len(e.HOST_CASES))
        for api in (31, 35):
            folder = self.run / f'api{api}'; folder.mkdir(exist_ok=True)
            (folder / 'intentional-process-death.log').write_text('APPMOD_REAL_PREPARING_CHECKPOINT\nRUNNER_EXPECTED_FORCE_STOP\n')
            for name, (_, count, markers) in e.SCENARIOS.items():
                key = f'api{api}/{name}'
                log = key + '.log'; path = self.run / log; path.parent.mkdir(exist_ok=True)
                path.write_text('\n'.join(markers) + f'\nOK ({count} tests)\nINSTRUMENTATION_CODE: -1\n')
                self.manifest['scenarios'][key] = dict(status='PASS', log=log, candidate=copy.deepcopy(self.manifest['artifacts']), coldStartMs=100)
        for module in e.MODULES:
            folder = self.run / 'jvm' / module; folder.mkdir(parents=True)
            (folder / 'TEST-fixture.xml').write_text('<testsuite tests="1" failures="0" skipped="0" errors="0"/>')
            self.manifest['jvm'][module] = dict(tests=1, failures=0, skipped=0, errors=0)
        cases = {}
        for proof in PROOFS.values():
            for cls, names in proof.items():
                cases.setdefault(cls, set()).update(names)
        for module in ('app', 'app_release'):
            for cls, names in cases.items():
                path = self.run / 'jvm' / module / ('TEST-' + cls + '.xml')
                path.write_text('<testsuite tests="' + str(len(names)) + '" failures="0" skipped="0" errors="0">' + ''.join('<testcase name="' + name + '"/>' for name in sorted(names)) + '</testsuite>')
            self.manifest['jvm'][module] = e.xml_counts(list((self.run / 'jvm' / module).glob('*.xml')))
        self.manifest['evidence'] = {str(p.relative_to(self.run)): e.sha256(p) for p in self.run.rglob('*') if p.is_file()}
        self.manifest['coverage'] = e.derive_coverage(self.run, self.manifest, self.repo)
        self.patches = [patch.object(e, 'REPO', self.repo), patch.object(e, 'snapshot', return_value={'source.kt': 'bound'})]
        for p in self.patches: p.start()

    def tearDown(self):
        for p in self.patches: p.stop()
        self.tmp.cleanup()

    def validate(self):
        e.write_json(self.run / 'run-manifest.json', self.manifest)
        return e.validate(self.run)

    def test_complete_matching_manifest_passes(self):
        self.assertEqual('PASS', self.validate()['status'])

    def test_missing_failed_or_skipped_scenario_refused(self):
        key = next(iter(self.manifest['scenarios']))
        original = copy.deepcopy(self.manifest['scenarios'])
        for status in ('FAIL', 'SKIPPED', 'RUNNING', None):
            self.manifest['scenarios'] = copy.deepcopy(original)
            if status is None: del self.manifest['scenarios'][key]
            else: self.manifest['scenarios'][key]['status'] = status
            with self.assertRaises(ValueError): self.validate()

    def test_mismatched_candidate_refused(self):
        next(iter(self.manifest['scenarios'].values()))['candidate'] = {'candidate.apk': 'different'}
        with self.assertRaises(ValueError): self.validate()

    def test_changed_evidence_refused(self):
        (self.run / 'api31/cancellation.log').write_text('OK (1 tests)\nINSTRUMENTATION_CODE: -1')
        with self.assertRaises(ValueError): self.validate()

    def test_missing_coverage_and_unbound_logs_refused(self):
        self.manifest['coverage'].pop(e.REQUIRED_COVERAGE[0])
        with self.assertRaises(ValueError): self.validate()
        self.manifest['coverage'] = e.derive_coverage(self.run, self.manifest, self.repo)
        self.manifest['evidence'].pop('api31/cancellation.log')
        with self.assertRaises(ValueError): self.validate()

    def test_zero_wrong_skipped_or_partial_instrumentation_refused(self):
        for content in ('OK (0 tests)\nINSTRUMENTATION_CODE: -1', 'OK (2 tests)\nINSTRUMENTATION_CODE: -1', 'OK (1 test)', 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -3\nINSTRUMENTATION_CODE: -1'):
            with self.assertRaises(ValueError): e.instrument_count(content, 1, ())

    def test_labels_or_build_log_alone_are_not_interaction_evidence(self):
        self.manifest['coverage'] = {area: dict(status='PASS', evidence=['gradle.log']) for area in e.REQUIRED_COVERAGE}
        with self.assertRaises(ValueError): self.validate()

    def test_missing_named_negative_readback_assertion_refused(self):
        path = self.run / 'jvm/app/TEST-com.mcpintelligence.fr3k.appcontrol.analysis.AppModExecutionTest.xml'
        path.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="unrelated"/></testsuite>')
        relative = str(path.relative_to(self.run))
        self.manifest['evidence'][relative] = e.sha256(path)
        self.manifest['jvm']['app'] = e.xml_counts(list((self.run / 'jvm/app').glob('*.xml')))
        self.manifest['coverage'] = e.derive_coverage(self.run, self.manifest, self.repo)
        with self.assertRaises(ValueError): self.validate()

    def test_candidate_and_source_drift_refused(self):
        self.candidate.write_bytes(b'changed')
        with self.assertRaises(ValueError): self.validate()
        for relative in e.REQUIRED_ARTIFACTS:
            path = self.repo / relative; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'fixture')
        (self.repo / e.REQUIRED_ARTIFACTS[2]).write_bytes(b'helper')
        self.candidate = self.repo / e.REQUIRED_ARTIFACTS[0]
        with zipfile.ZipFile(self.candidate, 'w') as archive:
            archive.writestr('assets/appmod/classes.dex', b'helper')
        self.candidate_bytes = self.candidate.read_bytes()
        self.manifest['source'] = {}
        with self.assertRaises(ValueError): self.validate()


if __name__ == '__main__': unittest.main()
