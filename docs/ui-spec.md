# Textbase Client — Functional Specification

## What this document is

A **behavior specification** for a new biblioteca-server client, deployable as both a web app and a mobile app. It defines what the app must let a person do, and what data/state each screen depends on — not how anything should look. No visual design, layout, navigation chrome, component choice, color, or framework/tech-stack decision is made here; all of that is deliberately left open. Every requirement below is phrased as a capability ("the user can…") or a constraint ("the app must…"), not a mockup.

It takes inspiration from, but does not copy, the existing Ionic UI at `textbase-ionic-ui`. Its real purpose is different: that UI wires up a small fraction of what the server already does (author browsing, three of five search modes, no auth, no collections). This spec is written directly against the server's actual capabilities (verified against the current codebase, not the old UI), specifically to put the *unused* ones — search modes, quotations, personal and system collections, auth, admin operations — to work.

## Guiding principles

1. **Feature-complete, not feature-guessed.** Every capability the server actually exposes gets a place in this client. Nothing here promises a capability the server doesn't have (see "Explicitly out of scope" at the end).
2. **One functional spec, two deployments.** Every requirement in this document applies equally to the web and mobile builds unless a section explicitly calls out a platform-specific variant. Platform choice of framework/toolkit is an implementation decision, not specified here.
3. **Public by default, richer when signed in.** Nearly everything (browsing, reading, all search modes, quotes, system collections) works for an anonymous visitor. Signing in adds personal collections and — for admins — the admin dashboard. The app must never require sign-in to read or search the library.
4. **Degrade, don't break.** The server already tracks whether vector search is available and returns empty results instead of erroring when it isn't (`VectorSearchAvailability`). The client must treat "vector search returned nothing because it's unavailable" as a normal, distinguishable state (see Search section) — not an error, and not indistinguishable from "no results."

---

## 1. Discovery & Browse

**Purpose:** let a visitor find their way into the corpus without already knowing what they're looking for.

