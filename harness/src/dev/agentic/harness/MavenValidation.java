package dev.agentic.harness;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Fixed Maven 3 bootstrap policy. The installation and cache are independently trusted host data. */
final class MavenValidation {
    private static final String WORK = "target/.harness-maven";
    private final Path home, repository, boot;
    private final Map<String,Object> frozen;

    MavenValidation(Path home, Path repository) throws Exception {
        this.home = canonicalDirectory(home); this.repository = canonicalDirectory(repository);
        if (home.startsWith(repository) || repository.startsWith(home)) reject();
        canonicalDirectory(home.resolve("boot")); canonicalDirectory(home.resolve("lib"));
        try (var files = Files.list(home.resolve("boot"))) {
            var candidates = files.limit(8193).filter(p -> p.getFileName().toString().matches("plexus-classworlds-[A-Za-z0-9.-]+\\.jar")).toList();
            if (candidates.size() != 1) throw new IllegalStateException("VALIDATION_MAVEN_BOOT_UNAVAILABLE");
            boot = candidates.getFirst();
        }
        frozen = current();
    }
    Map<String,Object> identity() { return frozen; }
    void verifyRoots(Path source, Path target) throws Exception {
        canonicalDirectory(source); canonicalDirectory(target);
        if (source.startsWith(target) || target.startsWith(source)) reject();
        for (Path trusted : List.of(home, repository))
            for (Path root : List.of(source, target))
                if (trusted.startsWith(root) || root.startsWith(trusted)) reject();
    }
    void verify(RepositoryFiles.Snapshot state) throws Exception {
        if (!frozen.equals(current())) throw new IllegalStateException("VALIDATION_MAVEN_HOST_FILES_CHANGED");
        if (state.entries().stream().noneMatch(e -> "pom.xml".equals(e.get("pathKey"))))
            throw new IllegalStateException("VALIDATION_MAVEN_POM_REQUIRED");
        // Maven core reads .mvn/maven.config and extensions.xml even without the wrapper.
        // Deny the directory altogether, including nested module configuration.
        if (state.entries().stream().anyMatch(e -> Arrays.asList(((String)e.get("pathKey")).toLowerCase(Locale.ROOT).split("/")).contains(".mvn")
                || ValidationProfile.ephemeral((String)e.get("pathKey"))))
            throw new IllegalStateException("VALIDATION_MAVEN_PROJECT_OPTIONS_DENIED");
    }
    List<String> command(Path java, Path root) {
        Path work = root.resolve(WORK);
        return List.of(java.toString(), "-Xmx256m", "-XX:+UseSerialGC", "-XX:-UsePerfData", "-XX:+DisableAttachMechanism",
                "-Duser.home=" + work, "-Djava.io.tmpdir=" + work.resolve("tmp"),
                "-Dmaven.home=" + home, "-Dmaven.conf=" + work,
                "-Dmaven.multiModuleProjectDirectory=" + root,
                "-Dclassworlds.conf=" + work.resolve("m2.conf"), "-cp", boot.toString(),
                "org.codehaus.plexus.classworlds.launcher.Launcher",
                "--batch-mode", "--offline", "--no-transfer-progress", "--strict-checksums",
                "--settings", work.resolve("settings.xml").toString(),
                "--global-settings", work.resolve("settings.xml").toString(),
                "-Dmaven.repo.local=" + repository, "-Dstyle.color=never", "test");
    }
    void prepare(Path root) throws Exception {
        // precheck has just verified the entire target and absent target/** output scope.
        Files.createDirectory(root.resolve("target"));
        Path work = Files.createDirectory(root.resolve(WORK));
        Files.createDirectory(work.resolve("tmp"));
        Files.writeString(work.resolve("settings.xml"), "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"/>\n", StandardOpenOption.CREATE_NEW);
        // No repository-controlled launcher config, classpath, extensions or shell script.
        Files.writeString(work.resolve("m2.conf"), "main is org.apache.maven.cli.MavenCli from plexus.core\n"
                + "[plexus.core]\nload ${maven.home}/lib/*.jar\n", StandardOpenOption.CREATE_NEW);
    }
    private Map<String,Object> current() throws Exception {
        return Json.object("installation", tree(home, false), "offlineRepository", tree(repository, true));
    }
    private static Path canonicalDirectory(Path path) throws Exception {
        if (path.toString().contains(":") || path.toString().contains("\n") || path.toString().contains("\r")) reject();
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.toRealPath().equals(path)) reject();
        Path part = path.getRoot();
        for (Path segment : path) {
            part = part.resolve(segment);
            if (!Files.isDirectory(part, LinkOption.NOFOLLOW_LINKS)) reject();
        }
        return path;
    }
    private static Map<String,Object> tree(Path root, boolean readOnly) throws Exception {
        canonicalDirectory(root);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0; int count = 0;
        try (var walk = Files.walk(root)) {
            // Limit before sorting; a host misconfiguration cannot allocate an unlimited path list.
            var paths = walk.limit(8193).sorted().toList();
            if (paths.size() > 8192) reject();
            for (Path path : paths) {
                var attrs = Files.readAttributes(path, "unix:dev,ino,mode,nlink,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS);
                int mode = ((Number)attrs.get("mode")).intValue(), kind = mode & 0170000;
                if ((kind != 0040000 && kind != 0100000) || (readOnly && (mode & 0222) != 0)) reject();
                digest.update(Json.bytes(Json.object("path", root.relativize(path).toString(), "metadata", new TreeMap<>(attrs).toString())));
                if (kind == 0100000) {
                    long size = ((Number)attrs.get("size")).longValue();
                    if (((Number)attrs.get("nlink")).intValue() != 1 || size > 64L * 1024 * 1024
                            || (total += size) > 1024L * 1024 * 1024) reject();
                    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[8192]; long seen = 0; int n;
                        while ((n = input.read(buffer)) != -1) {
                            if ((seen += n) > size) reject();
                            digest.update(buffer, 0, n);
                        }
                        if (seen != size) reject();
                    }
                }
                if (!attrs.equals(Files.readAttributes(path, "unix:dev,ino,mode,nlink,size,lastModifiedTime", LinkOption.NOFOLLOW_LINKS))) reject();
                count++;
            }
        }
        return Json.object("sha256", HexFormat.of().formatHex(digest.digest()), "entries", count, "bytes", total);
    }
    private static void reject() { throw new IllegalStateException("VALIDATION_MAVEN_HOST_PATH_UNSAFE"); }
}
