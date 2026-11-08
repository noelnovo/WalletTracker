"""Keep documentation and CI entry points aligned with the categorized layout."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


class LayoutTests(unittest.TestCase):
    def test_workflow_modules_exist_and_flat_delivery_commands_are_gone(self):
        for name in ('android.yml', 'validation.yml'):
            workflow = (ROOT / '.github/workflows' / name).read_text()
            self.assertNotIn('PYTHONPATH=delivery', workflow)
            self.assertNotIn('delivery/', workflow)
            if name == 'validation.yml':
                self.assertIn('discover -s tests/automation', workflow)
            for module in re.findall(r'python3 -m (scripts[\w.]+)', workflow):
                self.assertTrue((ROOT / (module.replace('.', '/') + '.py')).is_file(), module)

    def test_local_documentation_links_resolve(self):
        for document in [ROOT / 'README.md', *(ROOT / 'docs').rglob('*.md')]:
            for target in re.findall(r'\]\(([^)]+)\)', document.read_text()):
                target = target.split('#')[0]
                if target and not target.startswith(('https:', 'http:', 'mailto:')):
                    self.assertTrue((document.parent / target).exists(), f'{document}: {target}')
