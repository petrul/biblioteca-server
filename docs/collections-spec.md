# Collections spec

Three kinds of collection share one model, in `biblioteca-server` (Java) and `biblioteca-reader` (TypeScript).

| Kind | Java `CollectionKind` / TS `CollectionKind` | Notes |
|---|---|---|
| Favourites | `FAVORITES` / `'favorites'` | One per user, created at registration. Not deletable, not renamable. |
| User collection | `USER` / `'user'` | Created, renamed, deleted and sorted by the user. Entries: a whole work/chapter (`DIV`) or a quote (`FRAGMENT`). |
| Search results | `SEARCH` / `'search'` | The hits of a search (`SNIPPET` entries: stable path + matched excerpt + score). Name defaults to the query, user-editable. Keeps `searchQuery` and `searchParams` (JSON). |

## Common interface

- Java: `ro.editii.scriptorium.collection.ItemCollection` (`id`, `name`, `kind`, `createdAt`, `items`) and `CollectionEntry` (`id`, `divPath`, `addedAt`). Implemented by the `DivCollection` / `DivCollectionItem` entities and by their DTOs.
- TypeScript: `Collection<Entry>` and `CollectionEntry` in `src/types/textbase.ts`; `PersonalCollection` (favourites + user) and `SavedSearch` extend it.
- One table pair holds all kinds (`book_collection`, `book_collection_item`), discriminated by `kind`.

## Identity

- Collections and entries have UUID ids (stored as `VARCHAR(36)`), never incrementing numbers. The reader generates UUIDs for local collections as well.
- Works and chapters inside a collection are referenced by their stable path (`authorId/opusId[/...]`), never by database id or title.
- Migration `V10__Collections_with_uuid_ids` copies the old `DIV_COLLECTION*` rows to the new tables with fresh UUIDs (favourites flag -> kind) and drops the old tables. The reader rewrites legacy `col-...` / `item-...` ids to UUIDs when it loads them.

## Sorting

Entries carry a `sortOrder`; `PUT /api/collections/mine/{id}/order` takes the entry ids in the wanted order.

## API (all under `/api/collections/mine`, authentication required)

| Call | Purpose |
|---|---|
| `GET /` , `GET /{id}` | list / read (also GraphQL `myCollections`, `myCollection(id:)`) |
| `POST /` | create a user collection `{name}` |
| `PATCH /{id}` | rename `{name}` |
| `DELETE /{id}` | delete (not favourites) |
| `POST /{id}/items`, `DELETE /{id}/items/{itemId}` | add / remove an entry |
| `PUT /{id}/order` | sort entries `{itemIds}` |
| `POST /searches` | save a search `{name?, query, params, hits:[{path, snippet, score}]}`; a taken name gets a ` (2)` suffix |

## Reader

- Same-origin proxies: `POST /graphql` and the collection routes above (`server.ts`).
- Search page: "Save search" button saves the results shown.
- Collections page: "Saved searches" section (open, run again, rename, delete).
- Storage: a signed-in reader's saved searches live in the server DB (REST to write, `/graphql` to read); an anonymous reader's stay in localStorage.

## Not yet done

- Favourites and user collections in the reader are still stored as a preferences blob (now with UUID ids and a `kind`). The server API above is ready to take them over; switching the reader to it (and importing the blob once) is the next step.
- `rake gen-client` must be run against the redeployed server to regenerate the typed client for the new REST calls.
