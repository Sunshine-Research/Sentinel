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
package com.alibaba.csp.sentinel.cluster;

import java.util.Collection;

/**
 * 流控的服务接口
 * @since 1.4.0
 */
public interface TokenService {

    /**
	 * 向token server请求token数量
	 * @param ruleId 集群全局唯一ID
	 * @param acquireCount 需要获取的数量
	 * @param prioritized 请求是否开启优先级策略
	 * @return token请求结果
     */
    TokenResult requestToken(Long ruleId, int acquireCount, boolean prioritized);

    /**
	 * 向token server请求指定参数的token数量
	 * @param ruleId 集群全局唯一ID
	 * @param acquireCount 需要获取的数量
	 * @param params 请求的参数列表
	 * @return token请求结果
     */
    TokenResult requestParamToken(Long ruleId, int acquireCount, Collection<Object> params);
}
