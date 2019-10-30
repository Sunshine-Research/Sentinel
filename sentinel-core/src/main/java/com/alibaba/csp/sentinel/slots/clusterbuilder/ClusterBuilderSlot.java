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
package com.alibaba.csp.sentinel.slots.clusterbuilder;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.IntervalProperty;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.node.SampleCountProperty;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 此slot掌控了resource的运行时数据（响应时间，QPS，线程数量，异常），以及以{@link ContextUtil#enter(String origin)}标记的调用列表
 * 一个resource仅有一个cluster node，但是可以有多个default node
 */
public class ClusterBuilderSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

	/**
	 * 需要记住相同的resource会全局共享 {@link ProcessorSlotChain}，所以无论在哪个上下文中，如果代码已经进入
	 * {@link #entry(Context, ResourceWrapper, DefaultNode, int, boolean, Object...)}，则{@link #entry(Context, ResourceWrapper, DefaultNode, int, boolean, Object...)},
	 * resource name必须是相同的，但是上下问的名称是可以随意的
	 * 应用运行的时间越长，当前的映射集合就会变的越稳定，所以我们没有用并发的映射集合，而是通过lock来保证线程安全
	 * lock上锁仅会发生于最开始的时候，并发映射将始终保持该锁定
	 */
    private static volatile Map<ResourceWrapper, ClusterNode> clusterNodeMap = new HashMap<>();

	/**
	 * 乐观锁
	 * 用于创建新的{@link ClusterNode}
	 */
    private static final Object lock = new Object();

    private volatile ClusterNode clusterNode = null;

	/**
	 * 获取特定类型resource的{@link ClusterNode}
     * @param id   resource name.
	 * @param type 调用类型
	 * @return {@link ClusterNode}
     */
    public static ClusterNode getClusterNode(String id, EntryType type) {
        return clusterNodeMap.get(new StringResourceWrapper(id, type));
	}

	@Override
	public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
		fireExit(context, resourceWrapper, count, args);
	}

	/**
	 * 获取指定resource name的{@link ClusterNode}
     * @param id resource name.
	 * @return {@link ClusterNode}.
     */
    public static ClusterNode getClusterNode(String id) {
        if (id == null) {
            return null;
        }
        ClusterNode clusterNode = null;

        for (EntryType nodeType : EntryType.values()) {
            clusterNode = clusterNodeMap.get(new StringResourceWrapper(id, nodeType));
            if (clusterNode != null) {
                break;
            }
        }

        return clusterNode;
    }

	/**
	 * 获取存储{@link ClusterNode}的映射map，map持有了所有{@link ClusterNode}
	 * key: resource name
	 * value: {@link ClusterNode}
	 * @return {@link ClusterNode}映射集合
     */
    public static Map<ResourceWrapper, ClusterNode> getClusterNodeMap() {
        return clusterNodeMap;
    }

	/**
	 * 重置所有{@link ClusterNode}
	 * 在{@link IntervalProperty#INTERVAL}或{@link SampleCountProperty#SAMPLE_COUNT}发生变化是，需要进行重置
     */
    public static void resetClusterNodes() {
        for (ClusterNode node : clusterNodeMap.values()) {
            node.reset();
		}
	}

	@Override
	public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
					  boolean prioritized, Object... args)
			throws Throwable {
		if (clusterNode == null) {
			synchronized (lock) {
				// 使用同步的方式创建cluster node
				if (clusterNode == null) {
					clusterNode = new ClusterNode();
					HashMap<ResourceWrapper, ClusterNode> newMap = new HashMap<>(Math.max(clusterNodeMap.size(), 16));
					newMap.putAll(clusterNodeMap);
					newMap.put(node.getId(), clusterNode);
					clusterNodeMap = newMap;
				}
			}
		}
		node.setClusterNode(clusterNode);

		// 如果上下文中设置了origin信息，需要为此创建一个节点
		if (!"".equals(context.getOrigin())) {
			Node originNode = node.getClusterNode().getOrCreateOriginNode(context.getOrigin());
			context.getCurEntry().setOriginNode(originNode);
		}

		fireEntry(context, resourceWrapper, node, count, prioritized, args);
	}
}
