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
package com.carddemo.common.config;

import com.carddemo.common.dto.SessionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Guard the session-serializer contract asserted by
 *     ``docs/decision-log.md``: session values must be written as allowlisted JSON
 *     by a {@link GenericJacksonJsonRedisSerializer}, never as a JDK native
 *     serialization stream (CWE-502). The bean name
 *     ``springSessionDefaultRedisSerializer`` is the exact name Spring Session
 *     looks up, so a rename would silently reinstate JDK serialization.
 */
class SessionRedisConfigTest {

    /** :purpose: Bean name Spring Session resolves for the session value serializer. */
    private static final String SPRING_SESSION_SERIALIZER_BEAN = "springSessionDefaultRedisSerializer";

    @Test
    @DisplayName("The exposed session serializer is the allowlisted JSON serializer, not JDK")
    void serializerIsJsonNotJdk() {
        RedisSerializer<Object> serializer = new SessionRedisConfig().springSessionDefaultRedisSerializer();

        assertThat(serializer).isInstanceOf(GenericJacksonJsonRedisSerializer.class);
        assertThat(serializer).isNotInstanceOf(JdkSerializationRedisSerializer.class);
    }

    @Test
    @DisplayName("The serializer bean is exposed under the name Spring Session looks up")
    void serializerBeanNameIsTheOneSpringSessionResolves() throws Exception {
        assertThat(SessionRedisConfig.class.getDeclaredMethod(SPRING_SESSION_SERIALIZER_BEAN))
                .isNotNull();
    }

    @Test
    @DisplayName("A SessionContext round-trips as JSON with no JDK serialization marker")
    void sessionContextRoundTripsAsJson() {
        RedisSerializer<Object> serializer = new SessionRedisConfig().springSessionDefaultRedisSerializer();

        SessionContext context = new SessionContext();
        context.setUserId("ADMIN001");
        context.setUserType(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        context.setProgramContext(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        context.setFromProgram("COSGN00C");
        context.setFromTranid("CC00");
        context.setAcctId(90000000001L);

        byte[] payload = serializer.serialize(context);
        assertThat(payload).isNotNull();
        String wire = new String(payload, StandardCharsets.UTF_8);

        // JDK serialization streams start with 0xAC 0xED; JSON starts with '{'.
        assertThat(payload[0]).isEqualTo((byte) '{');
        assertThat(wire).contains("com.carddemo.common.dto.SessionContext");
        assertThat(wire).contains("ADMIN001");

        Object restored = serializer.deserialize(payload);
        assertThat(restored).isInstanceOf(SessionContext.class);
        SessionContext round = (SessionContext) restored;
        assertThat(round.getUserId()).isEqualTo("ADMIN001");
        assertThat(round.getUserType()).isEqualTo(SessionContext.UserType.CDEMO_USRTYP_ADMIN);
        assertThat(round.getProgramContext()).isEqualTo(SessionContext.ProgramContext.CDEMO_PGM_ENTER);
        assertThat(round.getFromProgram()).isEqualTo("COSGN00C");
        assertThat(round.getAcctId()).isEqualTo(90000000001L);
    }
}
