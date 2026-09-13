package dev.agentic.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Explicit host source designation and locator metadata only. No content reads,
 * Git discovery, instruction loading, subprocesses, or mutation capability.
 * Checks are bounded point-in-time observations, not race-free confinement.
 */
final class SourceBoundary {
    enum Authority { READ_ONLY }
    enum State { CLOSED, METADATA_ONLY }

    record Metadata(String declaredRoot, String resolvedRoot, String rootFilesystemIdentity) {
        Map<String, Object> view() {
            return Json.object("sourceScope", Json.object("declaredRoot", declaredRoot,
                    "resolvedRoot", resolvedRoot, "rootFilesystemIdentity", rootFilesystemIdentity),
                    "authority", Authority.READ_ONLY.name(), "contentAccessImplemented", false);
        }
    }

    private record Entry(Path path, Map<String, Object> attributes) {}
    private record Snapshot(Metadata metadata, List<Entry> entries) {}

    private State state = State.CLOSED;
    private Snapshot registered;
    private Path sourceRoot, trustedRoot, targetRoot;
    private boolean terminated;

    Authority authority() { return Authority.READ_ONLY; }
    State state() { return state; }
    Metadata metadata() { return registered == null ? null : registered.metadata(); }
    void close() { state = State.CLOSED; terminated = true; }

    Metadata register(Path source, Path trusted, Path target) throws IOException {
        try {
            if (terminated || registered != null) throw new IOException("SOURCE_ALREADY_REGISTERED");
            Snapshot observed = observe(source, trusted, target);
            sourceRoot = source;
            trustedRoot = trusted;
            targetRoot = target;
            registered = observed;
            state = State.METADATA_ONLY;
            return observed.metadata();
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IOException("SOURCE_METADATA_REJECTED");
        }
    }

    void verify() throws IOException {
        try {
            if (terminated || state != State.METADATA_ONLY || registered == null
                    || !registered.equals(observe(sourceRoot, trustedRoot, targetRoot)))
                throw new IOException("SOURCE_METADATA_CHANGED");
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IOException("SOURCE_METADATA_REJECTED");
        }
    }

    /** Validates an existing host locator; returns metadata, never file contents or an open handle. */
    Map<String, Object> inspectLocator(Path locator) throws IOException {
        try {
            verify();
            validateSpelling(locator);
            Path resolved = locator.isAbsolute() ? locator : sourceRoot.resolve(locator);
            if (!resolved.startsWith(sourceRoot) || resolved.equals(sourceRoot))
                throw new IOException("SOURCE_LOCATOR_ESCAPE");
            validateSpelling(resolved);
            Path relative = sourceRoot.relativize(resolved);
            List<Entry> entries = new ArrayList<>();
            Path component = sourceRoot;
            int remaining = relative.getNameCount();
            for (Path part : relative) {
                component = component.resolve(part);
                entries.add(entry(component, --remaining != 0));
            }
            if (!resolved.toRealPath().equals(resolved)) throw new IOException("SOURCE_LOCATOR_ALIAS");
            recheck(entries);
            verify();
            Map<String, Object> last = entries.getLast().attributes();
            return Json.object("sourceRelativePath", relative.toString(),
                    "filesystemIdentity", last.get("filesystemIdentity"),
                    "kind", (((Number) last.get("mode")).intValue() & 0170000) == 0040000 ? "DIRECTORY" : "REGULAR_FILE",
                    "authority", authority().name(), "contentRead", false);
        } catch (IOException | RuntimeException failure) {
            close();
            throw new IOException("SOURCE_LOCATOR_REJECTED");
        }
    }

    private static Snapshot observe(Path source, Path trusted, Path target) throws IOException {
        List<Entry> entries = new ArrayList<>();
        List<List<Entry>> rootChains = new ArrayList<>();
        for (Path root : List.of(source, trusted, target)) {
            validateSpelling(root);
            if (!root.isAbsolute() || root.getParent() == null
                    || !root.getFileSystem().supportedFileAttributeViews().contains("unix"))
                throw new IOException("SOURCE_ROOT_INVALID");
            Path component = root.getRoot();
            List<Entry> chain = new ArrayList<>();
            chain.add(entry(component, true));
            for (Path part : root) {
                component = component.resolve(part);
                chain.add(entry(component, true));
            }
            if (!root.toRealPath().equals(root)) throw new IOException("SOURCE_ROOT_ALIAS");
            entries.addAll(chain);
            rootChains.add(List.copyOf(chain));
        }
        if (overlaps(source, trusted) || overlaps(source, target) || overlaps(trusted, target)
                || overlapsByIdentity(rootChains.get(0), rootChains.get(1))
                || overlapsByIdentity(rootChains.get(0), rootChains.get(2))
                || overlapsByIdentity(rootChains.get(1), rootChains.get(2)))
            throw new IOException("SOURCE_ROOT_OVERLAP");
        recheck(entries);
        var sourceIdentity = entries.stream().filter(e -> e.path().equals(source)).findFirst().orElseThrow();
        return new Snapshot(new Metadata(source.toString(), source.toString(),
                (String) sourceIdentity.attributes().get("filesystemIdentity")), List.copyOf(entries));
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static boolean overlapsByIdentity(List<Entry> first, List<Entry> second) {
        Object firstRoot = first.getLast().attributes().get("filesystemIdentity");
        Object secondRoot = second.getLast().attributes().get("filesystemIdentity");
        return first.stream().anyMatch(e -> e.attributes().get("filesystemIdentity").equals(secondRoot))
                || second.stream().anyMatch(e -> e.attributes().get("filesystemIdentity").equals(firstRoot));
    }

    private static void validateSpelling(Path path) throws IOException {
        if (path == null || path.toString().isEmpty() || !path.equals(path.normalize())
                || path.getNameCount() > 128 || path.toString().length() > 8192
                || !Normalizer.isNormalized(path.toString(), Normalizer.Form.NFC)
                || path.toString().codePoints().anyMatch(Character::isISOControl))
            throw new IOException("SOURCE_PATH_INVALID");
        for (Path part : path) {
            if (part.toString().equals("..") || part.toString().equals("."))
                throw new IOException("SOURCE_PATH_TRAVERSAL");
            if (part.toString().contains(":") || part.toString().contains("\\"))
                throw new IOException("SOURCE_PATH_NONCANONICAL");
        }
        Json.identifier(path.toString());
    }

    private static Entry entry(Path path, boolean directoryRequired) throws IOException {
        var attrs = Files.readAttributes(path, "unix:dev,ino,mode,nlink", LinkOption.NOFOLLOW_LINKS);
        int kind = ((Number) attrs.get("mode")).intValue() & 0170000;
        if (kind != 0040000 && (directoryRequired || kind != 0100000
                || ((Number) attrs.get("nlink")).longValue() != 1))
            throw new IOException("SOURCE_PATH_UNSAFE");
        return new Entry(path, Json.object("filesystemIdentity",
                "POSIX:" + Long.toUnsignedString(((Number) attrs.get("dev")).longValue())
                        + ":" + Long.toUnsignedString(((Number) attrs.get("ino")).longValue()),
                "mode", attrs.get("mode"), "fileLinkCount", kind == 0100000 ? attrs.get("nlink") : null));
    }

    private static void recheck(List<Entry> entries) throws IOException {
        for (Entry before : entries) {
            boolean directory = (((Number) before.attributes().get("mode")).intValue() & 0170000) == 0040000;
            if (!before.equals(entry(before.path(), directory))) throw new IOException("SOURCE_PATH_CHANGED");
        }
    }
}
