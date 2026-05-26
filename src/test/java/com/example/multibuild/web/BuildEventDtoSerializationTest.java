package com.example.multibuild.web;

import com.example.multibuild.web.dto.BuildEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildEventDtoSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void logLine_serializes_withTypeField() throws Exception {
        BuildEventDto.LogLine logLine = new BuildEventDto.LogLine("hello world", "INFO", 1000L);

        String json = objectMapper.writeValueAsString(logLine);

        assertThat(json).contains("\"type\":\"LOG\"");
        assertThat(json).contains("\"message\":\"hello world\"");
        assertThat(json).contains("\"level\":\"INFO\"");
    }

    @Test
    void repoFinished_serializes_withTypeField() throws Exception {
        BuildEventDto.RepoFinished repoFinished = new BuildEventDto.RepoFinished("my-repo", true, null, 1500L);

        String json = objectMapper.writeValueAsString(repoFinished);

        assertThat(json).contains("\"type\":\"REPO_FINISHED\"");
        assertThat(json).contains("\"repo\":\"my-repo\"");
        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("\"durationMs\":1500");
    }

    @Test
    void buildFinished_deserializes_fromJson() throws Exception {
        String json = "{\"type\":\"BUILD_FINISHED\",\"success\":true,\"errorMessage\":null,\"totalDurationMs\":2000}";

        BuildEventDto result = objectMapper.readValue(json, BuildEventDto.class);

        assertThat(result).isInstanceOf(BuildEventDto.BuildFinished.class);
        BuildEventDto.BuildFinished finished = (BuildEventDto.BuildFinished) result;
        assertThat(finished.success()).isTrue();
        assertThat(finished.totalDurationMs()).isEqualTo(2000L);
    }

    @Test
    void unknownType_deserializes_throwsOrReturnsNull() {
        String json = "{\"type\":\"UNKNOWN_TYPE\",\"message\":\"test\"}";

        // Jackson with @JsonSubTypes either throws on unknown type discriminator or returns null.
        // Either outcome is acceptable — the important thing is it does not return a valid instance.
        try {
            BuildEventDto result = objectMapper.readValue(json, BuildEventDto.class);
            assertThat(result).isNull();
        } catch (Exception e) {
            // expected — unknown type discriminator rejected
            assertThat(e).isNotNull();
        }
    }
}
