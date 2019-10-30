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
package com.alibaba.csp.sentinel.cluster.server;

import com.alibaba.csp.sentinel.cluster.TokenService;

/**
 * 嵌入模式下的Token Server接口
 * 嵌入模式是指内置的Token Server和服务在同一个进程启动
 * Token Server和client可以随时进行身份的转换
 * 缺点：隔离性不佳，可能会影响应用本身
 * @since 1.4.0
 */
public interface EmbeddedClusterTokenServer extends ClusterTokenServer, TokenService {
}
