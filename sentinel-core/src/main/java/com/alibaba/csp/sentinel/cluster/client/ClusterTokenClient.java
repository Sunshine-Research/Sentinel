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
package com.alibaba.csp.sentinel.cluster.client;

import com.alibaba.csp.sentinel.cluster.TokenServerDescriptor;
import com.alibaba.csp.sentinel.cluster.TokenService;

/**
 * token客户端接口，用于分布式流控
 * @since 1.4.0
 */
public interface ClusterTokenClient extends TokenService {

    /**
	 * 获取当前token server的描述
	 * @return 当前已连接的token server描述，没有已连接的token server，返回null
     */
    TokenServerDescriptor currentServer();

    /**
	 * 启动token client
	 * @throws Exception 一些异常
     */
    void start() throws Exception;

    /**
	 * 停止token client
	 * @throws Exception 一些异常
     */
    void stop() throws Exception;

    /**
	 * 获取当前集群token client的状态
	 * @return 当前集群token client的状态
     */
    int getState();
}