"""Measured scenario ownership. A label inventory never supplies a PASS by itself."""
import json
import xml.etree.ElementTree as ET

AREAS = {
    'source_selection': ('domain', 'boundaries', 'picker', 'installed_controls'),
    'research_boundaries': ('boundaries', 'ai_tools', 'installed_controls'),
    'review_approval': ('domain', 'boundaries', 'basket', 'concurrency', 'mcp_socket'),
    'preparation_failure_recovery': ('storage', 'boundaries', 'concurrency', 'real_preparation_verify', 'restart_verify'),
    'six_operators_three_helpers': ('journey', 'update', 'templates'),
    'exact_negative_readback': (),
    'installer_recovery': ('journey', 'update', 'split_restore', 'cancellation', 'permission', 'boundaries', 'platform_session_verify'),
    'all_controls_and_offline_guides': ('journey', 'basket', 'ui_cancel', 'installed_controls', 'split_restore', 'picker'),
    'startup_scroll_main_thread_io': ('performance',),
    'hud_mcp_blackwave_regression': ('mcp_socket', 'browser_regression'),
}
CONTROLS = {
    'choose_apks': 'picker', 'choose_installed': 'installed_controls', 'research_requests': 'installed_controls',
    'request_ai_ideas': 'installed_controls', 'search_evidence': 'installed_controls', 'select_suggestion': 'basket',
    'preview': 'basket', 'approve': 'journey', 'cancel': 'ui_cancel', 'resume': 'installed_controls',
    'install': 'journey', 'verify_installed': 'journey', 'record_observation': 'journey', 'restore_original': 'split_restore',
}
LABELS = {
    'Allow Android installation from HUD': 'installed_controls', 'Analyse installed app': 'installed_controls',
    'Approve exact changes and prepare copy': 'journey', 'Cancel current work': 'ui_cancel',
    'Check installed artifact': 'journey', 'Choose APK or base + split APKs': 'picker',
    'Choose installed app': 'installed_controls', 'Confirm public repository: owner/repository': 'installed_controls',
    'Confirm repository and research requests': 'installed_controls', 'Executable suggestions only': 'basket',
    'Expert: import compatible Xposed module': 'installed_controls', 'Find installed app': 'installed_controls',
    'Find local suggestions': 'installed_controls', 'I checked the changed behavior': 'journey',
    'Inspect feature map': 'installed_controls', 'Installed package': 'installed_controls',
    'Offline walkthrough and recovery': 'installed_controls', 'Open analysis, saved jobs and offline guides': 'journey',
    'Reopen analysis / resume interrupted analysis': 'installed_controls',
    'Replacement (colour: 8-digit ARGB hex)': 'basket', 'Resume source analysis': 'ui_cancel',
    'Review archived original restoration': 'split_restore', 'Review in Android installer': 'journey',
    'Review removing the separate app': 'journey', 'Review saved preview / resume': 'journey',
    'Search local feature evidence': 'installed_controls', 'Search suggestions': 'basket',
    'Suggest with configured AI': 'installed_controls', 'What would you like this app to do?': 'installed_controls',
}
EXACT_CASES = (
    'exactVerifierAcceptsCombinedChanges', 'exactVerifierAcceptsAllHelperSequencesAndRejectsWrongAddressBindings',
    'exactVerifierRejectsUnrelatedResourceMutation', 'exactVerifierRejectsUnrelatedMethodMutation',
    'exactVerifierRejectsUnexpectedFileAndManifest', 'exactVerifierRejectsUnchangedTargetWithReplacementElsewhereInMethod',
    'exactVerifierRequiresPackagedHelperBytes',
)
PROOFS = {
    'exact_negative_readback': {'com.mcpintelligence.fr3k.appcontrol.analysis.AppModExecutionTest': EXACT_CASES},
    'six_operators_three_helpers': {'com.mcpintelligence.fr3k.appcontrol.analysis.AppModExecutionTest': EXACT_CASES[:2]},
    'hud_mcp_blackwave_regression': {
        'com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveLocalParametersTest': ('typedParametersAndQuotedBracesRemainData', 'unboundedOrMalformedParametersAreRefusedBeforeTheJsonParser'),
        'com.mcpintelligence.fr3k.appcontrol.analysis.AppModCatalogueTest': ('everyPublishedEntryReferencesAnOfflineGuide',),
    },
}


def derive(run, manifest, repo):
    inventory = json.loads((repo / 'docs/app-control/evidence/appmod-coverage-inventory.json').read_text())
    membership = set(inventory['controls']) == set(CONTROLS) and set(inventory['staticControlLabels']) == set(LABELS)
    result = {}
    for area, names in AREAS.items():
        refs = []
        passed = True
        for api in (31, 35):
            for name in names:
                scenario = manifest['scenarios'].get(f'api{api}/{name}', {})
                passed &= scenario.get('status') == 'PASS'
                if scenario.get('log'): refs.append(scenario['log'])
            if area == 'startup_scroll_main_thread_io':
                passed &= 0 < manifest['scenarios'].get(f'api{api}/performance', {}).get('coldStartMs', 0) < 10000
        for cls, cases in PROOFS.get(area, {}).items():
            for module in ('app', 'app_release'):
                relative = f'jvm/{module}/TEST-{cls}.xml'
                path = run / relative
                refs.append(relative)
                if not path.exists():
                    passed = False; continue
                root = ET.parse(path).getroot()
                measured = {test.get('name') for test in root.findall('testcase') if test.find('failure') is None and test.find('error') is None and test.find('skipped') is None}
                passed &= set(cases) <= measured
        if area == 'hud_mcp_blackwave_regression':
            refs += [r for r in manifest['evidence'] if r.startswith('jvm/') and r.endswith('.xml')]
        if area == 'all_controls_and_offline_guides':
            passed &= membership
        result[area] = dict(status='PASS' if passed else 'FAIL', evidence=sorted(set(refs)), scenarios=list(names))
        if area == 'all_controls_and_offline_guides':
            result[area].update(controls=CONTROLS, labels=LABELS, guides={g: 'installed_controls' for g in ('mod.discovery', 'mod.preparation', 'mod.install.restore')},
                                research_ui_scope='LOCAL_ONLY_REFUSAL; deterministic injected provider tested separately; configured live provider remains a separate gate')
    return result
