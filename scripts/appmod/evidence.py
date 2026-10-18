"""Shared, fail-closed contract for one frozen app-modification acceptance run."""
import hashlib
import json
import re
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from coverage_contract import derive as derive_coverage

REPO = Path(__file__).resolve().parents[2]
SOURCE_PATTERNS = ('*/src/**/*', 'app/libs/*', 'qualification/**/*', '*gradle*', '*/build.gradle.kts', 'gradle/**/*',
                   'docs/app-control/evidence/*.json', 'scripts/appmod/*.py',
                   'scripts/release/package_appmod_hud_release.py', 'scripts/generate_appmod_inventory.py')

MODULES = {'capability': 'capability/build/test-results/test/TEST-*.xml',
           'core': 'core/build/test-results/testDebugUnitTest/TEST-*.xml',
           'app': 'app/build/test-results/testDebugUnitTest/TEST-*.xml',
           'app_release': 'app/build/test-results/testReleaseUnitTest/TEST-*.xml'}
REQUIRED_ARTIFACTS = ('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
                      'app/build/generated/appmod/assets/appmod/classes.dex') + tuple('app/src/main/assets/app-control/' + name for name in
                      ('appmod-media-fixture.apk', 'appmod-gallery-fixture.apk', 'appmod-feature-split.apk', 'appmod-feature-split.apk.idsig', 'owned-fixture.apk', 'owned-fixture-hook.apk'))
HOST_CASES = ('test_complete_matching_manifest_passes', 'test_missing_failed_or_skipped_scenario_refused', 'test_mismatched_candidate_refused',
              'test_changed_evidence_refused', 'test_missing_coverage_and_unbound_logs_refused', 'test_zero_wrong_skipped_or_partial_instrumentation_refused',
              'test_candidate_and_source_drift_refused', 'test_labels_or_build_log_alone_are_not_interaction_evidence', 'test_missing_named_negative_readback_assertion_refused')
