#!/usr/bin/env python3
"""Merges GraalVM tracing-agent captures into the native image's metadata,
leaving out only what belongs to the tests themselves.

    filter-agent-metadata.py <build dir> <agent reachability-metadata.json>... > \
        src/main/resources/META-INF/native-image/ro.editii/biblioteca-server/reachability-metadata.json

The captures come from the whole test suite run under the agent (gradle
-PnativeAgentDir=...) and from the server itself serving the release
checks (see native/README.md). Everything the agent saw is kept - the
app, every library (Caffeine, ICU4J, Groovy...), the JDK - except the test
frameworks and the project's own test classes and resources (listed from
<build dir>/classes/*/test and <build dir>/resources/test): keeping what is
not needed only costs a little image size, missing what is needed fails at
run time.
"""
import json, os, sys

TEST_TYPE_PREFIXES = (
    'org.junit.', 'junit.', 'org.opentest4j.', 'org.apiguardian.',
    'org.mockito.', 'net.bytebuddy.', 'org.objenesis.',
    'org.spockframework.', 'spock.',
    'org.springframework.test.', 'org.springframework.boot.test.', 'org.springframework.mock.',
    'org.gradle.', 'worker.org.gradle.',
    'org.assertj.', 'org.hamcrest.', 'org.awaitility.', 'org.testcontainers.',
)
# Not test code, but must stay out of the native image: Hibernate's ByteBuddy
# bytecode provider generates classes at run time, which a native image
# cannot - announced through its service file, Hibernate picks it and fails
# ("BytecodeProviderImpl could not be instantiated"). Natively Hibernate
# runs without one (hibernate-graalvm).
JVM_ONLY_TYPE_PREFIXES = ('org.hibernate.bytecode.internal.bytebuddy.',)
JVM_ONLY_RESOURCES = ('META-INF/services/org.hibernate.bytecode.spi.BytecodeProvider',)
TEST_RESOURCE_PREFIXES = ('org/junit', 'junit', 'org/mockito', 'mockito-extensions', 'org/gradle',
                          'META-INF/services/org.junit', 'org/spockframework', 'META-INF/services/org.spockframework')

def test_types_and_resources(build_dir):
    types, resources = set(), set()
    classes = os.path.join(build_dir, 'classes')
    for lang in (os.listdir(classes) if os.path.isdir(classes) else []):
        root = os.path.join(classes, lang, 'test')
        for dirpath, _, files in os.walk(root):
            for f in files:
                if f.endswith('.class'):
                    rel = os.path.relpath(os.path.join(dirpath, f), root)[:-len('.class')]
                    types.add(rel.replace(os.sep, '.'))
    root = os.path.join(build_dir, 'resources', 'test')
    for dirpath, _, files in os.walk(root):
        for f in files:
            resources.add(os.path.relpath(os.path.join(dirpath, f), root).replace(os.sep, '/'))
    return types, resources

def type_name(entry):
    t = entry.get('type')
    if isinstance(t, str):
        # Groovy probes for optional custom metaclasses named after a type:
        # groovy.runtime.metaclass.<type>MetaClass - judge them by that type
        if t.startswith('groovy.runtime.metaclass.') and t.endswith('MetaClass'):
            return t[len('groovy.runtime.metaclass.'):-len('MetaClass')]
        return t
    if isinstance(t, dict):
        if 'lambda' in t:  # {"lambda": {"declaringClass": ..., "interfaces": [...]}}
            return t['lambda'].get('declaringClass', '')
        return ' '.join(t.get('proxy', []))  # {"proxy": [...]}
    return ''

def condition_type(entry):
    c = entry.get('condition') or {}
    return c.get('typeReached') or c.get('typeReachable') or ''

def is_test_type(name, test_types):
    if not name:
        return False
    if name.startswith(TEST_TYPE_PREFIXES) or name.startswith(JVM_ONLY_TYPE_PREFIXES):
        return True
    outer = name.split('$')[0].rstrip('[]')
    return outer in test_types

def keep_type_entry(entry, test_types):
    return not (is_test_type(type_name(entry), test_types) or is_test_type(condition_type(entry), test_types))

def keep_resource_entry(entry, test_resources, test_types):
    pattern = entry.get('glob') or entry.get('bundle') or ''
    if pattern in test_resources or pattern.startswith(TEST_RESOURCE_PREFIXES) or pattern in JVM_ONLY_RESOURCES:
        return False
    # .class files read as resources (the JVM's classpath scanning): the
    # image has no class files - listing their names only makes runtime
    # scans look for them ("Failed to search in directory ... NoSuchFile").
    if pattern.endswith('.class'):
        return False
    return not is_test_type(condition_type(entry), test_types)

def main():
    build_dir, captures = sys.argv[1], sys.argv[2:]
    test_types, test_resources = test_types_and_resources(build_dir)
    sections, nested = {}, {}
    for path in captures:
        for section, entries in json.load(open(path)).items():
            if isinstance(entries, dict):
                # e.g. "foreign": {"downcalls": [...], "upcalls": [...]} - Lucene's
                # native calls (memory-mapped index files); merged, all kept
                for kind, items in entries.items():
                    bucket = nested.setdefault(section, {}).setdefault(kind, {})
                    for item in items:
                        bucket[json.dumps(item, sort_keys=True)] = item
                continue
            if isinstance(entries, list):
                sections.setdefault(section, {})
                for e in entries:
                    keep = keep_resource_entry(e, test_resources, test_types) if section == 'resources' else keep_type_entry(e, test_types)
                    if keep:
                        sections[section][json.dumps(e, sort_keys=True)] = e
    out = {s: [entries[k] for k in sorted(entries)] for s, entries in sorted(sections.items()) if entries}
    for section, kinds in nested.items():
        out[section] = {kind: [items[k] for k in sorted(items)] for kind, items in sorted(kinds.items())}
    json.dump(out, sys.stdout, indent=1, sort_keys=True)
    sys.stdout.write('\n')
    print({s: (len(v) if isinstance(v, list) else {k: len(i) for k, i in v.items()}) for s, v in out.items()}, f'(left out {len(test_types)} test classes, {len(test_resources)} test resources)', file=sys.stderr)

main()
