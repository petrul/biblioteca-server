# The GraalVM native image

How this was made to work, as a recipe for other Java servers:
`work/scripts/docs/graalvm-native-image.md`.

`rake ci-graalvm` (TeamCity's *GraalVM Build*) compiles the server ahead of
time into one executable, published as `editii/biblioteca-server:<version>-graalvm`;
`rake graalvm[profile]` does the same locally (needs a GraalVM JDK as
`GRAALVM_HOME`/`JAVA_HOME`; ~14 min, ~16 GB peak, every core - not on a host
serving production: two builds on zmeu starved Biblioteca prod's chapter
pages until its connection pool ran dry). Deployments choose it per
stack with `BIBLIOTECA_SERVER_FLAVOR=graalvm` (`work/scripts/docker/biblioteca`);
a release is always deployed on the JVM first.

Measured on zmeu (0.9.17-SNAPSHOT, dev profile, 2026-11-07): starts in ~1 s
instead of ~22 s, ~340 MB resident after start and ~540 MB after serving the
release checks, against ~970 MB on the JVM. Lucene runs without the JDK's
SIMD vector API natively ("Java runtime is not using Hotspot VM").
Known, harmless at startup: a few "Failed to search in directory
[/ro/editii/scriptorium/{media,model,embedding}] for files matching pattern
[**/*.class]" warnings - a runtime classpath scan by Spring that finds
nothing in a native image; the entities and repositories are set up at
build time.

## What the image must be told

A native image only contains the classes, reflection, resources, serializable
types and native calls known at build time. Spring's AOT processing covers
what Spring itself knows; the rest:

- `src/main/resources/META-INF/native-image/ro.editii/biblioteca-server/reachability-metadata.json`
  - **generated**: everything GraalVM's tracing agent saw while the whole test
  suite ran (`rake graalvm-metadata`), plus optional extra captures, minus the
  test code itself (`filter-agent-metadata.py`). It covers this app's own
  reflection, ICU4J (Util's transliterator - without it Util, then Author,
  fail to initialize: 0.9.16-graalvm's "Unable to load class Author"),
  Caffeine's cache classes, Groovy's runtime, and Lucene's native calls.
  The filter also leaves out Hibernate's ByteBuddy bytecode provider (a
  native image cannot generate classes; Hibernate then runs without one) and
  `.class` files listed as resources.
- `src/main/java/ro/editii/scriptorium/NativeImageHints.java` - **by hand**:
  Author's resource files, all of ICU4J's data, and the types a sign-in
  session holds (sessions are persisted and reloaded on start - none of the
  tests exercises that, so the agent cannot see it).
- Flyway's migrations are **listed** in
  `src/main/resources/META-INF/services/org.flywaydb.core.api.migration.JavaMigration`:
  the native image cannot scan the classpath for them. `MigrationsListedTest`
  fails when one is missing.

## Regenerating the metadata

When the native server fails with `MissingReflectionRegistrationError`,
`MissingForeignRegistrationError`, `NoClassDefFoundError: Could not
initialize class`, or after adding a library:

```bash
G=~/.sdkman/candidates/java/25.4.4+1-graal       # a GraalVM JDK
JAVA_HOME=$G rake graalvm-metadata               # the whole test suite under the agent (~50 min)
```

Paths no test exercises can be captured from the server itself and merged:

```bash
rake build[zmeu]
source ~/.password-store/load-env.sh biblioteca/zmeu
$G/bin/java -agentlib:native-image-agent=config-output-dir=/tmp/agent-run \
  -Xmx1g -Xss20m --enable-native-access=ALL-UNNAMED \
  -Dspring.profiles.active=autoimport,zmeu \
  -jar build/libs/biblioteca-server-*-application.jar
# serve the release's verify.py through the dev reader, browse the reader; Ctrl-C
JAVA_HOME=$G EXTRA_CAPTURES=/tmp/agent-run/reachability-metadata.json rake graalvm-metadata
```

## Checking a native build

```bash
JAVA_HOME=$G GRAALVM_HOME=$G rake graalvm[ci]
source ~/.password-store/load-env.sh biblioteca/zmeu
SPRING_PROFILES_ACTIVE=autoimport,zmeu build/native/nativeCompile/biblioteca-server -Xmx1g -Xss20m
```

In place of the dev server (port 8080), behind the dev reader: run the
release's `verify.py` (`BARE_METAL=1`), browse the reader, and grep the log
for ERROR, exceptions and `Missing*Registration`. Sessions: write a signed-in
session in Tomcat's format into `<work dir>/sessions/SESSIONS.ser`, start the
binary, and check `GET /api/users/me` with that session's cookie answers
`authenticated: true`.
