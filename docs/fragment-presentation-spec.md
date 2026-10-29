# Fragments, quotations and reading context

Status: proposed specification, 2026-10-29. No implementation or deployment is
requested by this document. Applies to biblioteca-server, biblioteca-reader
and passage-producing integrations such as biblioteca-nestjs.

## Intended result

A fragment is an exact range of source text. A quotation is a presentation of
that fragment, with attribution and sharing actions. A citation identifies its
source and location. These must share one address and one resolution contract.

The public address remains the source's normal path, with a final structural/text selector:

```text
/strabo/the_geography_of_strabo/book_xiv/chapter_ii/20/_2:93..2:403
```

In an interactive browser this loads the source reading page and opens a
quotation card over it. Dismissing the card reveals that same page, positioned
at the passage, with the fragment selection retained. Search hits, saved
fragments, shared links and the reader's Quote Link action enter this same
experience. No newly generated public fragment URL uses `/quote/`, `/_N`, a
browser-local quote ID, or a separate paragraph-number addressing scheme.

## What exists now: inspected behavior and code

The current implementations overlap but do not share an end-to-end model:

| Area | Current representation | Consequence |
| --- | --- | --- |
| Server fragment resolver | A `TeiDiv` and two dot-path points; source text returned as paragraph/block strings | Good basis for exact source addressing, but not a structured public JSON fragment API |
| Server quotation HTML | `/quote/{divPath}?start=…&end=…`, a standalone literary card | Attractive independent HTML rendering, including a light paper design |
| Reader quotation gateway | `/api/quote`; POST resolves selected display text, GET parses the server's quote HTML | HTML CSS classes are effectively part of the data protocol |
| Reader quote route | Separate `view: quote` and `QuoteView`; server address packed into a `server:` string | Source reader is not mounted behind the quote page |
| Quote Link in reader | Another card inside `ReaderView`, driven by `createdQuote` | Already has the contextual backdrop, but duplicates rendering and actions |
| Search | `SearchResult.chapterId` receives the full hit path, including non-div elements such as `/_2` | A paragraph is mistaken for a chapter |
| Deep-link fallback | `loadMissingChapter` fetches the element and appends it to the chapter list | Paragraph navigation follows a synthetic chapter entry rather than its source page |
| Paragraph sharing | `?p=N` / `#pN`, based on rendered HTML paragraph ordinals | Another addressing system; not equivalent to TEI child positions and does not describe stanzas |
| Saved content | Reader preferences contain curated quote entries; server collection items can be `FRAGMENT` or `SNIPPET` | Exact ranges and illustrative search snippets are not consistently distinguished |

Read-only inspection of the running dev reader reproduced the supplied Strabo
quote, the Quote Link modal and the `/_2` view. The latter appeared as a
synthetic chapter with inappropriate navigation. The dedicated quote page
displayed “Division 0”: `serverQuoteApi` sets `chapterNumber: 0`. The modal
resolved the full source paragraph successfully. Public web retrieval was
unavailable; browser inspection used the same paths on the running local dev
reader.

Source references:

- [DotPath](../src/main/java/ro/editii/scriptorium/fragment/DotPath.java),
  [FragmentResolutionService](../src/main/java/ro/editii/scriptorium/fragment/FragmentResolutionService.java),
  [FragmentController](../src/main/java/ro/editii/scriptorium/web/FragmentController.java).
- [Server quote template](../src/main/resources/templates/quote.html) and
  [quote styles](../src/main/resources/static/css/quote.css).
- [Reader quote gateway](../../biblioteca-reader/src/services/quoteTwin.ts),
  [address matching](../../biblioteca-reader/src/services/quoteAddress.ts),
  [quote adapter](../../biblioteca-reader/src/services/serverQuoteApi.ts).
- [QuoteView](../../biblioteca-reader/src/components/QuoteView.tsx),
  [ReaderView](../../biblioteca-reader/src/components/ReaderView.tsx),
  [router](../../biblioteca-reader/src/router.ts),
  [chapter fallback](../../biblioteca-reader/src/services/readerService.ts).
