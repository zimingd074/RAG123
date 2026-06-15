/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.infra.embedding;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractOpenAIStyleEmbeddingClientTest {

    private final TestEmbeddingClient client = new TestEmbeddingClient();

    @Test
    void queryRequestAppliesPrefixAndDimensions() {
        ModelTarget target = target(1536, true, "Instruct: retrieve\nQuery: ");

        JsonObject body = client.requestBody(List.of("退款规则"), target, true);

        assertEquals(
                "Instruct: retrieve\nQuery: 退款规则",
                body.getAsJsonArray("input").get(0).getAsString());
        assertEquals(1536, body.get("dimensions").getAsInt());
    }

    @Test
    void documentRequestKeepsOriginalText() {
        ModelTarget target = target(1536, true, "Instruct: retrieve\nQuery: ");

        JsonObject body = client.requestBody(List.of("退款规则"), target, false);

        assertEquals("退款规则", body.getAsJsonArray("input").get(0).getAsString());
    }

    @Test
    void bgeRequestCanOmitDimensions() {
        ModelTarget target = target(1024, false, null);

        JsonObject body = client.requestBody(List.of("退款规则"), target, true);

        assertFalse(body.has("dimensions"));
    }

    @Test
    void responseDimensionMustMatchConfiguration() {
        ModelTarget target = target(3, true, null);
        JsonObject response = JsonParser.parseString(
                "{\"data\":[{\"embedding\":[0.1,0.2]}]}").getAsJsonObject();

        ModelClientException error = assertThrows(
                ModelClientException.class,
                () -> client.parse(response, target));

        assertTrue(error.getMessage().contains("expected=3"));
        assertTrue(error.getMessage().contains("actual=2"));
    }

    private static ModelTarget target(
            int dimension,
            boolean sendDimensions,
            String queryPrefix) {
        AIModelProperties.ModelCandidate candidate =
                new AIModelProperties.ModelCandidate();
        candidate.setModel("test-embedding");
        candidate.setDimension(dimension);
        candidate.setSendDimensions(sendDimensions);
        candidate.setQueryPrefix(queryPrefix);
        return new ModelTarget(
                "test",
                candidate,
                new AIModelProperties.ProviderConfig());
    }

    private static final class TestEmbeddingClient
            extends AbstractOpenAIStyleEmbeddingClient {

        private TestEmbeddingClient() {
            super(new OkHttpClient());
        }

        @Override
        public String provider() {
            return "test";
        }

        private JsonObject requestBody(
                List<String> texts,
                ModelTarget target,
                boolean query) {
            return buildRequestBody(texts, target, query);
        }

        private List<List<Float>> parse(JsonObject json, ModelTarget target) {
            return parseEmbeddings(json, target);
        }
    }
}
