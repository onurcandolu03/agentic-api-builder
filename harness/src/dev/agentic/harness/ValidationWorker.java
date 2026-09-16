package dev.agentic.harness;

import java.net.URLClassLoader;
import java.nio.file.*;
import javax.tools.ToolProvider;

/** Fixed child entry point; no arguments, discovery, scripts, environment or network operations. */
public final class ValidationWorker {
    private ValidationWorker() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 0) System.exit(20);
        Path output = Files.createDirectory(Path.of("target"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) System.exit(21);
        int result = compiler.run(null, System.out, System.out, "-proc:none", "-implicit:none", "-encoding", "UTF-8",
                "-classpath", "target", "-sourcepath", "target", "-d", "target",
                "src/Value.java", "src/ValueMapper.java", "src/TargetConventions.java", "src/ReadItemContractTest.java");
        if (result != 0) System.exit(22);
        try (var loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var method = loader.loadClass("ReadItemContractTest").getDeclaredMethod("verifiesIdentity");
            method.setAccessible(true);
            try { method.invoke(null); }
            catch (ReflectiveOperationException failure) { System.exit(23); }
        }
        System.out.println("CONTROLLED_CONTRACT_TEST_PASS");
    }
}