- [Search result mapping](../../biblioteca-reader/src/services/serversideApi.ts),
  [vector passage provenance](../../biblioteca-nestjs/src/biblioteca-rag/biblioteca.source.ts),
  [server collection item](../src/main/java/ro/editii/scriptorium/model/DivCollectionItem.java).

## One source address

### Canonical public URLs and selector grammar

| URL form | Meaning |
| --- | --- |
| `/{readingPagePath}` | Read the source page normally |
| `/{readingPagePath}/_1.2.3` | Select the entire node at child path 1 → 2 → 3; open its card |
| `/{readingPagePath}/_1:10..1:30` | Select UTF-16 text offsets [10,30) within child 1 |
| `/{readingPagePath}/_1:40..2.3.2:15` | Select from paragraph 1 into a descendant of the following table |
| `/{readingPagePath}/_1..3` | Select complete children 1 through 3, inclusive |
| Any fragment URL plus `?display=context` | Same fragment selected in its source, card dismissed |

`.` navigates one-based XML element children; `:` explicitly switches to a
zero-based canonical-text offset; `..` separates range endpoints. A selector
without a range selects the complete addressed node. Bare node endpoints in a
range mean its beginning on the left and its end on the right. Navigation
numbers never change meaning implicitly into text offsets. Offset ranges are
half-open. Text mode is allowed at a text-bearing node even when it contains
inline elements; it is not restricted to XML nodes without children.

The path before the selector identifies an actual reading page. The server
resolves legacy nested `/_1/_2` paths structurally, distinguishing non-div
nodes from real divisions. It must never remove underscores by regex alone.
The preferred canonical selector is relative to the nearest containing reading
division; legitimate division URLs remain division URLs.

A stanza is selected as one `lg` node. A manually selected verse may be a node
or a text range within that stanza. Cross-element ranges follow document order
through the source tree. Retain enough ancestor structure to render a partial
paragraph, intervening blocks and partial table as paragraphs and tables, not
one flattened string. Validate offsets on the canonical text projection and
map them back into the XML tree. Headings, inline formatting, notes and verse
breaks must have explicit projection/mapping rules.

`display=context` is presentation state only. Copy Fragment Link omits it;
reloading the context form keeps the card closed and its selection active.
All forms pass through one canonical serializer, normalizing numeric spelling
and rejecting missing endpoints, negative/out-of-bounds offsets, reversed and
empty ranges. Do not emit optional shorthand spellings for same-node ranges.
The existing `?start=1.10&end=1.30` form is a compatibility input, normalized
to `/_1:10..1:30` by interpreting its LAST dot component as the old text offset.

Existing vector payloads and internal locators such as `/_2` remain supported
indefinitely. No vector rewrite or re-embedding is required for this feature.
`/api/divs?path=…`, paragraph retrieval and raw source exports must retain their
existing meanings. Public reader navigation opens the same locator as a
fragment. Internal retrieval must not be redirected into a quotation response.

### Stability and source changes

The current addresses survive a database re-import of unchanged source; they
are positional and can change meaning when source elements or text change.
“Permanent” must not imply that the current implementation archives editions.

The new model carries `sourceVersion` and an exact-text witness. A version
covers both the TEI structure and its text projection rules; a text hash alone
cannot detect every structural change or uniquely identify repeated passages.

For durable links, emit an additional `rev=<opaque-source-version>` query
parameter. The canonical form is still the source path plus selector, qualified
by a source revision. The resolver must either resolve that retained revision
or explicitly report it unavailable; it must never apply old offsets to changed
text and return a different quotation. Retaining immutable source revisions is
a prerequisite for promising historical quotation availability. Without that
retention, revision mismatches fail closed rather than being silently relocated.

Existing links without `rev` resolve against the current source, with their
weaker existing guarantee documented. Do not permanently redirect such a link
to a pinned revision as though its historical identity had been established.
Prefix/suffix text can help diagnose a changed source; ambiguous or approximate
matches require user review, never automatic substitution in a citation.

## One fragment contract, owned by biblioteca-server

Introduce a typed `FragmentRef` and resolved `Fragment` contract. Public page
URLs remain unprefixed; API endpoints may use an explicit resource namespace.
Suggested API: `GET /api/fragments?path=…&selector=…[&rev=…]` and
`POST /api/fragments/resolve` for a source selection or legacy element locator.
These are proposals, not existing endpoints.

