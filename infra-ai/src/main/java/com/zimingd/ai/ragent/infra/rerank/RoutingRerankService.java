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

import com.zimingd.ai.ragent.framework.convention.RetrievedChunk;
import com.zimingd.ai.ragent.infra.model.ModelSelector;
import com.zimingd.ai.ragent.infra.model.ModelTarget;
import com.zimingd.ai.ragent.framework.exception.RemoteException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式重排服务实现类
 * <p>
 * 该服务通过模型路由机制动态选择合适的重排客户端，并支持失败降级策略
 * 作为主要的重排服务实现，用于对检索到的文档块进行相关性重新排序
 */
@Service
@Primary
public class RoutingRerankService implements RerankService {

    private final ModelSelector selector;
    private final Map<String, RerankClient> clientsByProvider;

    public RoutingRerankService(ModelSelector selector, List<RerankClient> clients) {
        this.selector = selector;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity()));
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        List<ModelTarget> targets = selector.selectRerankCandidates();
        if (targets.isEmpty()) {
            throw new RemoteException("No Rerank model candidates available");
        }
        ModelTarget target = targets.get(0);
        RerankClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            throw new RemoteException("Rerank provider client missing: " + target.candidate().getProvider());
        }
        return client.rerank(query, candidates, topN, target);
    }
}
