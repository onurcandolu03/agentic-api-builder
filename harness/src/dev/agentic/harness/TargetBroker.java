package dev.agentic.harness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Host metadata only; no subprocesses, source reads, or model-facing target tools.
 * Supports POSIX directories and ordinary Git worktrees with format 0 and a
 * simple core-only config. Gitfiles, bare repositories, enclosing repositories,
 * includes, extensions, worktree overrides, and other config are unsupported.
 * Observations are bounded point-in-time checks, not race-free confinement.
 */
final class TargetBroker {
    enum Phase { CLOSED, METADATA_ONLY, READ_ALLOWED, MUTATION_ALLOWED, VALIDATION_ALLOWED }

    record Metadata(String declaredRoot, String resolvedRoot, String repositoryKind,
                    String rootFilesystemIdentity, String gitWorktreeRoot) {
        Map<String, Object> scope() {
            return Json.object("declaredRoot", declaredRoot, "resolvedRoot", resolvedRoot,
                    "repositoryKind", repositoryKind, "rootFilesystemIdentity", rootFilesystemIdentity);
        }

        Map<String, Object> view() {
            return Json.object("targetScope", scope(), "gitWorktreeRoot", gitWorktreeRoot);
        }
    }

    private record Snapshot(Metadata metadata, List<Map<String, Object>> identities,
                            String head, String config) {}

    private Phase phase = Phase.CLOSED;
    private Snapshot registered;
    private boolean terminated;

    Phase phase() { return phase; }
    Metadata metadata() { return registered == null ? null : registered.metadata(); }

    /** Explicit close is terminal, including after a rejected transition. */
    void close() { phase = Phase.CLOSED; terminated = true; }

    Metadata inspect(Path declared) throws IOException {
        try {
            if (terminated || (phase != Phase.CLOSED && phase != Phase.METADATA_ONLY))
                throw new IOException("TARGET_METADATA_PHASE");
            if (registered != null && (declared == null
                    || !registered.metadata().declaredRoot().equals(declared.toString())))
                throw new IOException("TARGET_METADATA_CHANGED");
            Snapshot observed = observe(declared);
            if (registered != null && !registered.equals(observed))
                throw new IOException("TARGET_METADATA_CHANGED");
            registered = observed;
            phase = Phase.METADATA_ONLY;
            return observed.metadata();
        } catch (IOException | RuntimeException failure) {
            close();
            // Filesystem exceptions can carry sensitive paths; retain only safe codes.
            throw new IOException("TARGET_METADATA_REJECTED");
        }
    }

    void verify() throws IOException {
        if (registered == null || phase != Phase.METADATA_ONLY || terminated) {
            close();
            throw new IOException("TARGET_METADATA_UNAVAILABLE");
        }
        inspect(Path.of(registered.metadata().declaredRoot()));
    }

    /** Acceptance and read/mutation/validation capabilities are intentionally absent. */
    void request(Phase requested) throws IOException {
        if (requested == Phase.METADATA_ONLY && phase == Phase.METADATA_ONLY && !terminated) {
            verify();
            return;
        }
        close();
        throw new IOException("TARGET_ACCESS_DENIED");
    }

    private static Snapshot observe(Path declared) throws IOException {
        if (declared == null || !declared.isAbsolute() || declared.getParent() == null
                || !declared.equals(declared.normalize()) || declared.getNameCount() > 128
                || declared.toString().length() > 8192
                || !Normalizer.isNormalized(declared.toString(), Normalizer.Form.NFC)
                || declared.toString().codePoints().anyMatch(Character::isISOControl))
            throw new IOException("TARGET_ROOT_INVALID");
        Json.identifier(declared.toString());
        if (!declared.getFileSystem().supportedFileAttributeViews().contains("unix"))
            throw new IOException("TARGET_IDENTITY_UNSUPPORTED");

        List<Map<String, Object>> identities = new ArrayList<>();
        Path component = declared.getRoot();
        identities.add(directory(component));
        for (Path part : declared) {
            component = component.resolve(part);
            identities.add(directory(component));
        }
        Path resolved = declared.toRealPath();
        if (!resolved.equals(declared)) throw new IOException("TARGET_ROOT_ALIAS");
        Map<String, Object> rootBefore = directory(resolved);

        // Never inherit Git discovery from cwd, environment, ancestors, or gitfiles.
        for (Path ancestor = resolved.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (exists(ancestor.resolve(".git")) || bareShape(ancestor))
                throw new IOException("ENCLOSING_GIT_UNSUPPORTED");
        }
        Path git = resolved.resolve(".git");
        boolean isGit = exists(git);
        String head = null;
        String config = null;
        if (isGit) {
            identities.add(directory(git));
            for (String name : List.of("commondir", "gitdir", "worktrees")) {
                if (exists(git.resolve(name))) throw new IOException("GIT_INDIRECTION_UNSUPPORTED");
            }
            identities.add(directory(git.resolve("objects")));
            identities.add(directory(git.resolve("refs")));
            head = readMetadata(git.resolve("HEAD"), 1024, identities);
            config = readMetadata(git.resolve("config"), 16384, identities);
            validateHead(head);
            validateConfig(config);
        } else if (bareShape(resolved)) {
            throw new IOException("BARE_GIT_UNSUPPORTED");
        }
        if (!directory(resolved).equals(rootBefore) || !resolved.toRealPath().equals(resolved))
            throw new IOException("TARGET_ROOT_CHANGED");
        // Recheck every no-follow component after all bounded metadata reads.
        component = declared.getRoot();
        int index = 0;
        if (!directory(component).equals(identities.get(index++)))
            throw new IOException("TARGET_ANCESTOR_CHANGED");
        for (Path part : declared) {
            component = component.resolve(part);
            if (!directory(component).equals(identities.get(index++)))
                throw new IOException("TARGET_ANCESTOR_CHANGED");
        }
        if (isGit) {
            for (Path gitDirectory : List.of(git, git.resolve("objects"), git.resolve("refs"))) {
                if (!directory(gitDirectory).equals(identities.get(index++)))
                    throw new IOException("GIT_DIRECTORY_CHANGED");
            }
        }
        return new Snapshot(new Metadata(declared.toString(), resolved.toString(),
                isGit ? "GIT" : "NON_GIT", (String) rootBefore.get("filesystemIdentity"),
                isGit ? resolved.toString() : null), List.copyOf(identities), head, config);
    }

