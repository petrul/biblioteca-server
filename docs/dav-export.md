# Read-only DAV export

`.dav`, `/dav` — projects the corpus as a mountable
`language/author/work/chapter` virtual filesystem (`OPTIONS`, `PROPFIND`,
`GET`, `HEAD`; mutation methods return `405`).

| Parameter | Values | Default | Meaning |
| --- | --- | --- | --- |
| `format` | `txt`, `json`, `xml`, `xhtml` | `txt` | Serialization; `xml` is the original TEI fragment |
| `fragmentation` | `1`, `1.1`, `1.1.1` | `1` | Deepest division exposed as a file (work/chapter/subchapter) |
| `lang` | two-letter code | all | Restrict to works detected in that language |
| `author` | canonical author id | all | Restrict to that author's works |

Don't use the query-param form as a DAV *mount* URL (clients drop query
strings on child `href`s) — mount the path-configured form, which embeds the
same filters in the path:

```
/dav/_export/{format}/{fragmentation}/{language-or-all}/{author-or-all}/
```

`PROPFIND` supports depths `0` and `1` only; `Depth: infinity` is rejected so
one request can't materialize the whole corpus. Fragmentation selects a
frontier, not a requirement — each terminal file contains its complete
remaining subtree, so shallower branches never lose text.
