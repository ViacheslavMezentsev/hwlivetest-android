"""Validate local site assets and fragment links without network access."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit, unquote

ROOT = Path(__file__).resolve().parents[1] / "docs" / "site"

class Page(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.links = []
        self.has_title = False

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            assert attrs["id"] not in self.ids, f"Duplicate id: {attrs['id']}"
            self.ids.add(attrs["id"])
        if tag == "title":
            self.has_title = True
        for key in ("href", "src"):
            if key in attrs:
                self.links.append(attrs[key])

pages = {}
for path in ROOT.rglob("*.html"):
    page = Page()
    page.feed(path.read_text(encoding="utf-8"))
    assert page.has_title, f"Missing title: {path}"
    pages[path.resolve()] = page
assert pages, "No HTML pages"

for path, page in pages.items():
    for link in page.links:
        url = urlsplit(link)
        if url.scheme or url.netloc:
            continue
        target = (path.parent / unquote(url.path)).resolve() if url.path else path
        if target.is_dir():
            target /= "index.html"
        assert target.is_relative_to(ROOT.resolve()), f"Outside site: {link}"
        assert target.is_file(), f"Missing asset: {link}"
        if url.fragment and target in pages:
            assert unquote(url.fragment) in pages[target].ids, f"Missing fragment: {link}"
print(f"Site check passed: {len(pages)} HTML page(s)")
