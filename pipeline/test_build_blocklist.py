import unittest, build_blocklist as b

class T(unittest.TestCase):
    def test_extract(self):
        self.assertEqual(b.extract("0.0.0.0 ads.example.com # c"), "ads.example.com")
        self.assertEqual(b.extract("||tracker.example.net^"), "tracker.example.net")
        self.assertEqual(b.extract("||t.example.net^$third-party"), "t.example.net")
        self.assertIsNone(b.extract("||t.example.net^$script"))      # opsi yang mempersempit aturan
        self.assertIsNone(b.extract("! komentar"))
        self.assertEqual(b.extract("*.wild.example.org"), "wild.example.org")
    def test_domain_regex(self):
        self.assertTrue(b.DOM.match("a.example.com"))
        self.assertFalse(b.DOM.match("localhost"))
        self.assertFalse(b.DOM.match("bad_domain.example.com"))
    def test_drop_redundant(self):
        r = b.drop_redundant({"example.com", "a.example.com", "b.a.example.com", "other.org"})
        self.assertEqual(sorted(r), ["example.com", "other.org"])
    def test_drop_never(self):
        keep, bad = b.drop_never({"google.com", "www.youtube.com", "ads.google.com", "tracker.example.com"})
        self.assertEqual(bad, ["google.com", "www.youtube.com"])
        self.assertEqual(keep, {"ads.google.com", "tracker.example.com"})
    def test_degraded(self):
        prev = {"a": 100000, "b": 5000, "c": 200}
        got = b.degraded_sources({"a": 30000, "b": 4900, "c": 0, "d": 5}, prev)
        self.assertEqual([x[0] for x in got], ["a"])                 # c di bawah floor, d tanpa riwayat
        self.assertEqual(b.degraded_sources({"a": 60000}, prev), [])

class Sel(unittest.TestCase):
    def test_risky(self):
        for bad in ["div", "*", "body", "#content", ".container", 'div[class*="ad"]', "[id^='x']", "a, #main"]:
            self.assertTrue(b.risky_selector(bad), bad)
    def test_safe(self):
        for ok in [".adsbygoogle", "ins.adsbygoogle", "div.ad-banner", '[data-ad-slot]', '[class^="iklan-"]',
                   'iframe[src*="doubleclick.net"]', "#masthead-ad", "a[href*='adsterra']", ".ad-box a", ".sidebar > .ad-unit"]:
            self.assertFalse(b.risky_selector(ok), ok)

class Heal(unittest.TestCase):
    def test_blockers_includes_parent_but_not_bare_tld(self):
        self.assertEqual(sorted(b.blockers({"co.id", "com", "x.org"}, "bca.co.id")), ["co.id"])
        self.assertEqual(b.blockers({"com"}, "www.google.com"), [])
    def test_heal_removes_suffix_and_critical_hosts(self):
        bad = {"co.id", "mtalk.google.com", "connectivitycheck.gstatic.com", "gstatic.com"}
        good = {"ads.example.co.id", "tracker.example.net", "ads.google.com"}
        keep, culprits = b.heal(bad | good)
        self.assertEqual(keep, good)                       # iklan sungguhan tetap diblokir
        self.assertIn("co.id", culprits)
        self.assertIn("bca.co.id", culprits["co.id"])      # bank terlindungi dari entri induk
    def test_canary_includes_never_list(self):
        for d in b.NEVER: self.assertIn(d, b.CANARY)

class EndToEnd(unittest.TestCase):
    """Jalankan main() utuh secara offline dengan sumber palsu yang diracuni."""
    def test_poisoned_source_is_healed(self):
        import tempfile, pathlib, os, io, contextlib
        with tempfile.TemporaryDirectory() as t:
            t = pathlib.Path(t); pd = t / "pipeline"; pd.mkdir()
            (pd / "allowlist.txt").write_text("localhost\n")
            (pd / "sources.txt").write_text("http://fake/source\n")
            (pd / "cosmetic_sources.txt").write_text("")
            fake = [f"0.0.0.0 ads{i}.tracker{i % 50}.example.com" for i in range(1500)]
            fake += ["0.0.0.0 co.id", "0.0.0.0 mtalk.google.com", "0.0.0.0 google.com", "0.0.0.0 ads.example.co.id"]
            old_root, old_lines = b.root, b.lines
            b.root = pd; b.lines = lambda url, tries=3: fake
            summary = t / "summary.md"; os.environ["GITHUB_STEP_SUMMARY"] = str(summary)
            try:
                with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                    b.main()
            finally:
                b.root, b.lines = old_root, old_lines; os.environ.pop("GITHUB_STEP_SUMMARY", None)
            out = set((t / "blocklist.txt").read_text().split())
            self.assertNotIn("co.id", out); self.assertNotIn("mtalk.google.com", out); self.assertNotIn("google.com", out)
            self.assertIn("ads.example.co.id", out)        # iklan di dalam .co.id tetap diblokir
            self.assertTrue(any(x.startswith("ads1.") for x in out) or len(out) > 1000)
            self.assertIn("Penyembuhan mandiri", summary.read_text())

if __name__ == "__main__":
    unittest.main()
