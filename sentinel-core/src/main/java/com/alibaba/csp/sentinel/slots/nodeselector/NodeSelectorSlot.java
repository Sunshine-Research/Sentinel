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
package com.alibaba.csp.sentinel.slots.nodeselector;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 此类用于尝试构建调用路径
 * 如果有需要，添加一个新的{@link DefaultNode}到上下文的尾部
 * 上下文的最后一个节点是当前正在处理的节点，或者是上下文的parent节点
 * ContextUtil.enter("entrance1", "appA");
 * Entry nodeA = SphU.entry("nodeA");
 * if (nodeA != null) {
 *     nodeA.exit();
 * }
 * ContextUtil.exit();
 * 创建entrance1的上下文，指定调用发起者为appA
 * 接着通过SphU申请token
 * 如果没有抛出BlockException，则证明获取token成功
 *
 *              machine-root
 *                  /
 *                 /
 *           EntranceNode1
 *               /
 *              /
 *        DefaultNode(nodeA)- - - - - -> ClusterNode(nodeA);
 * 一个资源ID可以有多个不同入口的DefaultNode
 * 下面的代码会展示同一个resource id在两种不同的上下文中
 *    ContextUtil.enter("entrance1", "appA");
 *    Entry nodeA = SphU.entry("nodeA");
 *    if (nodeA != null) {
 *        nodeA.exit();
 *    }
 *    ContextUtil.exit();
 *
 *    ContextUtil.enter("entrance2", "appA");
 *    nodeA = SphU.entry("nodeA");
 *    if (nodeA != null) {
 *        nodeA.exit();
 *    }
 *    ContextUtil.exit();
 * 上述代码会在内存中生成如下调用结构
 *
 *                  machine-root
 *                  /         \
 *                 /           \
 *         EntranceNode1   EntranceNode2
 *               /               \
 *              /                 \
 *      DefaultNode(nodeA)   DefaultNode(nodeA)
 *             |                    |
 *             +- - - - - - - - - - +- - - - - - -> ClusterNode(nodeA);
 * 在两个上下文中创建了两个{@link DefaultNode}，但是只会创建一个{@link ClusterNode}
 * 我们可以使用{@code curl http://localhost:8719/tree?type=root}来查看节点结构
 */
public class NodeSelectorSlot extends AbstractLinkedProcessorSlot<Object> {

    /**
	 * 在不同上下文中，相同resource的{@link DefaultNode}集合
     */
    private volatile Map<String, DefaultNode> map = new HashMap<String, DefaultNode>(10);

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, Object obj, int count, boolean prioritized, Object... args)
        throws Throwable {
        /*
		 * 我们将使用上下文名称来代替资源ID作为map的key
		 * 因为同一个resource会全局共享相同的{@link ProcessorSlotChain}，无论在什么上下文中，
		 * 所以如果代码走进{@link #entry(Context, ResourceWrapper, DefaultNode, int, Object...)}方法时，资源ID必须一样，但是上下文可能不一样
		 * 如果我们在不同的上下文中使用{@link com.alibaba.csp.sentinel.SphU#entry(String resource)}来申请相同资源ID的token
		 * 使用上下文名称来作为映射的key可以区分相同的resource id，对于每个不同的上下文（不同的上下文名称）
		 * 思考另一个问题，一个resource可能会有多个{@link DefaultNode}，所以那种方式是最快获取相同resource的数据统计汇总结果呢？
		 * 所有的具有相同resource的{@link DefaultNode}会共享同一个{@link ClusterNode}，所以{@link ClusterBuilderSlot}会告诉你的答案
         */
		// 获取当前上下文的DefaultNode
        DefaultNode node = map.get(context.getName());
        if (node == null) {
			// 如果不存在当前上下文的数据统计节点，此时就需要为当前上下文创建一个数据统计节点
            synchronized (this) {
                node = map.get(context.getName());
                if (node == null) {
                    node = new DefaultNode(resourceWrapper, null);
                    HashMap<String, DefaultNode> cacheMap = new HashMap<String, DefaultNode>(map.size());
                    cacheMap.putAll(map);
                    cacheMap.put(context.getName(), node);
                    map = cacheMap;
                }
				// 构建调用树
                ((DefaultNode)context.getLastNode()).addChild(node);
            }
        }
		// 设置处理节点为当前节点
        context.setCurNode(node);
        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }
}
