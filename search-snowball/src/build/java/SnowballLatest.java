import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Pattern;

/// Warns when the Snowball website lists a newer `libstemmer_java` release than the one the build pins.
///
/// Run by the build as `java SnowballLatest.java <pinned version> --offline=<true or false> [<listing>]`, where
/// `<listing>` is a file holding the listing, for tests; without it, the program asks GitHub for the website's
/// `dist` directory, unless Maven is offline.
/// It never fails: when the listing cannot be read, it prints one informational line and exits normally, since a
/// build must not depend on GitHub being reachable.
void main(String[] args) {
    if (args.length < 2 || args.length > 3) {
        IO.println("[INFO] Snowball release check skipped: expected <version> --offline=<boolean> [<listing>]");
        return;
    }
    var pinned = parse(args[0]);
    if (pinned == null) {
        IO.println("[INFO] Snowball release check skipped: " + args[0] + " is not a version x.y.z");
        return;
    }
    if (args[1].equals("--offline=true")) {
        return;
    }
    String listing;
    try {
        listing = args.length == 3 ? Files.readString(Path.of(args[2])) : fetch();
    } catch (Exception e) {
        IO.println("[INFO] Snowball release check skipped: " + e);
        return;
    }
    var latest = latest(listing);
    if (latest == null) {
        IO.println("[INFO] Snowball release check skipped: no libstemmer_java release in the listing");
        return;
    }
    if (compare(latest, pinned) > 0) {
        IO.println("[WARNING] Snowball " + format(latest) + " is out; this build uses " + format(pinned)
                + ". To upgrade, set snowball.version and snowball.sha256 in search-snowball/pom.xml.");
    }
}

static final Pattern RELEASE = Pattern.compile("libstemmer_java-(\\d+)\\.(\\d+)\\.(\\d+)\\.tar\\.gz");
static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
static final Duration TIMEOUT = Duration.ofSeconds(5);

String fetch() throws Exception {
    try (var client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()) {
        var request = HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/snowballstem/snowball-website/contents/dist"))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub answered HTTP " + response.statusCode());
        }
        return response.body();
    }
}

/// The highest release in the listing, or `null` if it names none.
int[] latest(String listing) {
    int[] latest = null;
    var matcher = RELEASE.matcher(listing);
    while (matcher.find()) {
        var version = toVersion(matcher.group(1), matcher.group(2), matcher.group(3));
        if (version != null && (latest == null || compare(version, latest) > 0)) {
            latest = version;
        }
    }
    return latest;
}

int[] parse(String text) {
    var matcher = VERSION.matcher(text);
    return matcher.matches() ? toVersion(matcher.group(1), matcher.group(2), matcher.group(3)) : null;
}

/// Numerically, so that 3.10.0 is after 3.9.0; `null` if a component overflows an `int`.
int[] toVersion(String major, String minor, String patch) {
    try {
        return new int[] {Integer.parseInt(major), Integer.parseInt(minor), Integer.parseInt(patch)};
    } catch (NumberFormatException e) {
        return null;
    }
}

int compare(int[] a, int[] b) {
    return Arrays.compare(a, b);
}

String format(int[] version) {
    return version[0] + "." + version[1] + "." + version[2];
}
