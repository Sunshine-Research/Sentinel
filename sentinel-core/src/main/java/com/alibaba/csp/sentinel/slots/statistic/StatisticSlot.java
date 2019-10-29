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
package com.alibaba.csp.sentinel.slots.statistic;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotExitCallback;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.Collection;

/**
 * 致力于实时数据统计的slot
 * 当进此类型的slot时，我们需要分别计算以下信息
 * {@link ClusterNode}: resource id的cluster node的全部数据统计
 * Origin node: 不同的调用下cluster node的全部数据统计
 * {@link DefaultNode}: 在特定上下文，特定resource name中的数据统计
 * 所有根节点的数据统计之和
 */
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        try {
			// 继续进行责任链的后续Slot的处理
            fireEntry(context, resourceWrapper, node, count, prioritized, args);
			// 后续处理完成之后，回到这里，进行数据统计
            node.increaseThreadNum();
            node.addPassRequest(count);

            if (context.getCurEntry().getOriginNode() != null) {
				// 更新origin node的数据
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest(count);
            }

            if (resourceWrapper.getType() == EntryType.IN) {
				// 更新inbound类型请求的全局数据
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest(count);
            }

			// 处理注册entry回调任务的通过事件
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onPass(context, resourceWrapper, node, count, args);
            }
        } catch (PriorityWaitException ex) {
			// 如果抛出因为优先级问题导致的等待问题，更新节点的并发请求数据
            node.increaseThreadNum();
            if (context.getCurEntry().getOriginNode() != null) {
				// 更新origin node的数据
                context.getCurEntry().getOriginNode().increaseThreadNum();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
				// 更新inbound类型请求的全局数据
                Constants.ENTRY_NODE.increaseThreadNum();
            }
			// 处理注册entry回调任务的通过事件
			for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
				handler.onPass(context, resourceWrapper, node, count, args);
			}
        } catch (BlockException e) {
			// 阻塞异常
			// 设置当前异常
            context.getCurEntry().setError(e);

			// 更新阻塞数据统计
            node.increaseBlockQps(count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseBlockQps(count);
            }

            if (resourceWrapper.getType() == EntryType.IN) {
				// 更新inbound类型请求的全局数据
                Constants.ENTRY_NODE.increaseBlockQps(count);
            }

			// 处理注册entry回调任务的通过事件
            for (ProcessorSlotEntryCallback<DefaultNode> handler : StatisticSlotCallbackRegistry.getEntryCallbacks()) {
                handler.onBlocked(e, context, resourceWrapper, node, count, args);
            }

            throw e;
        } catch (Throwable e) {
			// 未知的错误
            context.getCurEntry().setError(e);

			// 更新异常QPS数量
            node.increaseExceptionQps(count);
			// 更新origin node的数据
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseExceptionQps(count);
            }

            if (resourceWrapper.getType() == EntryType.IN) {
				// 更新inbound类型请求的全局数据
                Constants.ENTRY_NODE.increaseExceptionQps(count);
            }
            throw e;
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
		// 获取当前需要处理的节点
        DefaultNode node = (DefaultNode)context.getCurNode();
		// 如果在正向chain中出现了异常，
        if (context.getCurEntry().getError() == null) {
			// 计算响应时间没
            long rt = TimeUtil.currentTimeMillis() - context.getCurEntry().getCreateTime();
			// 如果已经超过抛弃的阈值，将响应时间设为阈值
            if (rt > Constants.TIME_DROP_VALVE) {
                rt = Constants.TIME_DROP_VALVE;
            }

			// 记录响应时间和成功数量
            node.addRtAndSuccess(rt, count);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().addRtAndSuccess(rt, count);
            }
			// entry已经取消占用，减少线程数量
            node.decreaseThreadNum();

            if (context.getCurEntry().getOriginNode() != null) {
				// 更新origin node的数据统计
                context.getCurEntry().getOriginNode().decreaseThreadNum();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
				// 更新inbound类型请求的全局数据
                Constants.ENTRY_NODE.addRtAndSuccess(rt, count);
                Constants.ENTRY_NODE.decreaseThreadNum();
            }
        } else {
			// 错误可能会发生
        }

		// 处理注册entry回调任务的退出事件
        Collection<ProcessorSlotExitCallback> exitCallbacks = StatisticSlotCallbackRegistry.getExitCallbacks();
        for (ProcessorSlotExitCallback handler : exitCallbacks) {
            handler.onExit(context, resourceWrapper, count, args);
        }

        fireExit(context, resourceWrapper, count);
    }
}
