package com.dashaun.cfdockercpi.tooling;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class ToolDownloader {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public void install(ToolSpec spec, Path destination) throws IOException, InterruptedException {
        Files.createDirectories(destination.getParent());
        if (spec.tarball()) {
            installTarball(spec, destination);
        } else {
            installRawBinary(spec, destination);
        }
        chmodExecutable(destination);
    }

    private void installRawBinary(ToolSpec spec, Path destination) throws IOException, InterruptedException {
        Path tmp = Files.createTempFile(spec.name() + "-download-", ".part");
        try {
            downloadAndVerify(spec.url(), spec.sha256(), tmp);
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void installTarball(ToolSpec spec, Path destination) throws IOException, InterruptedException {
        Path tgz = Files.createTempFile(spec.name() + "-download-", ".tgz");
        Path extractDir = Files.createTempDirectory(spec.name() + "-extract-");
        try {
            downloadAndVerify(spec.url(), spec.sha256(), tgz);
            extractTarball(tgz, extractDir);
            Path entry = findEntry(extractDir, spec.entryInTarball())
                    .orElseThrow(() -> new IOException(
                            "Entry " + spec.entryInTarball() + " not found in " + spec.url()));
            Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tgz);
            deleteRecursively(extractDir);
        }
    }

    private void downloadAndVerify(URI url, String expectedSha256, Path target) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(url).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            try (InputStream body = resp.body()) {
                body.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("Download failed (" + resp.statusCode() + "): " + url);
        }
        MessageDigest digest = newSha256();
        try (InputStream in = new DigestInputStream(resp.body(), digest);
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA-256 mismatch for " + url
                    + "\n  expected: " + expectedSha256
                    + "\n  actual:   " + actual);
        }
    }

    private void extractTarball(Path tgz, Path destDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tgz.toString(), "-C", destDir.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("tar extraction failed (exit " + exit + "): " + new String(out));
        }
    }

    private java.util.Optional<Path> findEntry(Path root, String fileName) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst();
        }
    }

    private void chmodExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX filesystem
        }
    }

    private void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {
        }
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