Must support:
- An **author catalog**: every author, with whatever portrait/thumbnail is available, browsable as a full list.
- An **author page** per author: their works (opera) list, and — once populated server-side — a biography/description area (the field exists in the API shape today even though it isn't populated for any author yet; the client should render it when present and simply omit it when absent, not treat its absence as an error).
- A **work page** per opus: its table of contents (chapters/sub-chapters, arbitrarily nested), estimated size (page/word count, already computed server-side), detected language, and license/source information where the server provides it.
- **System-computed reading lists**, entirely public, no account needed:
  - Browse works **by language** (one list per detected language present in the corpus).
  - Browse works **by author** (same data as the author page, but reachable as its own entry point).
  - Browse works **by source repository** (the corpus is assembled from more than one named source repo; let a visitor filter to just one).
  - A list of the **repositories themselves**, so a visitor can discover this grouping exists at all.
- **Discover something at random**: one action that takes the visitor straight into a randomly chosen work, skipping purely administrative/legal boilerplate entries.
- Paging throughout — the corpus and its tables of contents are large; nothing here should require loading an entire list at once.

## 2. Reading

**Purpose:** actually read a text, at whatever grain the visitor wants — a whole work, a chapter, or a single paragraph on its own.

Must support:
- Reading a work's content chapter by chapter (or, for shorter works, straight through), following its table of contents.
- Displaying the language a given chapter/work is in.
- Displaying embedded illustrations/images that are part of a TEI file, inline with the surrounding text.
- **Choosing a format for a chapter/section**, not just reading server-rendered HTML: the server can already return the same content as plain text, structured JSON, or raw TEI/XML, in addition to decorated HTML. The client must let the visitor explicitly pick a format when they want one (e.g. "get this chapter as plain text") rather than only ever consuming the decorated view.
- Continuing to the next/previous chapter without returning to the table of contents.

## 3. Search

**Purpose:** the server has five distinct search capabilities. Today's UI wires up three (author name, chapter-title, and vector similarity). This client must expose all five, each legible as a *different kind* of search rather than one blended results list — a visitor should be able to tell they're getting a literal substring match versus a stemmed full-text match versus a meaning-based match, because those genuinely return different things.

Must support, as distinguishable search modes:
1. **Author search** — find authors by name.
2. **Title search** — find chapters/works by their heading text.
3. **Literal search** ("grep") — an unindexed, always-current, case-insensitive literal substring search over paragraph text. No stemming, no ranking. The client should make clear this is the "search exactly what I typed, right now" option.
4. **Full-text search** (Lucene) — a real, ranked, language-aware full-text index (stemming/stopwords per detected language where supported, diacritics-insensitive). The client should make clear this is the "find this concept, phrased naturally" option, and that its index may lag the newest imports (it's rebuilt on demand server-side, not live).
5. **Similarity search** (vector/"deep") — meaning-based search via embeddings, returning conceptually related passages even without shared vocabulary.

Also:
- **"More like this"**: from any work or chapter, get a list of other passages the server judges similar (the `/api/search/ann` capability) — surfaced as a "related reading" affordance on the reading view, not only as a manual search action.
- When similarity-based results (mode 5, and "more like this") come back empty because the server has vector search disabled for this run (unreachable embedder/Milvus), the client must show that as "similarity search is temporarily unavailable," never as "no matches found."
- Every search mode must support jumping straight from a result into the reading view at the matched location.

## 4. Quotations (Fragments)

**Purpose:** the server can already address and render an arbitrary span of text — from part of a paragraph up to several chapters — as a standalone, shareable unit. No current UI exposes this at all.

Must support:
- **Selecting a span of text** while reading (anywhere from a few words to multiple paragraphs/sub-chapters) and generating a **permanent, shareable link** to exactly that span, rendered on its own as a quote.
- Opening a shared quote link directly (someone else's shared link) and reading it as a standalone quote, with a clear path from there back into the full work/chapter it came from.
- Copying/sharing that link through the platform's normal sharing mechanism (share sheet on mobile, copy-to-clipboard/native share on web).
- Saving a quotation into a personal collection (see next section) directly from the selection action, for signed-in users.

## 5. Personal Collections

**Purpose:** let a signed-in visitor build and keep their own named groupings of works and quotations. Fully built server-side; no current UI exposes any of it (the old UI's nav entry for this is literally commented out).

Must support, for a signed-in user only:
- Viewing all of their own collections.
- Creating a new named collection, and deleting one they no longer want.
- Adding an item to a collection — either a whole work/chapter (a div) or a saved quotation (a fragment) — and removing an item from a collection.
- A **Favorites** collection that exists automatically from the moment of account creation, cannot be deleted, and otherwise behaves like any other collection (add/remove items).
- Discoverability: everywhere a work, chapter, or quotation is shown to a signed-in user, there must be a way to add it to a collection (existing or new) right there, not only from a separate "collections" screen.

## 6. Authentication & Accounts

**Purpose:** identify a returning visitor so personal collections (and, for admins, the dashboard) exist.

Must support:
- **Google One-Tap sign-in as the primary path** on both web and mobile — the server already implements verifying a Google ID token and finding-or-creating the account from it; the client's job is to present the One-Tap prompt/button and hand it the token.
- Fallback account creation by username + password, for a visitor without (or not wanting to use) a Google account.
- Signing out.
- A minimal account view: at least confirmation of who's signed in and a sign-out action. Nothing about profile editing is specified here since the server has no such capability today.
- The app must work fully for browsing/reading/searching/quoting-and-viewing-shared-quotes while signed out; sign-in should only ever be prompted at the point the visitor tries to do something that actually requires it (save to a collection, open the admin dashboard).

## 7. Admin Dashboard

**Purpose:** give an administrator a working console for the corpus-management operations the server already exposes as raw endpoints today, with no UI at all.

Must support, visible and usable **only** to a signed-in user with the admin role:
- Viewing server/build version information.
- Viewing the list of configured TEI source repositories.
- Triggering a re-import of the corpus: as "only what's changed since last import," "everything," a single named file, and — clearly separated as a more disruptive action, since it wipes and rebuilds — "force re-import everything."
- Triggering a full-text (Lucene) search index rebuild.
- Viewing whether vector/similarity search is currently available (surfacing the same signal `VectorSearchAvailability` tracks server-side), so an admin can tell at a glance whether search mode 5 and "more like this" are degraded for this run without having to go check server logs.
- A visible, obvious boundary: nothing in this dashboard, and no route that leads to it, should be reachable by a signed-in user who isn't an admin, or by a signed-out visitor.

**Dependency this spec surfaces, not something the client alone can fix:** the server's admin routes today only require *being signed in*, not holding the admin role specifically. Enforcing "admin-only" for real (not just hiding the dashboard in the UI, which a determined user could route around) requires the server to actually gate `/admin/**` and `/api/admin/**` on the admin role. Building this dashboard should happen together with that server-side fix, not after a UI-only gate ships alone.

---

## Cross-cutting requirements

- **Language awareness throughout.** Every work/chapter carries a detected language; browse-by-language, search, and the reading view should all reflect it (e.g. don't silently apply English-only assumptions anywhere content might not be English).
- **Consistent identity for shareable links.** Both a quote permalink and a direct link to a work/chapter should be stable, shareable URLs that work the same whether opened in the mobile app or a browser (i.e. the mobile app should be able to open a link created on web, and vice versa).
- **Graceful degradation is a first-class state**, not an edge case, for anything backed by search/vector infrastructure — see Search and Admin sections above.
- **Paging everywhere** a list is potentially large: tables of contents, author list, search results, collection contents.

## Explicitly out of scope (not real server capabilities today — do not promise these)

- Whole-book export (e.g. EPUB). The server exports per-chapter in several formats (§2) but has no whole-work export of any kind.
- A media/illustration gallery independent of reading a work — the database tables for this exist but nothing populates or serves them today.
- Profile editing beyond sign-in/sign-out.
- Any capability behind the reserved-but-unimplemented `/api/shell` route.
