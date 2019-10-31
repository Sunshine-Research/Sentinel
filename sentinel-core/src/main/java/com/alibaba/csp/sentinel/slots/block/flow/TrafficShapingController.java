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
package com.alibaba.csp.sentinel.slots.block.flow;

import com.alibaba.csp.sentinel.node.Node;

/**
 * 流量整形控制器的统一接口
 * 流量整形：限制流出某一网络的某一连接的流量与突发，使这类报文以比较均匀的速度向外发送
 * 流量整形通常使用缓冲区或者令牌桶来完成
 * 当报文的发送速度过快时，首先在缓冲区进行缓存，在令牌桶的控制下再均匀地发送这些被缓冲的报文
 * 是一种主动调整输出速率的措施
 */
public interface TrafficShapingController {

	/**
	 * 给定的resource entry是否可以以提供的获取token数量通过
	 * @param node         数据统计节点
	 * @param acquireCount 需要获取的token数量
	 * @param prioritized  请求是否开启优先级策略
	 * @return resource可以通过，返回true，发生阻塞则返回faslse
	 */
	boolean canPass(Node node, int acquireCount, boolean prioritized);

	/**
	 * 给定的resource entry是否可以以提供的获取token数量通过
	 * @param node         数据统计节点
	 * @param acquireCount 需要获取的token数量
	 * @return resource可以通过，返回true，发生阻塞则返回faslse
	 */
	boolean canPass(Node node, int acquireCount);
}
