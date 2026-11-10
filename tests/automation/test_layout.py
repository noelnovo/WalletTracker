"""Keep documentation and CI entry points aligned with the categorized layout."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class LayoutTests(unittest.TestCase):
    def test_workflow_modules_exist_and_flat_delivery_commands_are_gone(self):
        self.assertTrue((ROOT / '.gitlab-ci.yml').is_file())
        self.assertFalse((ROOT / '.github/workflows/android.yml').exists())
        self.assertFalse((ROOT / '.github/workflows/validation.yml').exists())
        self.assertNotIn('PYTHONPATH=delivery', (ROOT / '.gitlab-ci.yml').read_text())
        for suite in ('tests/automation', 'ci'):
            self.assertIn(f"discover -s {suite}", (ROOT / '.gitlab-ci.yml').read_text())

    def test_local_documentation_links_resolve(self):
        for document in [ROOT / 'README.md', *(ROOT / 'docs').rglob('*.md')]:
            for target in re.findall(r'\]\(([^)]+)\)', document.read_text()):
                target = target.split('#')[0]
                if target and not target.startswith(('https:', 'http:', 'mailto:')):
                    self.assertTrue((document.parent / target).exists(), f'{document}: {target}')
