package dev.agentic.harness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded POSIX point-in-time observations. No shell, network, or instruction discovery. */
final class RepositoryFiles {
    record Limits(int maxFileBytes, int maxEntries, int maxSearchHits,
                  int maxOperations, int maxReturnedBytes, int maxSnapshotBytes) {
        Limits {
            if (maxFileBytes < 1 || maxFileBytes > 4 * 1024 * 1024 || maxEntries < 1 || maxEntries > 8192
                    || maxSearchHits < 1 || maxSearchHits > 1024 || maxOperations < 1 || maxOperations > 256
                    || maxReturnedBytes < 1 || maxReturnedBytes > 32 * 1024 * 1024
                    || maxSnapshotBytes < 1 || maxSnapshotBytes > 64 * 1024 * 1024)
                throw new IllegalArgumentException("INVALID_REPOSITORY_LIMITS");
        }
        static Limits defaults() { return new Limits(1024 * 1024, 2048, 128, 64, 4 * 1024 * 1024, 16 * 1024 * 1024); }
    }

    record Snapshot(String rootFilesystemIdentity, List<Map<String, Object>> entries,
                    List<String> protectedPaths, List<String> excludedPaths) {
        Snapshot { entries = List.copyOf(entries); protectedPaths = List.copyOf(protectedPaths); excludedPaths = List.copyOf(excludedPaths); }
    }
    private record Observed(Path path, Map<String, Object> attributes) {}
    private final Path root;
    private final String rootIdentity;
    private final List<Observed> rootChain;
    private final Limits limits;
    private final List<String> protectedPaths, excludedPaths;
    private final Consumer<String> credentialCheck;

    RepositoryFiles(Path root, String rootIdentity, Limits limits, Set<String> protectedPaths,
                    Set<String> excludedPaths, Consumer<String> credentialCheck) throws IOException {
        this.root = root; this.rootIdentity = rootIdentity; this.limits = limits;
        this.protectedPaths = sortedPaths(protectedPaths); this.excludedPaths = sortedPaths(excludedPaths);
        this.credentialCheck = Objects.requireNonNull(credentialCheck);
        if (!root.isAbsolute() || !root.equals(root.normalize()) || !root.toRealPath().equals(root))
            throw new IOException("REPOSITORY_ROOT_INVALID");
        List<Observed> observed = new ArrayList<>();
        Path current = root.getRoot(); observed.add(observe(current, true));
        for (Path component : root) { current = current.resolve(component); observed.add(observe(current, true)); }
        rootChain = List.copyOf(observed);
        verify();
    }