JOURNEY = 'AppModJourneyDeviceTest'
# Separate invocations prevent permission, picker and installer state contaminating another scenario.
SCENARIOS = {
    'domain': ('AppModDeviceTest', 3, ()),
    'migration': ('AppModMigrationDeviceTest', 1, ()),
    'templates': ('AppModTemplateDeviceTest', 3, ('APPMOD_INSERTED_SHARE_BEHAVIOR_PASS',)),
    'boundaries': ('AppModBoundaryDeviceTest', 4, ()),
    'storage': ('AppModStorageDeviceTest', 1, ()),
    'concurrency': ('AppModConcurrencyDeviceTest', 4, ()),
    'journey': (JOURNEY + '#actualReviewInstallerAndModifiedBehavior', 1, ('APPMOD_BEHAVIOR_PASS',)),
    'ui_cancel': (JOURNEY + '#cancelControlCancelsOnlyItsQueuedAnalysis', 1, ('APPMOD_UI_CANCEL_PASS',)),
    'basket': (JOURNEY + '#selectionBasketPreviewAndDraftRecoveryUseActualControls', 1, ('APPMOD_BASKET_DRAFT_PREVIEW_PASS',)),
    'update': (JOURNEY + '#actualReviewInstallerAndModifiedBehavior', 1, ('APPMOD_BEHAVIOR_PASS',)),
    'split_restore': (JOURNEY + '#splitSetInstallAndArchivedOriginalRestoration', 1, ('APPMOD_SPLIT_AND_RESTORE_PASS', 'APPMOD_SIGNER_CONFLICT_PASS')),
    'cancellation': (JOURNEY + '#installerCancellationRetainsPreparedCopy', 1, ('APPMOD_INSTALL_CANCEL_BUTTON', 'explicitButton=true retry=true')),
    'picker': (JOURNEY + '#documentPickerImportsWithoutStoragePermission', 1, ('APPMOD_DOCUMENT_PICKER_PASS',)),
    'installed_controls': (JOURNEY + '#installedSelectionEvidenceSearchAndOfflineGuidesUseActualControls', 1, ('APPMOD_INSTALLED_SELECTION_AND_EVIDENCE_PASS', 'APPMOD_GUIDE_INTERACTION_PASS id=mod.discovery', 'APPMOD_GUIDE_INTERACTION_PASS id=mod.preparation', 'APPMOD_GUIDE_INTERACTION_PASS id=mod.install.restore')),
    'ai_tools': (JOURNEY + '#aiBoundaryAndAllFiveTools', 1, ('APPMOD_AI_BOUNDARY_AND_TOOLS_PASS',)),
    'mcp_socket': ('AppModMcpDeviceTest', 1, ('APPMOD_MCP_SOCKET_PASS',)),
    'browser_regression': ('com.mcpintelligence.fr3k.VisibleToolsDeviceTest#browserControlsActualPageAndReportsFailures', 1, ()),
    'permission': ('AppModPermissionDeniedDeviceTest', 1, ()),
    'restart_create': ('AppModRestartDeviceTest#createRestartCheckpoint', 1, ()),
    'restart_verify': ('AppModRestartDeviceTest#verifyRestartCheckpoint', 1, ()),
    'real_preparation_verify': ('AppModRestartDeviceTest#recoverRealPreparationAfterHostProcessDeath', 1, ('APPMOD_REAL_PREPARATION_RECOVERY_PASS',)),
    'platform_session_create': ('AppModRestartDeviceTest#createPlatformSessionCheckpoint', 1, ()),
    'platform_session_verify': ('AppModRestartDeviceTest#verifyPlatformSessionAfterProcessDeath', 1, ('APPMOD_PLATFORM_SESSION_RESTART_PASS',)),
    'performance': ('AppModPerformanceDeviceTest', 1, ('APPMOD_PERFORMANCE_MEASURED',)),
    'accessibility': ('AppModAccessibilityDeviceTest', 1, ('APPMOD_ACCESSIBILITY_LABELS_PASS', 'fontScale=1.0')),
    'small_200': (JOURNEY + '#actualReviewInstallerAndModifiedBehavior', 1, ('APPMOD_BEHAVIOR_PASS',)),
    'small_200_accessibility': ('AppModAccessibilityDeviceTest', 1, ('APPMOD_ACCESSIBILITY_LABELS_PASS', 'fontScale=2.0')),
    'tablet_accessibility': ('AppModAccessibilityDeviceTest', 1, ('APPMOD_ACCESSIBILITY_LABELS_PASS',)),
    'reduced_motion_accessibility': ('AppModAccessibilityDeviceTest', 1, ('APPMOD_ACCESSIBILITY_LABELS_PASS',)),
}
# These require dedicated assertions; label discovery and source presence do not satisfy them.
REQUIRED_COVERAGE = ('source_selection', 'research_boundaries', 'review_approval', 'preparation_failure_recovery',
                     'six_operators_three_helpers', 'exact_negative_readback', 'installer_recovery',
                     'all_controls_and_offline_guides', 'startup_scroll_main_thread_io', 'hud_mcp_blackwave_regression')


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def snapshot():
    files = {p for pattern in SOURCE_PATTERNS for p in REPO.glob(pattern) if p.is_file() and '.gradle/' not in str(p.relative_to(REPO)) and '/build/' not in str(p.relative_to(REPO))}
    return {str(p.relative_to(REPO)): sha256(p) for p in sorted(files)}


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def instrument_count(content, expected, markers):
    if re.search(r'FAILURES|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1234]|INSTRUMENTATION_ABORTED', content):
        raise ValueError('instrumentation failed, skipped or crashed')
    matches = re.findall(r'^OK \((\d+) tests?\)', content, re.M)
    if matches != [str(expected)] or 'INSTRUMENTATION_CODE: -1' not in content:
        raise ValueError('missing/mismatched instrumentation test count or completion')
    for marker in markers:
        if marker not in content:
            raise ValueError('missing marker: ' + marker)
    return expected


def xml_counts(reports):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    if not reports:
        raise ValueError('missing JVM XML')
    for path in reports:
        root = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(root.get(key, 0))
    if not totals['tests'] or any(totals[k] for k in ('failures', 'errors', 'skipped')):
        raise ValueError('nonpassing JVM XML: ' + str(totals))
    return totals


