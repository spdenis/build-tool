package com.example.multibuild.build;

import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.RepoConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual integration test that triggers a real TeamCity build and waits for it to complete.
 * Excluded from the normal Maven build by the "manual" tag — only runs when called explicitly.
 *
 * Run via Maven:
 *   mvn test -Dtest=TeamCityBuildServiceManualTest \
 *     -Dteamcity.config.id=MyConfig_Snapshot
 *
 * teamcity.url, teamcity.token, and integration.branch are read from user.properties
 * (project root) unless overridden by system properties (-D flags or IDE VM options).
 */
@Tag("manual")
class TeamCityBuildServiceManualTest {

    private static final String PROP_URL       = "teamcity.url";
    private static final String PROP_TOKEN     = "teamcity.token";
    private static final String PROP_CONFIG_ID = "teamcity.config.id";
    private static final String PROP_BRANCH    = "integration.branch";

    private TeamCityBuildService service;
    private RepoConfig repoConfig;
    private Path repoPath;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = loadProperties();

        String url      = props.getProperty(PROP_URL, "").trim();
        String token    = props.getProperty(PROP_TOKEN, "").trim();
        String configId = props.getProperty(PROP_CONFIG_ID, "").trim();
        String branch   = props.getProperty(PROP_BRANCH, "").trim();

        assumeTrue(!url.isBlank(),      "teamcity.url not set — skipping");
        assumeTrue(!token.isBlank(),    "teamcity.token not set — skipping");
        assumeTrue(!configId.isBlank(), "teamcity.config.id not set — skipping");
        assumeTrue(!branch.isBlank(),   "integration.branch not set — skipping");

        TeamCityProperties teamCityProps = new TeamCityProperties();
        teamCityProps.setUrl(url);
        teamCityProps.setToken(token);
        teamCityProps.setPollIntervalMs(5_000);
        teamCityProps.setTimeoutMs(600_000);

        service = new TeamCityBuildService(teamCityProps, new ObjectMapper());
        ReflectionTestUtils.setField(service, "buildMode", BuildMode.SNAPSHOT);
        ReflectionTestUtils.setField(service, "integrationBranch", branch);

        repoConfig = new RepoConfig();
        repoConfig.setTeamcitySnapshotConfigId(configId);

        // Path is used only for logging; the config ID drives the actual build lookup.
        repoPath = Path.of(configId);
    }

    @Test
    void triggerBuild_waitsForCompletion_succeeds() {
        assertThatCode(() -> service.buildAll(
                List.of(List.of(repoPath)),
                Map.of(),
                Map.of(repoPath, repoConfig),
                Set.of(),
                Map.of(),
                (path, error) -> {}
        )).doesNotThrowAnyException();
    }

    // Loads user.properties from the project root first, then lets system properties (-D) override.
    private static Properties loadProperties() throws Exception {
        Properties result = new Properties();
        Path userPropsFile = Path.of("user.properties");
        if (userPropsFile.toFile().exists()) {
            try (FileReader reader = new FileReader(userPropsFile.toFile())) {
                result.load(reader);
            }
        }
        for (String key : List.of(PROP_URL, PROP_TOKEN, PROP_CONFIG_ID, PROP_BRANCH)) {
            String value = System.getProperty(key);
            if (value != null) {
                result.setProperty(key, value);
            }
        }
        return result;
    }
}