    static String canonicalPath(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 4096 || value.startsWith("/") || value.endsWith("/")
                || value.contains("//") || value.contains(":") || value.contains("\\")
                || value.chars().anyMatch(c -> c < 32 || c > 126))
            throw new IOException("NONCANONICAL_REPOSITORY_PATH");
        String[] parts = value.split("/", -1);
        if (parts.length > 128) throw new IOException("REPOSITORY_DEPTH_LIMIT");
        for (String part : parts) if (part.equals(".") || part.equals("..") || part.isEmpty())
            throw new IOException("REPOSITORY_PATH_TRAVERSAL");
        Json.identifier(value);
        return value;
    }

    private static List<String> sortedPaths(Set<String> paths) throws IOException {
        TreeSet<String> sorted = new TreeSet<>(); Set<String> folded = new HashSet<>();
        for (String path : paths) {
            canonicalPath(path);
            if (!folded.add(path.toLowerCase(Locale.ROOT))) throw new IOException("REPOSITORY_PATH_COLLISION");
            sorted.add(path);
        }
        return List.copyOf(sorted);
    }

    boolean prohibited(String path) {
        return prohibited(path, protectedPaths) || prohibited(path, excludedPaths);
    }
    private static boolean prohibited(String path, List<String> boundaries) {
        String folded = path.toLowerCase(Locale.ROOT);
        return boundaries.stream().map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(p -> folded.equals(p) || folded.startsWith(p + "/"));
    }
    private void allowed(String path) throws IOException {
        canonicalPath(path);
        if (prohibited(path)) throw new IOException("PROTECTED_AREA_ACCESS_REQUIRED");
        if (path.toLowerCase(Locale.ROOT).equals(".git") || path.toLowerCase(Locale.ROOT).startsWith(".git/"))
            throw new IOException("GIT_CONTENT_OBSERVATION_UNSUPPORTED");
    }

    void verify() throws IOException {
        recheck(rootChain);
        if (!root.toRealPath().equals(root) || !identity(rootChain.getLast().attributes()).equals(rootIdentity))
            throw new IOException("REPOSITORY_ROOT_CHANGED");
    }

    private List<Observed> chain(String path, boolean allowAbsent) throws IOException {
        allowed(path); verify();
        List<Observed> result = new ArrayList<>(); Path current = root;
        String[] parts = path.split("/");
        for (int index = 0; index < parts.length; index++) {
            current = current.resolve(parts[index]);
            if (!current.startsWith(root)) throw new IOException("REPOSITORY_PATH_ESCAPE");
            try { result.add(observe(current, index != parts.length - 1)); }
            catch (NoSuchFileException absent) {
                if (allowAbsent && index == parts.length - 1) { recheck(result); verify(); return result; }
                throw new IOException("REPOSITORY_PARENT_UNAVAILABLE");
            }
            if (!current.toRealPath().equals(current)) throw new IOException("REPOSITORY_PATH_ALIAS");
        }
        recheck(result); verify(); return result;
    }

    private static Observed observe(Path path, boolean directory) throws IOException {
        Map<String, Object> attrs = Files.readAttributes(path, "unix:dev,ino,mode,nlink,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS);
        int kind = ((Number) attrs.get("mode")).intValue() & 0170000;
        if (kind != 0040000 && (directory || kind != 0100000 || ((Number) attrs.get("nlink")).longValue() != 1))
            throw new IOException("REPOSITORY_PATH_UNSAFE");
        return new Observed(path, attrs);
    }
    private static String identity(Map<String, Object> attrs) {
        return "POSIX:" + Long.toUnsignedString(((Number) attrs.get("dev")).longValue()) + ":"
                + Long.toUnsignedString(((Number) attrs.get("ino")).longValue());
    }
    private static void recheck(List<Observed> entries) throws IOException {
        for (Observed entry : entries) {
            boolean directory = (((Number) entry.attributes().get("mode")).intValue() & 0170000) == 0040000;
            Map<String, Object> current = observe(entry.path(), directory).attributes();
            // Parent directory size, link count and mtime change during permitted CREATEs.
            if (directory ? !identity(current).equals(identity(entry.attributes()))
                    || !current.get("mode").equals(entry.attributes().get("mode")) : !entry.attributes().equals(current))
                throw new IOException("REPOSITORY_STATE_CHANGED");
        }
    }

    byte[] readBytes(String path) throws IOException {
        List<Observed> entries = chain(path, false); Observed file = entries.getLast();
        if ((((Number) file.attributes().get("mode")).intValue() & 0170000) != 0100000
                || ((Number) file.attributes().get("size")).longValue() > limits.maxFileBytes())
            throw new IOException("REPOSITORY_FILE_LIMIT_OR_TYPE");
        byte[] bytes;
        try (var stream = Files.newInputStream(file.path(), LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(limits.maxFileBytes() + 1);
        }
        if (bytes.length > limits.maxFileBytes()) throw new IOException("REPOSITORY_FILE_LIMIT");
        recheck(entries); verify();
        safe(text(bytes));
        recheck(entries); verify();
        return bytes;
    }
    static String text(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    void safe(Object value) { Evidence.inspectSecretMaterial(value, credentialCheck); }

    Map<String, Object> state(String path, boolean allowAbsent) throws IOException {
        if ("".equals(path)) {
            // The registered root has directory metadata, but is never a file path
            // for content reads or mutations. Keep the file-path gate unchanged.
            verify();
            Observed entry = observe(root, true);
            recheck(List.of(entry)); verify();
            return pathState("", "PRESENT", "DIRECTORY", null, identity(entry.attributes()),
                    ((Number) entry.attributes().get("nlink")).longValue());
        }
        List<Observed> entries = chain(path, allowAbsent);
        if (entries.size() != path.split("/").length) return absent(path);
        Observed entry = entries.getLast();
        boolean directory = (((Number) entry.attributes().get("mode")).intValue() & 0170000) == 0040000;
        Map<String, Object> fingerprint = directory ? null : Json.fingerprint("PATH_CONTENT:" + path, readBytes(path));
        recheck(entries); verify();
        return pathState(path, "PRESENT", directory ? "DIRECTORY" : "REGULAR_FILE", fingerprint,
                identity(entry.attributes()), ((Number) entry.attributes().get("nlink")).longValue());
    }
    static Map<String, Object> absent(String path) {
        return pathState(path, "ABSENT", "ABSENT", null, null, null);
    }
    private static Map<String, Object> pathState(String path, String existence, String type,
                                               Map<String, Object> fingerprint, String identity, Long links) {
        return Json.object("pathKey", path, "existence", existence, "fileType", type,
                "contentFingerprint", fingerprint, "symlinkTargetBase64", null,
                "filesystemIdentity", identity, "linkCount", links, "gitTracking", "NOT_APPLICABLE",
                "indexStatus", "NOT_APPLICABLE", "worktreeStatus", "NOT_APPLICABLE", "gitRelatedPathKey", null);
    }

    private List<String> children(String directory) throws IOException {
        if (!directory.isEmpty()) {
            List<Observed> chain = chain(directory, false);
            if ((((Number) chain.getLast().attributes().get("mode")).intValue() & 0170000) != 0040000)
                throw new IOException("REPOSITORY_DIRECTORY_REQUIRED");
        }
        verify();
        List<String> result = new ArrayList<>(); Set<String> collisionKeys = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory.isEmpty() ? root : root.resolve(directory))) {
            int visited = 0;
            for (Path entry : stream) {
                if (++visited > limits.maxEntries()) throw new IOException("REPOSITORY_ENTRY_LIMIT_INCOMPLETE");
                String path = root.relativize(entry).toString(); canonicalPath(path);
                if (!collisionKeys.add(path.toLowerCase(Locale.ROOT))) throw new IOException("REPOSITORY_PATH_COLLISION");
                // Recognize boundary names without metadata lookup or entry traversal.
                if (!prohibited(path)) result.add(path);
            }
        }
        Collections.sort(result); verify(); return List.copyOf(result);
    }

    Map<String, Object> list(String directory) throws IOException {
        List<String> paths = children(directory);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String path : paths) {
            List<Observed> checked = chain(path, false);
            var last = checked.getLast();
            entries.add(Json.object("pathKey", path, "fileType",
                    (((Number) last.attributes().get("mode")).intValue() & 0170000) == 0040000 ? "DIRECTORY" : "REGULAR_FILE"));
        }
        return Json.object("entries", entries, "complete", true, "truncated", false,
                "protectedPathKeys", protectedPaths, "excludedPathKeys", excludedPaths);
    }

    Map<String, Object> search(String path, String query) throws IOException {
        if (query == null || query.isEmpty() || query.length() > 1024 || query.contains("\n"))
            throw new IOException("SEARCH_QUERY_INVALID");
        String text = text(readBytes(path)); List<Map<String, Object>> hits = new ArrayList<>();
        int start = 0, line = 1;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start); if (end < 0) end = text.length();
            String content = text.substring(start, end);
            if (content.contains(query)) {
                if (hits.size() == limits.maxSearchHits()) throw new IOException("SEARCH_HIT_LIMIT_INCOMPLETE");
                hits.add(Json.object("line", line, "text", content));
            }
            if (end == text.length()) break; start = end + 1; line++;
        }
        return Json.object("hits", hits, "complete", true, "truncated", false);
    }

    Snapshot snapshot(Set<String> additionalPaths) throws IOException {
        verify(); TreeMap<String, Map<String, Object>> entries = new TreeMap<>();
        Deque<String> pending = new ArrayDeque<>(children("")); Set<String> collisions = new HashSet<>();
        long remaining = limits.maxSnapshotBytes();
        while (!pending.isEmpty()) {
            String path = pending.removeFirst();
            if (entries.size() >= limits.maxEntries()) throw new IOException("SNAPSHOT_ENTRY_LIMIT_INCOMPLETE");
            if (!collisions.add(path.toLowerCase(Locale.ROOT))) throw new IOException("REPOSITORY_PATH_COLLISION");
            Map<String, Object> state = state(path, false); entries.put(path, state);
            if (state.get("contentFingerprint") instanceof Map<?, ?> fingerprint)
                if ((remaining -= ((Number) fingerprint.get("byteLength")).longValue()) < 0)
                    throw new IOException("SNAPSHOT_BYTE_LIMIT_INCOMPLETE");
            if (state.get("fileType").equals("DIRECTORY")) {
                List<String> nested = children(path);
                if ((long) entries.size() + pending.size() + nested.size() > limits.maxEntries())
                    throw new IOException("SNAPSHOT_ENTRY_LIMIT_INCOMPLETE");
                pending.addAll(nested);
            }
        }
        for (String path : sortedPaths(additionalPaths)) {
            if (entries.containsKey(path)) continue;
            if (entries.size() >= limits.maxEntries() || !collisions.add(path.toLowerCase(Locale.ROOT)))
                throw new IOException("SNAPSHOT_ENTRY_LIMIT_OR_COLLISION");
            Map<String, Object> absent = state(path, true);
            if (!absent.get("existence").equals("ABSENT")) throw new IOException("SNAPSHOT_STATE_CHANGED");
            entries.put(path, absent);
        }
        verify(); List<Map<String, Object>> retained = List.copyOf(entries.values()); safe(retained); verify();
        return new Snapshot(rootIdentity, retained, protectedPaths, excludedPaths);
    }

    /** Fresh read-only terminal observer; never restores a tool capability or mutation grant. */
    Snapshot observeTerminatedSnapshot(Set<String> additionalPaths, Consumer<String> credentialCheck) throws IOException {
        verify();
        RepositoryFiles observer = new RepositoryFiles(root, rootIdentity, limits,
                Set.copyOf(protectedPaths), Set.copyOf(excludedPaths), credentialCheck);
        Snapshot first = observer.snapshot(additionalPaths);
        if (!first.equals(observer.snapshot(additionalPaths))) throw new IOException("TERMINAL_OBSERVATION_CHANGED");
        verify(); return first;
    }

    Map<String, Object> write(String path, String action, Map<String, Object> expectedBefore,
                              Map<String, Object> expectedContentFingerprint, String content) throws IOException {
        allowed(path); safe(content);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxFileBytes()) throw new IOException("REPOSITORY_FILE_LIMIT");
        Map<String, Object> before = state(path, true);
        if (!before.equals(expectedBefore) || !Objects.equals(before.get("contentFingerprint"), expectedContentFingerprint))
            throw new IOException("MUTATION_BEFORE_STATE_MISMATCH");
        if (action.equals("CREATE") ? !before.get("existence").equals("ABSENT")
                : !action.equals("MODIFY") || !before.get("fileType").equals("REGULAR_FILE"))
            throw new IOException("MUTATION_ACTION_MISMATCH");
        // No intermediate directory creation or replacing moves. Existing inode is preserved for MODIFY.
        Set<OpenOption> options = action.equals("CREATE")
                ? Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
                : Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        List<Observed> checked = chain(path, action.equals("CREATE")); recheck(checked); verify();
        try (SeekableByteChannel channel = Files.newByteChannel(root.resolve(path), options)) {
            if (!action.equals("CREATE")) {
                recheck(checked); verify();
                channel.position(0);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.truncate(bytes.length);
        }
        verify(); Map<String, Object> after = state(path, false);
        if (!Json.fingerprint("PATH_CONTENT:" + path, bytes).equals(after.get("contentFingerprint")))
            throw new IOException("MUTATION_AFTER_STATE_MISMATCH");
        return after;
    }

    static String parentKey(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Some POSIX filesystems count directory entries as links. Only the immediate
     * CREATE parent may gain one link; every other state field must be identical. */
    static boolean createParentTransition(String file, Map<String,Object> before, Map<String,Object> after) {
        if (!parentKey(file).equals(before.get("pathKey")) || !"DIRECTORY".equals(before.get("fileType"))
                || !(before.get("linkCount") instanceof Number left) || !(after.get("linkCount") instanceof Number right)
                || right.longValue() != left.longValue() + 1) return false;
        Map<String,Object> expected = new LinkedHashMap<>(before);
        expected.put("linkCount", after.get("linkCount"));
        return expected.equals(after);
    }

    String canonicalScope(String path) { return path.isEmpty() ? root.toString() : root.resolve(path).toString(); }
}
