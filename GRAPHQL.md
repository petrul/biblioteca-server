# GraphQL

POST JSON to `/graphql`, for example `{"query":"{ authors(size: 5) { strId lastName } }"}`.
This is a read-only endpoint; existing REST APIs and controllers are unchanged.

The schema comes from the JPA metamodel, and queries use the existing Spring Data
JPA repositories. Every entity has a lower-camel-case ID lookup and a paged list:

```graphql
query {
  authorList(page: 0, size: 20, filter: {strId: "shakespeare"}) {
    id
    strId
    firstName
    lastName
  }
  teiFileList(size: 5, filter: {language: "EN"}) {
    filename
    title
    authors { strId lastName }
  }
}
```

Use introspection to discover types and fields. Enum filters use Java constant
names, such as `EN` for English and `USER` for the reader role. The query stems are
`author`, `teiFile`, `teiElem`, `teiDiv`, `teiOpus`, `relocation`, `mediaRef`,
`authorMediaAssociation`, `divMediaAssociation`, `embeddingBatchStat`,
`opusVectorizingStat`, `appUser`, `userPreference`, `readingProgress`,
`divCollection`, and `divCollectionItem`. For example, `mediaRef(id: "https://...")`
uses its URL primary key, and `relocation(id: "author/old_opus")` uses its old path.
`authors` and `authorById` remain aliases for `authorList` and `author`.
Database IDs are lookup handles; persisted work links should still use canonical
`authorId/opusId` paths.

Lists default to page 0 and size 20, sort by primary key, and allow sizes 1–100.
Filters use exact-match Query by Example on supplied scalar fields. Omitted fields
never filter on constructor defaults. Dates and enums are returned as strings;
64-bit counters use `Long`. Date filters, binary LOBs, and transient XML/DOM fields
are omitted. There are no mutations.

`TeiElemNode` represents TEI inheritance, including `TeiDiv`:

```graphql
query($id: ID!) {
  teiDiv(id: $id) {
    head
    dbChildren {
      id
      ... on TeiDiv { head }
    }
  }
}
```

Nested selections are materialized inside read-only repository transactions and
work with Open Session in View disabled. Query depth is limited to 12. Relations
use normal JPA loading, so large nested lists can still incur additional SQL.

Catalog/media/statistics queries are public. `AppUser`, `UserPreference`,
`ReadingProgress`, `DivCollection`, and `DivCollectionItem` require an authenticated
administrator named in `admin.users` / `ADMIN_USERS`, including nested access.
`passwordHash` and `googleSub` are absent from both output types and filters.

Run isolated HTTP/repository coverage using:

```bash
./gradlew unittest --tests '*GraphQLJpaTest'
```

The test uses an embedded Derby database and does not access the shared datasource
or production TEI repositories.

## Entity queries matching REST

The repository queries above return JPA entities. These additional queries reuse
existing REST read implementations and their DTOs, including derived information
such as work counts, stable paths, cover metadata and resolved collection items.
REST controllers, routes and contracts are unchanged. There are no mutations,
administrative operations, vector/content searches, file downloads or login flows
in this extension.

