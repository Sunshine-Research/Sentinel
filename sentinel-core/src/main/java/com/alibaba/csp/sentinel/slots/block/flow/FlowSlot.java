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

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.function.Function;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 合并由之前的槽传递过来的运行时数据（NodeSelectorSlot, ClusterNodeBuilderSlot, and StatisticSlot）
 * <p>
 * FlowSlot使用前置设置规则来决定请求是否会被阻塞住
 * 如果触发了规则{@code SphU.entry(resourceName)}会抛出{@code FlowException}
 * 开发者可以通过自定义逻辑来处理{@code FlowException}
 * <p>
 * 一个resource可以拥有多个资源，FlowSlot会遍历所有的规则
 * 触发一个规则限制，则抛出{@code FlowException}
 * 每个{@link FlowRule}包含了基本元素，流控方式，流控策略，流控路径，可以自由组合来实现不同的影响
 * <p>
 * 流控方式：
 * 0：并发线程数
 * 1：QPS限流
 * 每种方式都是实时收集数据，我们可以通过curl http://localhost:8719/tree来查看实时数据统计
 * <p>
 * {@code thread} 并发线程数
 * {@code pass} 1s内通过的请求数
 * {@code blocked} 1s内阻塞的请求数
 * {@code success} 1s内Sentinel处理成功的请求数
 * {@code RT} 1s内请求的响应时间
 * {@code total} 1s内请求总数
 * {@code 1m-pass} 1min内，通过请求的总数
 * {@code 1m-block} 1min内，阻塞的请求总数
 * {@code 1m-all} 1min内总的请求数
 * {@code exception} 1s内出现的业务异常数量
 * <p>
 * 此阶段常用于保护资源免受占用，响应时间越长，线程占用就越多
 * <p>
 * 除了计数器，线程池和信号量也会实现
 * <p>
 * - 线程池：分配线程池来处理这些资源，如果线程池没有更多的空闲线程，请求就会被拒绝，避免影响其他资源
 * <p>
 * - 信号量：使用信号量来控制线程在同一的resource中的并发数量
 * <p>
 * 使用线程池的好处在于，超时时处理的很优雅，但是会带来Context切换、额外线程的开销
 * 如果通过的请求在分离线程中处理，比如，一个Servlet HTTP请求，就会耗费近似两倍的线程数量进行处理
 * <p>
 * 当QPS超过阈值时，Sentinel会采取动作来控制进入的请求，动作由{@code controlBehavior}进行配置
 * ({@code RuleConstant.CONTROL_BEHAVIOR_DEFAULT})是一个默认的处理动作，超限的请求会被立即拒绝，并抛出FlowException
 * <p>
 * 启动预热，({@code RuleConstant.CONTROL_BEHAVIOR_WARM_UP})
 * 如果系统的负载在一段时间内已经处于很低的状态，如果突然有一大批请求进入，系统可能还没有准备好处理进入的请求
 * 我们可以通过稳步增加进入的请求，是系统达到预热的状态，最终处理所有的请求
 * 可以使用{@code warmUpPeriodSec}来配置预热时间
 * <p>
 * 统一速率限制({@code RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER})
 * 这个策略严格控制了请求内部，也就是说，它允许请求以一个稳定、统一的速率通过
 * 这个策略是leaky bucket的一种实现，它用于在一个稳定的速率下处理请求，
 * 也就是漏桶算法的一种实现
 */
public class FlowSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

	private final FlowRuleChecker checker;
	/**
	 * 流控规则提供器
	 */
	private final Function<String, Collection<FlowRule>> ruleProvider = new Function<String, Collection<FlowRule>>() {
		@Override
		public Collection<FlowRule> apply(String resource) {
			// 通过流控规则管理器，获得当前的流控规则信息
			Map<String, List<FlowRule>> flowRules = FlowRuleManager.getFlowRuleMap();
			// 获取当前resource的规则信息
			return flowRules.get(resource);
		}
	};

	public FlowSlot() {
		this(new FlowRuleChecker());
	}

	/**
	 * 仅用于测试
	 * @param checker 流控规则检查器
	 * @since 1.6.1
	 */
	FlowSlot(FlowRuleChecker checker) {
		AssertUtil.notNull(checker, "flow checker should not be null");
		this.checker = checker;
	}

	@Override
	public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
					  boolean prioritized, Object... args) throws Throwable {
		checkFlow(resourceWrapper, context, node, count, prioritized);

		fireEntry(context, resourceWrapper, node, count, prioritized, args);
	}

	/**
	 * 验证规则
	 * @param resource    申请的资源
	 * @param context     entry所在的Context
	 * @param node        申请的节点
	 * @param count       申请的数量
	 * @param prioritized 是否开启优先级策略
	 * @throws BlockException 触发流控规则，抛出阻塞异常
	 */
	void checkFlow(ResourceWrapper resource, Context context, DefaultNode node, int count, boolean prioritized)
			throws BlockException {
		checker.checkFlow(ruleProvider, resource, context, node, count, prioritized);
	}

	@Override
	public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
		fireExit(context, resourceWrapper, count, args);
	}
}
