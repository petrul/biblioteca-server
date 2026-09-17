"""Small client for textbase.scriptorium.ro's live public API.

Only uses endpoints that are actually live in production today. The newer
/api/divs/{id}/toc and /api/collections/system/by-language/{lang} endpoints
exist in the biblioteca-server source but returned 404 against both prod and
the int deployment when checked (2026-09-03) - the running builds predate
them. So:

  - chapter-tree discovery walks the *decorated HTML* reading-view page's own
    child links instead of a TOC endpoint (see direct_child_hrefs below) -
    a div with zero direct children is a leaf, exactly matching what
    TeiDivDto.leaf would have told us if the endpoint were live.
  - language is detected locally with langdetect rather than trusting
    server-side language metadata, since /api/collections/system/by-language
    isn't reachable either.

/util/random and /api/search/divHeads ARE live and are used as-is.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup

BASE_URL = "https://textbase.scriptorium.ro"
_TIMEOUT = 30


def _session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": "textbase-nomic-embed-experiment/0.1"})
    return s


SESSION = _session()


@dataclass
class Div:
    path: str  # e.g. "anon/biblia_ortodoxa/nota_editorului"
    url: str  # full reading-view URL, no extension
    head: str | None = None


def random_leaf_url() -> str:
    """Hits /util/random and returns the absolute reading-view URL it redirects to."""
    resp = SESSION.get(f"{BASE_URL}/util/random", allow_redirects=False, timeout=_TIMEOUT)
    resp.raise_for_status()
    location = resp.headers["Location"]
    return urljoin(BASE_URL, location)


def path_of(url: str) -> str:
    return urlparse(url).path.strip("/")


def opus_path_of(div_path: str) -> str:
    """First two path segments (author/opus) - the work root a div belongs to."""
    parts = div_path.split("/")
    return "/".join(parts[:2])


def fetch_text(path: str) -> str:
    """Plain-text content of a single div (its own paragraphs, not descendants)."""
    resp = SESSION.get(f"{BASE_URL}/{path}.txt", timeout=_TIMEOUT)
    resp.raise_for_status()
    return resp.text


def fetch_html(path: str) -> str:
    resp = SESSION.get(f"{BASE_URL}/{path}", timeout=_TIMEOUT)
    resp.raise_for_status()
    return resp.text


def page_title(html: str) -> str | None:
    soup = BeautifulSoup(html, "html.parser")
    title = soup.find("title")
    return title.get_text(strip=True) if title else None


def direct_child_paths(path: str, html: str) -> list[str]:
    """Paths of divs one level below `path`, found as <a href> links on its own
    decorated page. Returns [] for a leaf (a div with no sub-divs)."""
    soup = BeautifulSoup(html, "html.parser")
    prefix = f"/{path}/"
    seen: list[str] = []
    for a in soup.find_all("a", href=True):
        href = a["href"]
        if not href.startswith(prefix):
            continue
        remainder = href[len(prefix):]
        if not remainder or "/" in remainder or "#" in remainder:
            continue
        child_path = f"{path}/{remainder}"
        if child_path not in seen:
            seen.append(child_path)
    return seen


def walk_leaves(opus_path: str, max_divs: int | None = None) -> list[Div]:
    """DFS over the decorated-HTML child links starting at opus_path, returning
    every leaf div (a div with no children) reachable from it. Fetches one HTML
    page per *container* div visited - leaves themselves are not fetched here
    (callers fetch .txt content for leaves separately, only for the ones they
    actually keep)."""
    leaves: list[Div] = []
    stack = [opus_path]
    visited: set[str] = set()
    while stack:
        if max_divs is not None and len(visited) >= max_divs:
            break
        path = stack.pop()
        if path in visited:
            continue
        visited.add(path)
        html = fetch_html(path)
        children = direct_child_paths(path, html)
        if not children:
            leaves.append(Div(path=path, url=f"{BASE_URL}/{path}", head=page_title(html)))
        else:
            stack.extend(children)
    return leaves


def search_div_heads(query: str, limit: int = 10) -> list[dict]:
    resp = SESSION.get(
        f"{BASE_URL}/api/search/divHeads",
        params={"q": query, "limit": limit},
        headers={"Accept": "application/json"},
        timeout=_TIMEOUT,
    )
    resp.raise_for_status()
    return resp.json()
