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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresKeywordSearchChannelTest {

    @Test
    void extractsProductVersionAndErrorIdentifiers() {
        List<String> tokens = PostgresKeywordSearchChannel.extractIdentifierTokens(
                "Check AB-1234 on v2.3.1, error ERR_001. and plain words"
        );

        assertEquals(List.of("ab-1234", "v2.3.1", "err_001"), tokens);
    }

    @Test
    void ignoresWordsWithoutDigits() {
        assertEquals(List.of(), PostgresKeywordSearchChannel.extractIdentifierTokens(
                "printer cartridge replacement"
        ));
    }
}
