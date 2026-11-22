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

if __name__ == "__main__":
    unittest.main()
