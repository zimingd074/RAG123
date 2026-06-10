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

import lombok.Getter;

/**
 * 模型客户端统一异常。
 * 用来把 HTTP 状态异常、响应结构异常和底层网络异常封装成统一类型。
 */
@Getter
public class ModelClientException extends RuntimeException {

    /**
     * 归一化后的错误类型，便于上层记录和决策。
     */
    private final ModelClientErrorType errorType;

    /**
     * 如果失败来自 HTTP 响应，则记录状态码；否则可能为空。
     */
    private final Integer statusCode;

    /**
     * 包装底层异常时使用，例如 I/O 异常或 JSON 解析异常。
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }

    /**
     * 根据已知错误信息直接构造异常。
     */
    public ModelClientException(String message, ModelClientErrorType errorType, Integer statusCode) {
        super(message);
        this.errorType = errorType;
        this.statusCode = statusCode;
    }
}
