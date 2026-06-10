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

package com.zimingd.ai.ragent.infra.http;

import com.zimingd.ai.ragent.infra.config.AIModelProperties;
import com.zimingd.ai.ragent.infra.enums.ModelCapability;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 模型请求 URL 解析器。
 * 把 provider 基础地址、能力端点和候选模型覆盖配置合成为最终请求地址。
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class ModelUrlResolver {

    /**
     * 解析最终请求 URL。
     * 优先级：
     * 1. candidate.url
     * 2. provider.url + provider.endpoints[capability]
     */
    public static String resolveUrl(
            AIModelProperties.ProviderConfig provider,
            AIModelProperties.ModelCandidate candidate,
            ModelCapability capability) {
        if (candidate != null && candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl();
        }
        if (provider == null || provider.getUrl() == null || provider.getUrl().isBlank()) {
            throw new IllegalStateException("Provider baseUrl is missing");
        }

        Map<String, String> endpoints = provider.getEndpoints();
        String key = capability.name().toLowerCase();
        String path = endpoints == null ? null : endpoints.get(key);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Provider endpoint is missing: " + key);
        }

        return joinUrl(provider.getUrl(), path);
    }

    /**
     * 用一个斜杠规范拼接基础地址和路径。
     */
    private static String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }
}
