/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.context;

import com.alibaba.csp.sentinel.Constants;

/**
 * 没有上下文
 * 如果上下文总数超过了{@link Constants#MAX_CONTEXT_NAME_SIZE}限制，就会使用{@link NullContext}
 * 意味着不会进行规则检查
 */
public class NullContext extends Context {

    public NullContext() {
        super(null, "null_context_internal");
    }
}
