#!/usr/bin/env python3
"""Generate source coverage inventories. This never treats source presence as interaction PASS."""
from pathlib import Path
import hashlib, json, re
ROOT = Path(__file__).resolve().parents[1]
base = ROOT / 'app/src/main/java/com/mcpintelligence/fr3k'
source = (base / 'appcontrol/AppModCatalogue.kt').read_text()
guides = set(re.findall(r'releaseGuide\("(mod\.[^"]+)"', (base / 'appcontrol/ReleaseGuides.kt').read_text()))
sections = {}
for name in ['operators', 'helpers', 'tools', 'controls']:
    block = source.split(f'val {name} = linkedMapOf(', 1)[1].split(')', 1)[0]
    sections[name] = dict(re.findall(r'"([^"]+)" to "([^"]+)"', block))
    assert set(sections[name].values()) <= guides, name
expected = {
    'operators': {'resource_string', 'resource_color', 'layout_attribute', 'dex_constant', 'helper_call', 'hook_template'},
    'helpers': {'url.clean', 'preference.persist', 'hud.share'},
    'tools': {'analyze_apk', 'suggest_app_mods', 'preview_app_mods', 'request_app_mod_preparation', 'get_app_mod_job'},
}
for name, members in expected.items():
    assert set(sections[name]) == members, (name, set(sections[name]) ^ members)
ui = (base / 'ui/appcontrol/AppModDiscoveryActivity.kt').read_text()
labels = sorted(set(re.findall(r'Text\("([^"$]+)"\)', '\n'.join(line for line in ui.splitlines() if any(word in line for word in ['Button(', 'label =', 'Checkbox(', 'Switch('])))))
tests = {}
for path in sorted((ROOT / 'app/src/androidTest').rglob('AppMod*Test.kt')):
    tests[str(path.relative_to(ROOT))] = re.findall(r'@Test fun ([A-Za-z0-9_]+)', path.read_text())
fixtures = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT / 'app/src/main/assets/app-control').glob('*fixture*.apk'))}
split = ROOT / 'app/src/main/assets/app-control/appmod-feature-split.apk'
fixtures[split.name] = hashlib.sha256(split.read_bytes()).hexdigest()
output = ROOT / 'docs/app-control/evidence/appmod-coverage-inventory.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'schema': 'hud-appmod-inventory/1', 'guideReferences': 'PASS', 'interactionCoverage': 'NOT_INFERRED_FROM_INVENTORY', **sections,
    'staticControlLabels': labels, 'deviceTests': tests, 'fixtureSha256': fixtures}, indent=2) + '\n')
print(output)
