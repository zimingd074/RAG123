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

/**
 * 模型客户端错误分类。
 * 这层分类用于统一日志、路由降级和后续重试策略，不追求覆盖所有细节。
 */
public enum ModelClientErrorType {

    /**
     * 鉴权失败，例如 API Key 无效或无权限。
     */
    UNAUTHORIZED,

    /**
     * 请求频率超限。
     */
    RATE_LIMITED,

    /**
     * 上游服务端 5xx 错误。
     */
    SERVER_ERROR,

    /**
     * 调用参数、请求格式等客户端侧错误。
     */
    CLIENT_ERROR,

    /**
     * 网络连接、超时、I/O 等传输失败。
     */
    NETWORK_ERROR,

    /**
     * 上游返回成功，但响应体结构不符合预期。
     */
    INVALID_RESPONSE,

    /**
     * 供应商业务层报错，但不一定能直接映射到标准 HTTP 状态。
     */
    PROVIDER_ERROR;

    /**
     * 根据 HTTP 状态码做粗粒度映射。
     */
    public static ModelClientErrorType fromHttpStatus(int status) {
        if (status == 401 || status == 403) {
            return UNAUTHORIZED;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        return CLIENT_ERROR;
    }
}
