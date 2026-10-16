import unittest

from apk_launcher import verify_launcher


MANIFEST = """A: android:icon(0x01010002)=@0x7f030000
A: android:roundIcon(0x0101052c)=@0x7f030000
A: android:name="android.intent.action.MAIN"
A: android:name="android.intent.category.LAUNCHER"""


def fixture(optimized):
    paths = ("res/BW.xml", "res/IG.xml", "res/Qr.xml", "res/Vy.xml") if optimized else (
        "res/mipmap-anydpi-v26/ic_launcher.xml", "res/mipmap-anydpi-v33/ic_launcher.xml",
        "res/drawable/ic_launcher_foreground.xml", "res/drawable/ic_launcher_monochrome.xml")
    resources = f"""resource 0x7f010000 color/ic_launcher_background
resource 0x7f020000 drawable/ic_launcher_foreground
  () (file) {paths[2]} type=XML
resource 0x7f020001 drawable/ic_launcher_monochrome
  () (file) {paths[3]} type=XML
resource 0x7f030000 mipmap/ic_launcher
  (anydpi) (file) {paths[0]} type=XML
  (anydpi-v33) (file) {paths[1]} type=XML
"""
    icon = """E: adaptive-icon
  E: background
    A: android:drawable(0x01010199)=@0x7f010000
  E: foreground
    A: android:drawable(0x01010199)=@0x7f020000
"""
    xml = {paths[0]: icon,
           paths[1]: icon + "  E: monochrome\n    A: android:drawable(0x01010199)=@0x7f020001\n",
           paths[2]: "E: vector\n  E: path\n",
           paths[3]: "E: vector\n  E: path\n"}
    return resources, xml


class ApkLauncherTest(unittest.TestCase):
    def test_debug_and_renamed_release_resources(self):
        for optimized in (False, True):
            with self.subTest(optimized=optimized):
                resources, xml = fixture(optimized)
                verify_launcher(resources, MANIFEST, set(xml), xml.__getitem__)

    def test_missing_compiled_or_packaged_artwork_fails(self):
        resources, xml = fixture(True)
        missing_path = "res/Vy.xml"
        with self.assertRaisesRegex(ValueError, "Missing packaged monochrome"):
            verify_launcher(resources, MANIFEST, set(xml) - {missing_path}, xml.__getitem__)
        with self.assertRaisesRegex(ValueError, "Compiled drawable/ic_launcher_monochrome"):
            verify_launcher(resources.replace("drawable/ic_launcher_monochrome", "drawable/unrelated"),
                            MANIFEST, set(xml), xml.__getitem__)

    def test_wrong_manifest_or_icon_reference_fails(self):
        resources, xml = fixture(True)
        with self.assertRaisesRegex(ValueError, "roundIcon"):
            verify_launcher(resources, MANIFEST.replace("roundIcon(0x0101052c)=@0x7f030000",
                                                         "roundIcon(0x0101052c)=@0x7f020000"),
                            set(xml), xml.__getitem__)
        bad_xml = dict(xml)
        bad_xml["res/IG.xml"] = bad_xml["res/IG.xml"].replace("E: monochrome", "E: ignored")
        with self.assertRaisesRegex(ValueError, "monochrome"):
            verify_launcher(resources, MANIFEST, set(bad_xml), bad_xml.__getitem__)
        bad_xml = dict(xml)
        bad_xml["res/BW.xml"] = bad_xml["res/BW.xml"].replace("=@0x7f010000", "=@0x7f020001")
        with self.assertRaisesRegex(ValueError, "background"):
            verify_launcher(resources, MANIFEST, set(bad_xml), bad_xml.__getitem__)
        bad_xml = dict(xml)
        bad_xml["res/Qr.xml"] = "E: vector\n"
        with self.assertRaisesRegex(ValueError, "vector with artwork"):
            verify_launcher(resources, MANIFEST, set(bad_xml), bad_xml.__getitem__)


if __name__ == "__main__":
    unittest.main()
