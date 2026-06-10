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

package com.zimingd.ai.ragent.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.infra.config.AIModelProperties;
import com.zimingd.ai.ragent.infra.enums.ModelCapability;
import com.zimingd.ai.ragent.infra.enums.ModelProvider;
import com.zimingd.ai.ragent.infra.http.HttpMediaTypes;
import com.zimingd.ai.ragent.infra.http.HttpResponseHelper;
import com.zimingd.ai.ragent.infra.http.ModelClientErrorType;
import com.zimingd.ai.ragent.infra.http.ModelClientException;
import com.zimingd.ai.ragent.infra.http.ModelUrlResolver;
import com.zimingd.ai.ragent.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class BaiLianRerankClient implements RerankClient {

    // 注入同步 OkHttp 客户端，用于向百炼 Rerank 接口发起阻塞式 HTTP 请求。
    @Qualifier("syncHttpClient")
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        // 返回当前客户端支持的模型供应商标识，路由层会用它匹配百炼配置。
        return ModelProvider.BAI_LIAN.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 候选片段为空时无需调用外部模型，直接返回空列表。
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 按 RetrievedChunk 的 id 去重，保留首次出现的片段顺序。
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            // HashSet.add 返回 true 表示该 id 第一次出现，此时加入去重后的列表。
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }

        // topN 非法或去重后的数量不超过 topN 时，不需要重排，直接返回原顺序结果。
        if (topN <= 0 || dedup.size() <= topN) {
            return dedup;
        }

        // 候选数量超过 topN 时，调用百炼 Rerank 模型进行相关性重排。
        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 从当前模型目标中获取百炼供应商配置，缺失时由工具方法抛出统一异常。
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());

        // 私有方法也做一次参数兜底，避免被内部其他入口误调用时继续请求外部接口。
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        // 构造百炼 Rerank 请求体的根对象。
        JsonObject reqBody = new JsonObject();
        // model 字段使用路由解析出的具体模型名称。
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, provider()));

        // input 承载用户查询和待重排文档数组。
        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        // documents 按 candidates 顺序写入文本，后续响应中的 index 会回指这个数组下标。
        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            // 百炼接口需要字符串文档，片段文本为空时用空字符串占位，保证下标不偏移。
            documentsArray.add(each.getText() == null ? "" : each.getText());
        }
        input.add("documents", documentsArray);

        // parameters 控制返回数量，并要求接口返回文档相关信息。
        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        // 将 input 和 parameters 挂到请求根对象上，形成完整 JSON 请求体。
        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        // 构造 HTTP POST 请求：解析 Rerank URL、设置 JSON Body，并携带 Bearer Token。
        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();

        JsonObject respJson;
        // 执行 HTTP 请求，try-with-resources 确保 Response 自动关闭。
        try (Response response = httpClient.newCall(request).execute()) {
            // 非 2xx 响应统一转为模型客户端异常，并记录状态码和响应体方便排查。
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            // 成功响应解析为 JsonObject，解析失败会由工具方法转换为统一异常。
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            // 网络、连接、超时等 IO 异常归类为 NETWORK_ERROR。
            throw new ModelClientException(provider() + " rerank 请求失败: " + e.getMessage(), ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 校验响应必须包含 output.results，并取出 output 节点。
        JsonObject output = requireOutput(respJson);

        // results 是百炼返回的重排结果数组，每个元素通常包含 index 和 relevance_score。
        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(provider() + " rerank results 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // reranked 保存最终重排结果，addedIds 用来避免兜底补齐时重复加入同一片段。
        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement elem : results) {
            // 忽略非对象元素，增强对异常响应格式的容错。
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();

            // 缺少 index 时无法映射回原始候选片段，跳过该结果。
            if (!item.has("index")) {
                continue;
            }
            // index 对应请求 documents 数组中的位置。
            int idx = item.get("index").getAsInt();

            // 防止模型返回越界下标导致运行时异常。
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }

            // 根据 index 找回原始 RetrievedChunk，保留原始 id 和文本。
            RetrievedChunk src = candidates.get(idx);

            // relevance_score 是模型给出的相关性分数，接口未返回时保持原片段不变。
            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }

            // 有分数时创建带新 score 的片段；无分数时直接复用原始片段。
            RetrievedChunk hit = score != null ? new RetrievedChunk(src.getId(), src.getText(), score) : src;
            reranked.add(hit);
            addedIds.add(src.getId());

            // 达到 topN 后停止遍历，避免返回超过调用方需要的数量。
            if (reranked.size() >= topN) {
                break;
            }
        }

        // 如果有效重排结果不足 topN，用原候选列表按原顺序补齐未加入的片段。
        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(c);
                }
                // 补齐到 topN 后立即停止。
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        // 返回最终的重排列表。
        return reranked;
    }

    private JsonObject requireOutput(JsonObject respJson) {
        // 百炼 Rerank 正常响应必须包含 output 节点。
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 output", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // output 内必须包含 results，否则调用方无法取得重排结果。
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        // 校验通过后返回 output 节点，交给调用方继续读取 results。
        return output;
    }
}