The resolved response supplies:

| Field group | Required information |
| --- | --- |
| Identity | Containing source path, selector and resolved boundaries, selector version, source version |
| Reading context | Stable work path, preferred reading-page path, source-page URL, actual source breadcrumb |
| Content | Exact plain text and ordered structured blocks; preserve verse line breaks, stanza boundaries and partial first/last blocks |
| Attribution | Author, work, real division title/breadcrumb, language, edition/source metadata when available |
| Links | Canonical public fragment URL and selected-context URL, using the configured public origin |
| Mapping | Source-block identifiers and boundary-to-rendered-text mapping sufficient to highlight the exact passage in its source |
| Resolution | Exact, source changed, ambiguous, not found or unavailable; include a text witness/version for validation |

No database row ID, fabricated chapter number, `server:` string or display title
is the fragment's identity. A quote does not need a new persisted row merely
to be shared. Collection entries store the typed reference, with an optional
text snapshot for offline display and source-change diagnosis. A snapshot is
not a substitute for successful source resolution.

The server owns element-to-fragment conversion, selector validation, text
normalization, context attribution and URL normalization. The reader gateway
proxies typed results and renders representations; it no longer scrapes
`.quote-text p` from HTML or maintains a competing canonical resolver.

The server's own source-route controller must also recognize the unified selector suffix and legacy range
parameters and serve the corresponding fragment representation. Otherwise
legacy redirects on the server would lose the range at the ordinary division
renderer. Direct server HTML may use the standalone skin; the interactive
reader supplies the source-page/modal composition. Both use the same resolver
and fragment content. biblioteca-covers needs no new fragment identity; existing
work covers remain suitable fallback sharing images.

The current display-text matching algorithm is useful during transition, but
it filters source paths to underscore-only descendants and relies on text
matching. It is not sufficient as the final DOM-to-source identity mechanism.
The source HTML/render contract must carry server-produced block mapping, with
an offset map for transformed whitespace, inline formatting, notes and verse
breaks. A repeated sentence must resolve through its actual source location,
not a global first text match. Ambiguity remains an explicit failure.

Apply the same access decision to fragment resolution, source context and every
representation. Do not make the fragment endpoint a way around source access
rules. Retain sensible range/output limits and existing read-only deployment
behavior; public resolution does not save a collection or launch background jobs.

## Search results and service integrations

Search targets become a discriminated union: author, work, reading page or
fragment. A fragment target includes a `FragmentRef` and its reading-page path;
it is never placed in `chapterId` as if it were a chapter. Snippet text, score,
highlight markup and the precise target are separate fields.

| Producer | Target rule |
| --- | --- |
| Semantic paragraph hit | Exact whole indexed block, resolved to a fragment; retain its source version/provenance |
| Grep hit | Exact matched range if bounds are supplied; otherwise the exact whole source block |
| Lucene division hit | Reading page by default; an illustrative excerpt is not an exact fragment until offsets are supplied |
| Title/author hit | Existing work/author navigation |
| Saved search result | Persist its typed target, not just a snippet and a path that may be mistaken for a chapter |

The nestjs/vector payload currently carries a paragraph URL, text hash and
work path. Extend or adapt that provenance so server search, RAG citations and
reader navigation agree on the target. Metadata adaptation does not inherently
require re-embedding unchanged text. Identical text in two places still needs
two source occurrences; a content hash alone cannot select which one is cited.

The chapter list remains the real TOC. Resolving a fragment must never append
a synthetic chapter. Previous/Next operate on its source reading page's TOC
position. Do not add Previous/Next Paragraph controls disguised as chapter
navigation. Reading progress and attention belong to the containing reading
page, not to a synthetic fragment page.

## One quotation experience, preserving both designs

Use a shared `FragmentCard` for content, attribution and actions, inside a
`FragmentDialog` or standalone shell. Keep the generous typography, decorative
quotation marks, amber quotation rule and attribution of the current quote
page. Keep the compact modal's convenient link field and immediate sharing
actions. These become responsive/composable parts of the same card, rather
than two competing implementations.

