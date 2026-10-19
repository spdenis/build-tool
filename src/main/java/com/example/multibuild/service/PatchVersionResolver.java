package com.example.multibuild.service;

import com.example.multibuild.build.MavenRepositoryClient;
import com.example.multibuild.maven.PomVersionUpdater;
import com.example.multibuild.model.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

// Computes patch versions for build.mode=patch.
// A patch version sits between the last release and the next regular release:
// source branch at 1.1.1-SNAPSHOT → previous release 1.1.0 → patch base 1.1.0.N,
// where N is the first number for which 1.1.0.N has not been released yet.
@Component
public class PatchVersionResolver {

    private static final Logger log = LoggerFactory.getLogger(PatchVersionResolver.class);

    // Release versions use 3 segments (major.minor.patch); a 4th segment marks a patch build.
    private static final int PATCH_SEGMENTS = 4;
    private static final int MAX_PATCH_NUMBER = 999;

    private final PomVersionUpdater pomVersionUpdater;
    private final MavenRepositoryClient mavenClient;

    public PatchVersionResolver(PomVersionUpdater pomVersionUpdater, MavenRepositoryClient mavenClient) {
        this.pomVersionUpdater = pomVersionUpdater;
        this.mavenClient = mavenClient;
    }

    // Returns the patch base version (no qualifier) for the project in repoDir, e.g. "1.1.0.2".
    // If the pom already carries a patch version, its base is kept as is.
    public String resolve(Path repoDir) {
        Artifact root = pomVersionUpdater.getRootArtifact(repoDir);
        if (root == null) {
            throw new RuntimeException("Could not determine root artifact in " + repoDir.getFileName()
                    + " — required to compute the patch version");
        }
        String base = baseVersion(root.getVersion());
        if (isPatchVersion(base)) {
            log.info("{} already has patch version {}", repoDir.getFileName(), root.getVersion());
            return base;
        }
        if (!mavenClient.isReleasesConfigured()) {
            throw new RuntimeException("build.mode=patch requires lightspeed.maven-repo.releases-url"
                    + " to check which patch versions were already released");
        }

        String previous = previousRelease(base);
        for (int n = 1; n <= MAX_PATCH_NUMBER; n++) {
            String candidate = previous + "." + n;
            if (!mavenClient.releaseExists(new Artifact(root.getGroupId(), root.getArtifactId(), candidate))) {
                log.info("{}: {} → patch version {}", repoDir.getFileName(), root.getVersion(), candidate);
                return candidate;
            }
            log.info("{}: patch {} of {} already released", repoDir.getFileName(), candidate, root.key());
        }
        throw new RuntimeException("No free patch version found for " + root.key() + " after "
                + previous + "." + MAX_PATCH_NUMBER);
    }

    public static boolean isPatchVersion(String baseVersion) {
        return baseVersion.split("\\.").length >= PATCH_SEGMENTS;
    }

    // "1.1.1" → "1.1.0". The last segment must be > 0: for "1.2.0" the previous release
    // cannot be derived from the version alone.
    static String previousRelease(String baseVersion) {
        int dot = baseVersion.lastIndexOf('.');
        String last = baseVersion.substring(dot + 1);
        int n;
        try {
            n = Integer.parseInt(last);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Cannot derive previous release from version '" + baseVersion
                    + "': last segment is not numeric");
        }
        if (n <= 0) {
            throw new RuntimeException("Cannot derive previous release from version '" + baseVersion
                    + "': last segment is 0. Set an explicit version for this repo in repos.json.");
        }
        return baseVersion.substring(0, dot + 1) + (n - 1);
    }

    // Strips the qualifier suffix (everything from the first '-' onward).
    public static String baseVersion(String version) {
        int idx = version.indexOf('-');
        return idx >= 0 ? version.substring(0, idx) : version;
    }
}
