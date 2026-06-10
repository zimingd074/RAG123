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

package com.zimingd.ai.ragent.infra.util;

import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

/**
 * LLM 输出清理工具类
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class LLMResponseCleaner {

    // 匹配响应开头的 Markdown 代码块起始围栏，例如 ```、```json、```json\n。
    private static final Pattern LEADING_CODE_FENCE = Pattern.compile("^```[\\w-]*\\s*\\n?");
    // 匹配响应结尾的 Markdown 代码块结束围栏，例如 \n``` 或 ```。
    private static final Pattern TRAILING_CODE_FENCE = Pattern.compile("\\n?```\\s*$");

    /**
     * 移除 Markdown 代码块围栏（例如 ```json ... ```）
     * <p>
     * 很多 LLM 在要求输出 JSON 时仍会包一层 Markdown 代码块。
     * 上层 JSON 解析前先调用该方法，可以把外层围栏剥掉，只保留真实内容。
     */
    public static String stripMarkdownCodeFence(String raw) {
        // null 直接返回 null，保持调用方对“无响应”的语义判断。
        if (raw == null) {
            return null;
        }
        // 先去掉首尾空白，避免围栏前后的换行影响正则匹配。
        String cleaned = raw.trim();
        // 去掉开头的代码块标记和可选语言名。
        cleaned = LEADING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        // 去掉结尾的代码块结束标记。
        cleaned = TRAILING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        // 再次 trim，清理移除围栏后残留的换行或空格。
        return cleaned.trim();
    }
}