| GraphQL query | Existing REST read |
| --- | --- |
| `authorDirectory` | `GET /api/authors/` |
| `authorCatalog(page, size, q)` | `GET /api/authors/page` |
| `workCatalog(page, size, q, lang)` | `GET /api/works` |
| `authorDetails(strId)` | `GET /api/authors/{strId}` |
| `authorWorks(strId)` | `GET /api/authors/{strId}/opera` |
| `authorMedia` | `GET /api/authors/media` |
| `divByPath(path)` | `GET /api/divs?path=...` |
| `divDetails(id)` | `GET /api/divs/{id}` |
| `divCatalog(page, size)` | `GET /api/divs/` |
| `divToc(id, page, size)` | `GET /api/divs/{id}/toc` |
| `divParagraphs(divId, page, size, withContent)` | `GET /api/divs/{id}/paras` |
| `coverMetadata(id, includeRawTei)` | `GET /api/divs/{id}/cover-metadata` |
| `worksByLanguage(lang)` | `GET /api/collections/system/by-language/{lang}` |
| `worksByAuthor(authorStrId)` | `GET /api/collections/system/by-author/{authorStrId}` |
| `worksByRepo(repoName)` | `GET /api/collections/system/by-repo/{repoName}` |
| `repoNames` | `GET /api/collections/system/repos` |
| `featuredWorks` | `GET /api/collections/system/featured` |
| `searchAuthors(q, limit)` | `GET /api/search/authors` |
| `searchDivHeads(q, limit)` | `GET /api/search/divHeads` |
| `currentUser` | `GET /api/users/me` |
| `myCollections` | `GET /api/collections/mine` |
| `myCollection(name)` | `GET /api/collections/mine/{name}` |
| `myReadingProgress` | `GET /api/reading-progress` |
| `myWorkReadingProgress(opusPath)` | `GET /api/reading-progress/one` |
| `myPreferences` | `GET /api/users/me/preferences` |

Catalogue page numbering remains 1-based, default size 12. Division lists, TOC
and paragraphs remain 0-based, default size 20. Entity search requires at least
3 characters and permits limits 1–100. `withContent: true` includes paragraph
text and its SHA-256 hash. Missing authors/collections report `NOT_FOUND`;
invalid query arguments report `BAD_REQUEST`.

`currentUser` is public and reports `authenticated: false` for anonymous callers.
The `my*` queries require sign-in and always use that caller's identity; they
are accessible to normal readers. The generic JPA queries for personal entities
remain administrator-only. `myPreferences` returns the existing arbitrary JSON
document; the other queries expose typed fields and nested selections.

```graphql
query {
  authorCatalog(q: "shakespeare", page: 1, size: 12) {
    totalItems
    items { strId displayName worksCount }
  }
  authorDetails(strId: "shakespeare") {
    displayName
    opera { path head coverUrl }
  }
  divByPath(path: "shakespeare/hamlet") {
    id
    path
    head
    author { strId displayName }
  }
  workCatalog(lang: "en", q: "Hamlet") {
    totalItems
    items { path head author { strId } }
  }
}
```

```graphql
query {
  currentUser { authenticated username isAdmin }
  myCollections { name isFavorites items { divPath divHead fragmentText } }
  myWorkReadingProgress(opusPath: "shakespeare/hamlet") {
    divPath
    divHead
    scrollFraction
    attentionSeconds
  }
}
```

Entity search data is typed: use `data { ... on AuthorDto { strId displayName } }`
for author hits or `data { ... on TeiDivDto { path head } }` for division hits.

Run both GraphQL test classes with
`./gradlew unittest --tests '*GraphQL*Test'`. Tests compare GraphQL with real REST
handlers backed by embedded Derby, with fixture-only TEI and no external services.

## Separate smoke integration test

`GraphQLSmokeITest` is a Groovy test tagged `integration-test`. It starts its own
HTTP server on a random port, uses a fresh embedded Derby database, and mocks
external services with `@MockitoBean`. GraphQL wiring, JPA repositories, security
and REST read handlers remain real. No running server or shared corpus is needed.

It checks all 16 entity query pairs, every public entity list, filters and nested
relations, aliases/fragments, REST/catalog parity, stable work paths, TOC and
paragraph content, cover metadata, typed entity search, authorization and paging
validation.

Run it separately on Yoga (the Gradle integration task requires profile settings):

```bash
source /home/petru/secrets/load-env.sh biblioteca/yoga
./gradlew -Pprofile=yoga integrationTest --tests '*GraphQLSmokeITest'
```

The test overrides the datasource with its own in-memory database and does not
load the shared datasource. `unittest` excludes this test via its integration tag.
