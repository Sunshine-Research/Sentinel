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
package com.alibaba.csp.sentinel.slots.block;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;

/**
 * 规则的基础接口
 */
public interface Rule {

    /**
	 * 校验当前统计指标是否符合此规则，意味着所有指标均不能超过设定的阈值
	 * @param context 当前{@link Context}
	 * @param node    当前{@link com.alibaba.csp.sentinel.node.Node}
	 * @param count   需要的token数量
	 * @param args    自定义参数
	 * @return 判断规则通过结果
     */
    boolean passCheck(Context context, DefaultNode node, int count, Object... args);

}
