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

package com.zimingd.ai.ragent.infra.token;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 轻量 Token 估算服务
 * <p>
 * 这里不依赖具体模型的 tokenizer，而是按字符类型做启发式估算：
 * 英文/数字/符号等 ASCII 字符约 4 个字符算 1 个 token，CJK 字符约 1 个字符算 1 个 token。
 */
@Service
public class HeuristicTokenCounterService implements TokenCounterService {

    @Override
    public Integer countTokens(String text) {
        // 空文本或全空白文本没有有效内容，直接返回 0。
        if (!StringUtils.hasText(text)) {
            return 0;
        }

        // asciiCount：英文、数字、英文标点等 ASCII 字符数量。
        int asciiCount = 0;
        // cjkCount：中文、日文、韩文以及相关符号字符数量。
        int cjkCount = 0;
        // otherCount：既不是 ASCII 也不是 CJK 的其他 Unicode 字符数量。
        int otherCount = 0;

        // 逐字符扫描文本，并按字符所属类型累加计数。
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // 空白字符不参与 token 估算，避免换行、空格过多时高估。
            if (Character.isWhitespace(ch)) {
                continue;
            }
            // ASCII 字符通常更接近英文 tokenizer 的压缩效果。
            if (ch <= 0x7F) {
                asciiCount++;
            // CJK 字符在多数模型 tokenizer 中常接近 1 字 1 token。
            } else if (isCjk(ch)) {
                cjkCount++;
            // 其他字符用更保守的 2 字符约 1 token 估算。
            } else {
                otherCount++;
            }
        }

        // 向上取整：例如 1~4 个 ASCII 字符都算 1 个 token。
        int asciiTokens = (asciiCount + 3) / 4; // 英文等按 4 字符约 1 token
        // 其他非 CJK 字符按 2 字符约 1 token，同样向上取整。
        int otherTokens = (otherCount + 1) / 2; // 其他字符按 2 字符约 1 token
        // CJK 直接按字符数计入，再加上 ASCII 和其他字符的估算 token 数。
        int total = asciiTokens + cjkCount + otherTokens;
        // 只要输入有有效字符，最少返回 1，避免上层把非空文本误判为 0 token。
        return Math.max(total, 1);
    }

    private boolean isCjk(char ch) {
        // 根据 UnicodeBlock 判断字符是否属于 CJK 相关区段。
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