The recommended interactive presentation uses a dark-blue frame matching
Similar, with the literary quotation treatment retained inside it. The server's
warm paper treatment remains a standalone/embed skin of the same fragment
content. Avoid forcing poetry into one italic prose paragraph: preserve lines,
stanzas, lists and paragraph breaks, including exact partial boundaries.

The source reading page is genuinely loaded underneath, positioned at the
fragment. A light dim/glass backdrop leaves its layout recognizable. The
background is inert and does not scroll while the modal is open. On mobile the
card becomes a bounded sheet/dialog, with internal scrolling for long passages,
a visible close action and reachable attribution/actions.

The card offers Copy Link, Copy Text, Copy Citation, Save, Share and Read in
Context. Similar may search the exact fragment as it does today. Both entry
paths—opening a link and pressing Quote Link—show the same card and actions.
There is no need for a separate “quote created” resource or confirmation design;
the existing confirmation's attractive details belong in the shared card.

Citation output includes the actual author/work/division breadcrumb and the
canonical range link. Include translator/edition information when known; do not
invent missing metadata. Remove “Division 0” and local ID labels from the UI.
Copy Citation and Share use the same attribution data in every presentation.

### Selection and closing behavior

- A regular paragraph/stanza click still shows its edge bar and toolbar, with
  no full-text highlight. A stanza remains the click/hover unit for poetry.
- Opening a fragment card activates its exact source range. That range remains
  visibly highlighted after the card closes, until explicitly deselected.
  It may be a partial paragraph, one verse, a stanza or several blocks; never
  expand an exact fragment to an entire paragraph merely to draw its marker.
- Similar continues to reveal the exact searched selection. A manually
  selected fragment remains that fragment, not the enclosing stanza/paragraph.
- Close, backdrop dismissal and Escape close only the topmost overlay. They do
  not clear the fragment selection or lose source scroll position. Read in
  Context has the same source-revealing result.
- A subsequent Escape in the source view, an explicit clear action, a click
  outside the selected passage or a new selection can deselect/replace it.
  Navigating to another page clears the old range.
- On dismissal, focus returns to the initiating control or selected source
  block. Keep dialog labelling, focus trapping and keyboard navigation.

Selection identity belongs to route/application state, not only a live browser
`Selection` object. Opening Similar, Share, Save or a nested dialog must not
destroy it. Render its highlight independently if native focus changes would
erase the range, and restore the native selection when useful for copy actions.

### Browser history

Opening a card from its source page adds a fragment entry. Closing it returns
to the source page with its selector suffix and `display=context`; reload preserves
that selection without reopening the card. Back/Forward must reflect card open
versus contextual reading without dismissal loops or duplicate source entries.

For a direct external fragment link, Close replaces the current entry with its
selected-context form; it must not send the visitor back to an unrelated site.
If a card was opened from search, keep the originating search/query/results and
scroll position in history, so Browser Back returns to search. The card's Close
still means “show this passage in its source,” not “return to search.”

## curl, no-JavaScript readers and sharing

The same canonical URL is a resource, not an SPA-only instruction. Representation
negotiation is separate from the range and from `display=context`.

| Request | Proposed response |
| --- | --- |
| Interactive browser, HTML | Source page with fragment modal; context form opens with selection and no modal |
| curl/default `*/*`, scraper, or no-JS HTML | Standalone literary fragment HTML with attribution and a Read in Context link |
| `Accept: text/plain` | Exact fragment text only, preserving meaningful breaks |
| `Accept: text/markdown` | Fragment text with suitable block/verse formatting and citation |
| `Accept: application/json` | The typed resolved Fragment, not the whole chapter and not an HTML scrape |

Preserve existing `view=text` as an explicit no-JS HTML override. Do not reuse
that parameter to mean “card closed.” An explicit unsupported Accept type
receives 406. Anonymous HTML with `display=context` can show the source with
the passage marked, and a link back to its card representation.

Examples below describe the proposed contract; the current unprefixed reader
route does not yet implement fragment range negotiation:

