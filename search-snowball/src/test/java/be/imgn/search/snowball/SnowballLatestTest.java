package be.imgn.search.snowball;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The build's check for a newer Snowball release, `src/build/java/SnowballLatest.java`, run as the build runs
/// it, on listings written here so that nothing reaches the network.
class SnowballLatestTest {

    private static final String LISTING = """
            [
              {"name": "libstemmer_c-3.10.0.tar.gz"},
              {"name": "libstemmer_java-2.2.0.tar.gz"},
              {"name": "libstemmer_java-3.2.0.tar.gz"},
              {"name": "libstemmer_java-3.9.1.tar.gz"},
              {"name": "libstemmer_java.tgz"}
            ]
            """;

    @TempDir
    private Path directory;

    @Test
    void warnsAboutANewerRelease() throws Exception {
        assertThat(run("3.2.0", LISTING))
                .isEqualTo("[WARNING] Snowball 3.9.1 is out; this build uses 3.2.0. To upgrade, set snowball.version"
                        + " and snowball.sha256 in search-snowball/pom.xml.\n");
    }

    @Test
    void comparesVersionsNumerically() throws Exception {
        assertThat(run("3.9.1", LISTING.replace("3.2.0", "3.10.0"))).contains("Snowball 3.10.0 is out");
        assertThat(run("3.10.0", LISTING)).isEmpty();
        assertThat(run("10.0.0", LISTING)).isEmpty();
    }

    @Test
    void staysQuietWhenUpToDate() throws Exception {
        assertThat(run("3.9.1", LISTING)).isEmpty();
    }

    @Test
    void staysQuietOffline() throws Exception {
        assertThat(run("3.2.0", "--offline=true", write(LISTING))).isEmpty();
    }

    @Test
    void saysInOneLineWhenItCannotTell() throws Exception {
        assertThat(run("3.2.0", "[]"))
                .isEqualTo("[INFO] Snowball release check skipped: no libstemmer_java release in the listing\n");
        assertThat(run("3.2.0", "--offline=false", directory.resolve("missing.json")))
                .startsWith("[INFO] Snowball release check skipped: ")
                .hasLineCount(1);
        assertThat(run("snapshot", LISTING))
                .isEqualTo("[INFO] Snowball release check skipped: snapshot is not a version x.y.z\n");
    }

    private String run(String version, String listing) throws IOException, InterruptedException {
        return run(version, "--offline=false", write(listing));
    }

    private static String run(String version, String offline, Path listing) throws IOException, InterruptedException {
        var command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                Path.of("src", "build", "java", "SnowballLatest.java").toString(),
                version,
                offline,
                listing.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output.replace(System.lineSeparator(), "\n");
    }

    private Path write(String listing) throws IOException {
        return Files.writeString(directory.resolve("listing.json"), listing);
    }
}
