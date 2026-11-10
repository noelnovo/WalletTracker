"""Guard real dependency remediation rather than scanner/gate suppression."""
from pathlib import Path
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[2]


class SecurityDependencyTests(unittest.TestCase):
    def test_patched_families_are_pinned_in_one_catalog(self):
        versions = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())['versions']
        expected = {'security-netty': '4.1.140.Final', 'security-bouncycastle': '1.86',
            'security-opentelemetry': '1.68.0', 'security-guava': '33.8.0',
            'security-protobuf': '3.25.9', 'security-commons-lang3': '3.21.0',
            'security-httpclient': '4.5.14'}
        for key, value in expected.items():
            self.assertEqual(versions[key], value)

    def test_configuration_scope_and_variant_families_stay_intact(self):
        build = (ROOT / 'build.gradle.kts').read_text()
        self.assertIn('configurations.configureEach', build)
        self.assertIn('resolutionStrategy.eachDependency', build)
        self.assertIn('!requested.name.startsWith("netty-tcnative")', build)
        self.assertIn('requested.version?.endsWith("-android")', build)
        self.assertIn('"-android" else "-jre"', build)
        self.assertNotIn('skipConfigs', build)
        self.assertNotIn('includeConfigs', build)
        self.assertNotIn('excludeConfigs', build)


if __name__ == '__main__':unittest.main()
