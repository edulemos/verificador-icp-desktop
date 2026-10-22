package br.com.verificador.desktop.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record AppPaths(Path data) {
    public AppPaths { data = data.toAbsolutePath().normalize(); }

    public static AppPaths defaults() {
        String override = System.getProperty("verificador.dataDir");
        String local = System.getenv("LOCALAPPDATA");
        return new AppPaths(override != null ? Path.of(override)
                : local != null ? Path.of(local, "VerificadorICPDesktop")
                : Path.of(System.getProperty("user.home"), ".verificador-icp-desktop"));
    }

    public Path cache() { return data.resolve("cache"); }
    public Path repository() { return data.resolve("repository"); }
    public Path intermediates() { return data.resolve("intermediates"); }
    public Path anchors() { return data.resolve("trust-anchors"); }
    public Path logs() { return data.resolve("logs"); }

    public void initialize() throws IOException {
        for (Path path : new Path[]{data, cache(), repository(), anchors(), intermediates(), logs()}) Files.createDirectories(path);
        System.setProperty("verificador.logDir", logs().toString());
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");
        System.setProperty("sun.net.client.defaultReadTimeout", "15000");
        System.setProperty("com.sun.security.enableAIAcaIssuers", "true");
    }
}
