import json
from pathlib import Path
import unittest

from generate_localization import CATALOG, generate


class LocalizationCatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads(CATALOG.read_text())

    def test_english_and_chinese_outputs_are_complete_and_current(self):
        for path, content in generate(self.catalog).items():
            self.assertEqual(content, path.read_text(), str(path))

    def test_placeholder_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Placeholder mismatch'):
            generate({'Send %1$s BNT': '支付 %2$s BNT'})

    def test_security_copy_and_language_choices_are_bundled(self):
        for key in ('Follow phone language', 'Simplified Chinese',
                    'Review every detail. Broadcast transactions cannot be reversed.',
                    'Incorrect wallet password or damaged saved view.',
                    'Shortname returned an invalid address',
                    'Shortname could not be checked',
                    'Offline: saved names and check times may be stale. Reconnect to add, edit, or verify recipients; sending is unavailable.'):
            self.assertIn(key, self.catalog)
            self.assertNotEqual(key, self.catalog[key])

    def test_sensitive_values_use_raw_ui_paths(self):
        source = (CATALOG.parents[1] / 'app/src/main/java/com/blocknet/wallet/MainActivity.java').read_text()
        for fragment in ('ui.rawButton(word,', 'ui.rawButton(file,',
                         'ui.rawText(bookName(entry),', 'ui.rawText(repo.activeWallet(),',
                         'hero("Welcome back.",repo.activeWallet(),true)',
                         'ui.rawText(value,size,WalletTheme.TEXT)', 'ui.rawText(release.notes,'):
            self.assertIn(fragment, source)

    def test_system_language_and_fallback_are_declared(self):
        root = CATALOG.parents[1]
        config = (root / 'app/src/main/res/xml/locales_config.xml').read_text()
        manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
        english = (root / 'app/src/main/res/values/strings.xml').read_text()
        chinese = (root / 'app/src/main/res/values-b+zh+Hans/strings.xml').read_text()
        self.assertIn('android:name="zh-Hans"', config)
        self.assertIn('android:localeConfig="@xml/locales_config"', manifest)
        self.assertIn('<string name="app_name" translatable="false">Blocknet Wallet</string>', english)
        self.assertNotIn('name="app_name"', chinese)  # brand falls back to English

    def test_language_change_clears_session_before_activity_recreation(self):
        source = (CATALOG.parents[1] / 'app/src/main/java/com/blocknet/wallet/MainActivity.java').read_text()
        start = source.index('private void chooseLanguage()')
        end = source.index('private void checkUpdates(', start)
        flow = source[start:end]
        for item in ('repo.lock()', 'LanguageChangeFlow.complete(', 'OfflineSnapshotStore.lock()',
                     'clearSecrets(body)', 'snapshot=null;page="overview";render()',
                     'AppLanguage.set(this,choice)', 'this::recreate'):
            self.assertIn(item, flow)
        self.assertLess(flow.index('repo.lock()'), flow.index('AppLanguage.set(this,choice)'))
        self.assertLess(flow.index('snapshot=null;page="overview";render()'), flow.index('AppLanguage.set(this,choice)'))

    def test_shortname_warning_localizes_embedded_failure_reason(self):
        source = (CATALOG.parents[1] / 'app/src/main/java/com/blocknet/wallet/MainActivity.java').read_text()
        self.assertIn('LocalizedText.get(this,checked.message)', source)


if __name__ == '__main__':
    unittest.main()