    private static boolean exists(Path path) throws IOException {
        try {
            Files.readAttributes(path, "basic:isDirectory", LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException absent) {
            return false;
        }
    }

    private static boolean bareShape(Path root) throws IOException {
        return exists(root.resolve("HEAD")) && exists(root.resolve("config"))
                && exists(root.resolve("objects")) && exists(root.resolve("refs"));
    }

    private static Map<String, Object> directory(Path path) throws IOException {
        Map<String, Object> attrs = Files.readAttributes(path, "unix:dev,ino,mode", LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || (((Number) attrs.get("mode")).intValue() & 0170000) != 0040000)
            throw new IOException("TARGET_DIRECTORY_UNSAFE");
        return identity(attrs);
    }

    private static Map<String, Object> identity(Map<String, Object> attrs) throws IOException {
        if (!(attrs.get("dev") instanceof Number device) || !(attrs.get("ino") instanceof Number inode))
            throw new IOException("TARGET_IDENTITY_UNAVAILABLE");
        return Json.object("filesystemIdentity", "POSIX:" + Long.toUnsignedString(device.longValue())
                + ":" + Long.toUnsignedString(inode.longValue()), "mode", attrs.get("mode"));
    }

    private static String readMetadata(Path path, int limit, List<Map<String, Object>> identities)
            throws IOException {
        Map<String, Object> before = Files.readAttributes(path, "unix:dev,ino,mode,nlink,size",
                LinkOption.NOFOLLOW_LINKS);
        if ((((Number) before.get("mode")).intValue() & 0170000) != 0100000
                || ((Number) before.get("nlink")).longValue() != 1
                || ((Number) before.get("size")).longValue() > limit)
            throw new IOException("GIT_METADATA_UNSAFE");
        byte[] bytes;
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(limit + 1);
        }
        if (bytes.length == 0 || bytes.length > limit
                || !before.equals(Files.readAttributes(path, "unix:dev,ino,mode,nlink,size",
                        LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("GIT_METADATA_CHANGED");
        identities.add(identity(before));
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void validateHead(String head) throws IOException {
        String value = head.endsWith("\n") ? head.substring(0, head.length() - 1) : head;
        if (value.matches("[0-9a-f]{40}")) return;
        if (!value.startsWith("ref: refs/")) throw new IOException("GIT_HEAD_UNSUPPORTED");
        String ref = value.substring(5);
        if (!ref.matches("refs/[A-Za-z0-9_/-]+(?:\\.[A-Za-z0-9_-]+)*") || ref.endsWith("/")
                || ref.contains("//") || ref.contains("..") || ref.contains("@{")
                || ref.endsWith(".lock"))
            throw new IOException("GIT_HEAD_UNSUPPORTED");
        for (String part : ref.split("/")) {
            if (part.startsWith(".") || part.endsWith(".") || part.endsWith(".lock"))
                throw new IOException("GIT_HEAD_UNSUPPORTED");
        }
    }

    private static void validateConfig(String config) throws IOException {
        Set<String> seen = new HashSet<>();
        Set<String> booleanKeys = Set.of("filemode", "bare", "logallrefupdates", "ignorecase",
                "precomposeunicode", "symlinks", "protecthfs", "protectntfs");
        boolean inCore = false;
        for (String raw : config.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[")) {
                if (inCore || !line.equalsIgnoreCase("[core]"))
                    throw new IOException("GIT_CONFIG_UNSUPPORTED");
                inCore = true;
                continue;
            }
            int equals = line.indexOf('=');
            if (!inCore || equals <= 0) throw new IOException("GIT_CONFIG_UNSUPPORTED");
            String key = line.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(equals + 1).strip().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) throw new IOException("GIT_CONFIG_DUPLICATE");
            if (key.equals("repositoryformatversion")) {
                if (!value.equals("0")) throw new IOException("GIT_FORMAT_UNSUPPORTED");
            } else if (!booleanKeys.contains(key) || !(value.equals("true") || value.equals("false"))) {
                throw new IOException("GIT_CONFIG_UNSUPPORTED");
            }
            if (key.equals("bare") && !value.equals("false"))
                throw new IOException("BARE_GIT_UNSUPPORTED");
        }
        if (!seen.contains("repositoryformatversion") || !seen.contains("bare"))
            throw new IOException("GIT_CONFIG_INCOMPLETE");
    }
}
