# Unified fragments verification

Feature branches: `feat/unified-fragments` in server and reader. Development uses
`biblioteca/zmeu`, with bare-metal services in their existing `biblioteca:services`
tmux panes. Production configuration and persistent user data are unchanged.

## Repeatable checks

From biblioteca-reader:

```sh
rake 'test[zmeu]'
npm run lint
npm run build
npm run test:e2e -- e2e/unified-fragments.spec.ts --workers=1
```

From biblioteca-server, the selected tests below declare dedicated H2 databases
or are pure tests; they never use the shared development database:

```sh
rake 'test[zmeu,ro.editii.scriptorium.fragment]'
rake 'test[zmeu,DivCollectionServiceTest]'
```

The corresponding combined Gradle run (after loading the zmeu profile) also passed:

```sh
./gradlew -Pprofile=zmeu unittest \
  --tests '*UnifiedFragmentNotationTest' \
  --tests '*FragmentResolutionServiceTest' \
  --tests '*DivCollectionServiceTest'
```

## Expected results

- `_1.2.3` navigates nodes; `_1:10..1:30` uses half-open UTF-16 offsets.
  Bad ranges, nonexistent nodes, surrogate splits and conflicting parameters fail.
- Partial paragraph-to-table and cross-chapter ranges preserve their endpoints
  and semantic table/verse structure. Repeated text uses actual DOM/source positions.
- Strabo `/strabo/the_geography_of_strabo/book_xiv/chapter_ii/20/_2:93..2:403`
  resolves to the same text for the card, plain text, JSON and Markdown.
- Old `/quote/…?start=2.93&end=2.403`, query ranges, paragraph aliases and vector
  `/_2` links normalize temporarily to unprefixed revision-qualified URLs.
  Genuine divisions retain page identity. Raw source exports remain raw.
- Changed revisions fail with 409; no approximate quotation is substituted.
- Quote Link, search fragments and old quote entry points share one card, with
  all original sharing, citation, collection and context actions available.
- Closing retains selection; reload and browser history restore card versus
  context state. Next/previous use the real source TOC.
- Stanzas are whole click/hover units; manually selected verse text remains exact.
- Desktop/mobile cards fit the viewport. No-JS browsers and curl get readable content.
- Source preview rules apply to every representation and ancestor-range endpoints.
- Collections retain their existing records; failed legacy upgrades retain snapshots.

## Development result

Server: 42 selected tests passed (7 notation/projection, 22 service/HTTP/legacy
fragment, 13 collection tests), all using isolated fixtures. Reader: 256 unit
tests passed; type checking and the production-bundle smoke check passed.
All 8 Playwright tests passed, covering desktop, mobile, no-JS, curl parity,
legacy aliases, Similar, retained ranges, history and real chapter navigation.
No database migration is required; vectors and text export projections are unchanged.

Historical source retention remains outside this feature. Revision mismatch is
explicitly unavailable rather than a claim that prior editions are archived.