def validate(run, require_coverage=True):
    run = Path(run).resolve()
    manifest = json.loads((run / 'run-manifest.json').read_text())
    if manifest.get('schema') != 'hud-appmod-run/2' or manifest.get('status') != 'PASS':
        raise ValueError('selected run is incomplete or failed')
    if manifest['source'] != snapshot():
        raise ValueError('sources changed after candidate freeze')
    if not set(REQUIRED_ARTIFACTS) <= set(manifest['artifacts']):
        raise ValueError('missing frozen APK/test APK/fixture/helper hashes')
    for relative, digest in manifest['artifacts'].items():
        if sha256(REPO / relative) != digest:
            raise ValueError('candidate/fixture/helper mismatch: ' + relative)
    with zipfile.ZipFile(REPO / REQUIRED_ARTIFACTS[0]) as apk:
        if hashlib.sha256(apk.read('assets/appmod/classes.dex')).hexdigest() != manifest['artifacts'][REQUIRED_ARTIFACTS[2]]:
            raise ValueError('packaged helper differs from frozen reference')
    if 'dependency-verification.json' not in manifest['evidence']:
        raise ValueError('missing measured dependency verification')
    pins = json.loads((REPO / 'docs/app-control/evidence/appmod-dependency-pins.json').read_text())
    expected_dependencies = {pin['coordinate']: {'jarSha256': pin['jarSha256'], 'pomSha256': pin['pomSha256']} for pin in pins['dependencies']}
    if json.loads((run / 'dependency-verification.json').read_text()) != expected_dependencies:
        raise ValueError('dependency verification differs from frozen pins')
    for relative, digest in manifest['evidence'].items():
        path = (run / relative).resolve()
        if not path.is_relative_to(run) or sha256(path) != digest:
            raise ValueError('evidence mismatch: ' + relative)
    host = manifest.get('host', {})
    if host.get('status') != 'PASS' or host.get('log') not in manifest['evidence']:
        raise ValueError('host evidence contract checks missing or failing')
    host_log = (run / host['log']).read_text()
    host_count = re.search(r'Ran (\d+) tests?', host_log)
    if not host_count or int(host_count.group(1)) != host.get('tests') or int(host_count.group(1)) < len(HOST_CASES) or re.search(r'^FAILED|^ERROR|\.\.\. skipped', host_log, re.M):
        raise ValueError('host contract results incomplete')
    if any(not re.search(r'^' + name + r' .*\.\.\. ok$', host_log, re.M) for name in HOST_CASES):
        raise ValueError('required host rejection assertions missing')
    if 'gradle.log' not in manifest['evidence']:

        raise ValueError('build evidence not bound')
    if 'BUILD SUCCESSFUL' not in (run / 'gradle.log').read_text():
        raise ValueError('build gate missing')
    for api in (31, 35):
        death_log = f'api{api}/intentional-process-death.log'
        if death_log not in manifest['evidence']:
            raise ValueError('missing intentional process-death evidence')
        death = (run / death_log).read_text()
        if 'APPMOD_REAL_PREPARING_CHECKPOINT' not in death or 'RUNNER_EXPECTED_FORCE_STOP' not in death or 'OK (' in death:
            raise ValueError('real preparation process death not established')
        for name, (_, count, markers) in SCENARIOS.items():
            result = manifest['scenarios'].get(f'api{api}/{name}')
            if not result or result['status'] != 'PASS' or result['candidate'] != manifest['artifacts']:
                raise ValueError('missing/failing/mismatched scenario: ' + str((api, name)))
            if result['log'] not in manifest['evidence']:
                raise ValueError('scenario log not bound')
            instrument_count((run / result['log']).read_text(), count, markers)
    for report in (run / 'jvm').rglob('*.xml'):
        if str(report.relative_to(run)) not in manifest['evidence']:
            raise ValueError('JVM report not bound')
    counts = {name: xml_counts(sorted((run / 'jvm' / name).glob('*.xml'))) for name in MODULES}
    if counts != manifest['jvm']:
        raise ValueError('JVM counts mismatch')
    if require_coverage:
        if manifest['coverage'] != derive_coverage(run, manifest, REPO):
            raise ValueError('coverage does not match measured scenarios and named JVM assertions')
        for area in REQUIRED_COVERAGE:
            coverage = manifest['coverage'].get(area, {})
            if coverage.get('status') != 'PASS' or not coverage.get('evidence') or any(ref not in manifest['evidence'] for ref in coverage['evidence']):
                raise ValueError('mandatory measured coverage incomplete: ' + area)
    return manifest
