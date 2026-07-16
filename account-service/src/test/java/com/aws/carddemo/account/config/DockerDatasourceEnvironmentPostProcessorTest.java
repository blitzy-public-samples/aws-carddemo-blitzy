/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link DockerDatasourceEnvironmentPostProcessor}: the fail-fast preflight that,
 * under the {@code docker} profile, requires the datasource environment variables and reports any
 * that are missing by NAME ONLY (never by value).
 *
 * <p>A "missing" variable is modeled by <em>omitting</em> it from the {@link MockEnvironment} (a
 * {@code null} value cannot be stored), which faithfully mirrors an unset container environment
 * variable.</p>
 */
class DockerDatasourceEnvironmentPostProcessorTest {

    private static final String URL = "SPRING_DATASOURCE_URL";
    private static final String USERNAME = "SPRING_DATASOURCE_USERNAME";
    private static final String PASSWORD = "SPRING_DATASOURCE_PASSWORD";

    private static final String URL_VALUE = "jdbc:postgresql://db.internal:5432/carddemo";
    private static final String USERNAME_VALUE = "carddemo_app_user";
    private static final String PASSWORD_VALUE = "sup3r-s3cret-passw0rd";

    private final DockerDatasourceEnvironmentPostProcessor processor =
            new DockerDatasourceEnvironmentPostProcessor();
    private final SpringApplication application = new SpringApplication();

    /**
     * Builds a {@code docker}-profile environment populated with all three datasource variables
     * except the one named by {@code omit} (pass {@code null} to include all three).
     */
    private MockEnvironment dockerEnvOmitting(final String omit) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(DockerDatasourceEnvironmentPostProcessor.DOCKER_PROFILE);
        if (!URL.equals(omit)) {
            env.setProperty(URL, URL_VALUE);
        }
        if (!USERNAME.equals(omit)) {
            env.setProperty(USERNAME, USERNAME_VALUE);
        }
        if (!PASSWORD.equals(omit)) {
            env.setProperty(PASSWORD, PASSWORD_VALUE);
        }
        return env;
    }

    private void run(final MockEnvironment env) {
        processor.postProcessEnvironment(env, application);
    }

    @Test
    void allVariablesPresentUnderDockerProfile_passes() {
        assertThatCode(() -> run(dockerEnvOmitting(null))).doesNotThrowAnyException();
    }

    @Test
    void missingUrl_throwsNamingOnlyUrl() {
        assertThatThrownBy(() -> run(dockerEnvOmitting(URL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(URL)
                .hasMessageNotContaining(USERNAME)
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void missingUsername_throwsNamingOnlyUsername() {
        assertThatThrownBy(() -> run(dockerEnvOmitting(USERNAME)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(USERNAME)
                .hasMessageNotContaining(URL)
                .hasMessageNotContaining(PASSWORD);
    }

    @Test
    void missingPassword_throwsNamingOnlyPassword() {
        assertThatThrownBy(() -> run(dockerEnvOmitting(PASSWORD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PASSWORD)
                .hasMessageNotContaining(URL)
                .hasMessageNotContaining(USERNAME);
    }

    @Test
    void blankValue_isTreatedAsMissing() {
        MockEnvironment env = dockerEnvOmitting(URL);
        env.setProperty(URL, "   ");
        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(URL);
    }

    @Test
    void allMissing_namesAllThree() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(DockerDatasourceEnvironmentPostProcessor.DOCKER_PROFILE);
        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(URL)
                .hasMessageContaining(USERNAME)
                .hasMessageContaining(PASSWORD);
    }

    @Test
    void diagnostic_neverLeaksAnyValue() {
        // Omit only the URL; username/password are present with secret values that must NOT surface.
        assertThatThrownBy(() -> run(dockerEnvOmitting(URL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(USERNAME_VALUE)
                .hasMessageNotContaining(PASSWORD_VALUE)
                .hasMessageNotContaining(URL_VALUE);
    }

    @Test
    void nonDockerProfile_withAllMissing_isNoOp() {
        // Default profile (no "docker"): even with every variable absent, the processor does nothing.
        assertThatCode(() -> run(new MockEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void dockerProfileActivatedViaProperty_isEnforced() {
        // Activation via the raw spring.profiles.active property (mirrors SPRING_PROFILES_ACTIVE).
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.profiles.active", "docker");
        assertThatThrownBy(() -> run(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(URL);
    }
}