```sh
fragment='https://test.scriptorium.ro/strabo/the_geography_of_strabo/book_xiv/chapter_ii/20/_2:93..2:403'
curl -H 'Accept: text/plain' "$fragment"
curl -H 'Accept: application/json' "$fragment"
curl -H 'Accept: text/html' "$fragment"
```

The canonical link and Open Graph URL retain fragment selectors (and `rev`
when pinned), omit presentation-only parameters, and use the configured public
origin. Metadata describes the selected passage, not merely the whole work.
Keep `Vary` correct for negotiated/browser HTML responses; access-controlled
responses must not leak through shared caches. Server HTML remains readable
without scripts and resolves to the same text as JSON and the interactive UI.

## Compatibility and rollout

1. Implement the typed server contract and source mapping first, with tests
   against the existing resolver and source HTML. Regenerate affected clients.
2. Add unprefixed range routing and the shared fragment presentation. Separate
   source page state, selected fragment state and overlay state.
3. Convert all newly generated quotes, search targets, RAG citations and saved
   fragment links to the common model. Delete synthetic paragraph-to-chapter
   promotion only after replacement routing works.
4. Keep old `/quote/{path}?start=…&end=…` links as redirects to the unprefixed
   canonical range URL. Preserve meaningful selectors and version parameters.
5. Resolve legacy non-div `/_N` links structurally and redirect to the exact
   whole-element fragment. Do not redirect real divisions or raw exports by
   URL pattern alone. Do not cache a permanent location across source changes
   without a pinned/validated source version.
6. Translate `?p=N`, paragraph ranges and `#pN` through the rendered/source
   mapping when unambiguous. Hash-only migration is client-side. Never equate
   the Nth rendered paragraph to XML child N. Stop emitting these older forms.
7. Upgrade saved references without deleting collections or their text
   snapshots. Legacy browser-local quote IDs can be upgraded only where the
   source resolves exactly. If the browser's old quote data is absent or the
   passage is ambiguous, show an explicit unavailable legacy quote rather
   than fabricate a replacement. Collections-store unification is related
   work, not a prerequisite to using the common FragmentRef.

Source snapshot retention and version-qualified links must be delivered before
advertising historical/permanent citation guarantees. If shipped in stages,
state that limitation explicitly and fail on detected source mismatches.

## Acceptance checks

- The supplied Strabo range resolves to identical words in modal, standalone
  HTML, JSON, text, copied text and citation. Its canonical URL has no prefix.
- Opening `…/20/_2` resolves the whole second source element, opens it over
  `…/20`, and leaves the work's TOC unchanged. Its Previous/Next are the
  source page's neighbors, not an appended synthetic entry or a license page.
- Quote Link and search-fragment opening produce the same card. Source
  backdrop, attribution, actions and close behavior agree on desktop/mobile.
- Dismissing a direct-link card leaves the user in its source with the exact
  range active; reload, Back and Forward have the specified behavior.
- Similar on a stanza still uses the whole stanza; manual verse and partial
  prose selections remain exact. Check repeated sentences, notes, inline
  markup, significant line breaks, Unicode/emoji and ranges across blocks.
- Cross-element ranges from a partial paragraph into a table preserve valid table structure.
  Full-node ranges, deeply nested nodes, partial stanzas and UTF-16 boundaries resolve exactly.
- Existing stored vector URLs and internal text exports retrieve identical source text before/after rollout.
- A genuine division stays a division even if its source locator looks
  positional. An imprecise Lucene excerpt remains a page hit, never a false
  exact quotation.
- An unchanged-source re-import preserves references despite changed DB IDs.
  Changed source is detected. A pinned revision resolves its original text
  or explicitly fails; no neighboring or approximate quote is substituted.
- Old quote/paragraph links continue to resolve where their source still
  exists. Legacy collection data is retained on failed migration.
- No-JS/curl results, bot metadata and interactive pages enforce the same
  access checks, range validation and output limits. No generic 200 “not
  found” card replaces a real HTTP error.

The product-level decisions proposed here are: unprefixed source URLs with unified node/range suffixes;
one contextual fragment card with retained selection; shared content/actions
with responsive and standalone skins; and source-version validation before
calling a positional quotation permanent. Implementation proceeds on `feat/unified-fragments`; production remains unchanged until a separately requested release.
