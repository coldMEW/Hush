package io.coldmew.hush.helper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

// Runs a fixed program with arguments (no shell, so arguments are never interpreted) and returns its
// output. Used only as the fallback when a direct Android API call is not available.
final class Exec {
    private Exec() {}

    // Runs a shell command line (sh -c), like Shizuku's newProcess. Used by the EXEC action so the
    // existing 1.0.3 skip commands run unchanged.
    static String sh(String cmd) {
        return run("sh", "-c", cmd);
    }

    static String run(String... argv) {
        try {
            // stdout only, like Shizuku's newProcess: callers parse it, and stderr must not fill a pipe.
            java.lang.Process p = new ProcessBuilder(argv)
                    .redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null"))).start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            }
            p.waitFor();
            return out.toString("UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
