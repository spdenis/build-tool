package com.example.multibuild.web;

import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.BuildServiceType;
import com.example.multibuild.pipeline.BuildContext;
import com.example.multibuild.web.dto.BuildRequest;
import com.example.multibuild.web.dto.SettingsDto;
import com.example.multibuild.web.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsServiceTest {

    private SettingsService settingsService;

    @BeforeEach
    void setUp() {
        settingsService = new SettingsService();
        ReflectionTestUtils.setField(settingsService, "defaultCloneDir", "/tmp/repos");
        ReflectionTestUtils.setField(settingsService, "defaultIntegrationBranch", "feature-test");
        ReflectionTestUtils.setField(settingsService, "defaultBuildMode", BuildMode.SNAPSHOT);
        ReflectionTestUtils.setField(settingsService, "defaultBuildService", BuildServiceType.LOCAL);
        ReflectionTestUtils.setField(settingsService, "defaultBuildEnabled", false);
        ReflectionTestUtils.setField(settingsService, "defaultDryMode", false);
        ReflectionTestUtils.setField(settingsService, "defaultSkipGit", false);
        ReflectionTestUtils.setField(settingsService, "defaultSourceBranch", "main");
        ReflectionTestUtils.setField(settingsService, "defaultGithubToken", "secret-token");
        ReflectionTestUtils.setField(settingsService, "defaultGitAuthTokenInUrl", false);
        ReflectionTestUtils.setField(settingsService, "defaultTeamcityUrl", "");
        ReflectionTestUtils.setField(settingsService, "defaultTeamcityToken", "tc-secret");
        ReflectionTestUtils.setField(settingsService, "defaultTeamcityPollIntervalMs", 5000);
        ReflectionTestUtils.setField(settingsService, "defaultLightspeedSnapshotsUrl", "");
        ReflectionTestUtils.setField(settingsService, "defaultLightspeedReleasesUrl", "");
        ReflectionTestUtils.setField(settingsService, "defaultLightspeedUsername", "");
        ReflectionTestUtils.setField(settingsService, "defaultLightspeedPassword", "ls-secret");
    }

    @Test
    void load_defaultValues_tokensMasked() {
        SettingsDto dto = settingsService.load();

        assertThat(dto.githubToken()).isEqualTo("***");
        assertThat(dto.teamcityToken()).isEqualTo("***");
        assertThat(dto.lightspeedPassword()).isEqualTo("***");
    }

    @Test
    void save_thenLoad_preservesNonSecretFields() {
        SettingsDto toSave = new SettingsDto(
                "/custom/clone", "my-branch", BuildMode.RELEASE, BuildServiceType.DUMMY,
                true, true, false, "develop",
                null, false, "http://teamcity.example.com", null, null,
                null, null, null, null
        );

        settingsService.save(toSave);
        SettingsDto loaded = settingsService.load();

        assertThat(loaded.cloneDir()).isEqualTo("/custom/clone");
        assertThat(loaded.integrationBranch()).isEqualTo("my-branch");
        assertThat(loaded.buildMode()).isEqualTo(BuildMode.RELEASE);
        assertThat(loaded.buildEnabled()).isTrue();
        assertThat(loaded.dryMode()).isTrue();
        assertThat(loaded.defaultSourceBranch()).isEqualTo("develop");
        assertThat(loaded.teamcityUrl()).isEqualTo("http://teamcity.example.com");
    }

    @Test
    void save_tokenFieldIsNull_preservesExistingToken() {
        SettingsDto saveWithToken = new SettingsDto(
                null, null, null, null, false, false, false, null,
                "new-github-token", false, null, null, null,
                null, null, null, null
        );
        settingsService.save(saveWithToken);

        // Now save again with null token — existing token must be preserved
        SettingsDto saveWithoutToken = new SettingsDto(
                "/other/dir", null, null, null, false, false, false, null,
                null, false, null, null, null,
                null, null, null, null
        );
        settingsService.save(saveWithoutToken);

        // Token was saved, so load() should return "***" (masked), not blank
        SettingsDto loaded = settingsService.load();
        assertThat(loaded.githubToken()).isEqualTo("***");
    }

    @Test
    void toBuildContext_withRequestOverride_overrideWins() {
        // Settings has SNAPSHOT
        BuildRequest request = new BuildRequest(List.of(), BuildMode.RELEASE, null, null, null, null, null);

        BuildContext context = settingsService.toBuildContext(request);

        assertThat(context.buildMode()).isEqualTo(BuildMode.RELEASE);
    }

    @Test
    void toBuildContext_noRequestOverride_settingsValueUsed() {
        settingsService.save(new SettingsDto(
                null, null, BuildMode.RELEASE, null, false, false, false,
                null, null, false, null, null, null,
                null, null, null, null
        ));
        BuildRequest request = new BuildRequest(List.of(), null, null, null, null, null, null);

        BuildContext context = settingsService.toBuildContext(request);

        assertThat(context.buildMode()).isEqualTo(BuildMode.RELEASE);
    }
}
