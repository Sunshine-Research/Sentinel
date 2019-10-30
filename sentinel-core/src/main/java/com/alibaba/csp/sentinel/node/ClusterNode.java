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
package com.alibaba.csp.sentinel.node;

import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 此类存储了resource的实时数据统计，包括响应时间，线程数量，QPS等
 * 相同的resource在不同的上下文中全局共享同一个{@link ClusterNode}，
 * 对于不同origin的特定调用（声明于{@link ContextUtil#enter(String name, String origin)}），一个{@link ClusterNode}持有一个{@link #originCountMap}，存储了不同origin的{@link StatisticNode}
 * 使用{@link #getOrCreateOriginNode(String)}来获取特定origin的{@link Node}节点
 * 需要注意的是origin通常是后端服务的应用名称
 */
public class ClusterNode extends StatisticNode {

	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * origin map持有特定资源的键值对（origin, originNode）
	 * 应用程序运行的时间越长，映射结果会变得越稳定，所以使用锁来代替并发集合，锁仅会发生于最开始的时候
	 */
	private Map<String, StatisticNode> originCountMap = new HashMap<String, StatisticNode>();

	/**
	 * 获取特定origin的Node节点，通常origin是Service的应用名称
	 * 如果没有找到origin node，就需要为此origin创建一个{@link StatisticNode}
	 * @param origin 调用名称，由{@link ContextUtil#enter(String name, String origin)}指派
	 * @return 特定origin node
	 */
	public Node getOrCreateOriginNode(String origin) {
		StatisticNode statisticNode = originCountMap.get(origin);
		if (statisticNode == null) {
			try {
				lock.lock();
				statisticNode = originCountMap.get(origin);
				if (statisticNode == null) {
					// 创建新的数据统计节点
					statisticNode = new StatisticNode();
					HashMap<String, StatisticNode> newMap = new HashMap<>(originCountMap.size() + 1);
					newMap.putAll(originCountMap);
					newMap.put(origin, statisticNode);
					originCountMap = newMap;
				}
			} finally {
				lock.unlock();
			}
		}
		return statisticNode;
	}

	public synchronized Map<String, StatisticNode> getOriginCountMap() {
		return originCountMap;
	}

	/**
	 * 只有在{@code throwable}异常而非{@link BlockException}时进行的计数
	 * @param throwable 异常类型
	 * @param count     阻塞数量
	 */
	public void trace(Throwable throwable, int count) {
		if (count <= 0) {
			return;
		}
		if (!BlockException.isBlockException(throwable)) {
			this.increaseExceptionQps(count);
		}
	}
}
