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

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.csp.sentinel.util.function.Function;

import java.util.Collection;

/**
 * 流控规则校验器
 */
public class FlowRuleChecker {

    public void checkFlow(Function<String, Collection<FlowRule>> ruleProvider, ResourceWrapper resource,
                          Context context, DefaultNode node, int count, boolean prioritized) throws BlockException {
        if (ruleProvider == null || resource == null) {
            return;
        }
		// 获取当前resource的所有流控规则集合
        Collection<FlowRule> rules = ruleProvider.apply(resource.getName());
        if (rules != null) {
			// 遍历并校验
            for (FlowRule rule : rules) {
                if (!canPassCheck(rule, context, node, count, prioritized)) {
                    throw new FlowException(rule.getLimitApp(), rule);
                }
            }
        }
    }

    public boolean canPassCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node,
                                                    int acquireCount) {
        return canPassCheck(rule, context, node, acquireCount, false);
    }

	/**
	 * 进行集群流控校验
	 * @param rule         流控规则
	 * @param context      上下文
	 * @param node         默认节点
	 * @param acquireCount 获取资源数量
	 * @param prioritized  是否开启优先级策略
	 * @return 是否被规则拦住
	 */
	private static boolean passClusterCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
											boolean prioritized) {
		try {
			TokenService clusterService = pickClusterService();
			// 如果没有集群服务，降级到校验本地流控校验
			if (clusterService == null) {
				return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
			}
			// 获取flowId
			long flowId = rule.getClusterConfig().getFlowId();
			// 请求服务端，进行流控校验
			TokenResult result = clusterService.requestToken(flowId, acquireCount, prioritized);
			return applyTokenResult(result, rule, context, node, acquireCount, prioritized);
		} catch (Throwable ex) {
			RecordLog.warn("[FlowRuleChecker] Request cluster token unexpected failed", ex);
		}
		// 降级到本地流控校验，如果规则的服务端或者客户端不可用
		// 如果降级服务也不可用，直接判定为通过
		return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
    }

    private static boolean passLocalCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                          boolean prioritized) {
        Node selectedNode = selectNodeByRequesterAndStrategy(rule, context, node);
        if (selectedNode == null) {
            return true;
        }

        return rule.getRater().canPass(selectedNode, acquireCount, prioritized);
    }

	/**
	 * 选座关联的数据统计节点
	 * @param rule    指定流控规则
	 * @param context 请求上下文
	 * @param node    上下文关联的节点
	 * @return 提供数据的数据统计节点
	 */
	static Node selectReferenceNode(FlowRule rule, Context context, DefaultNode node) {
		// 获取当前resource关联的resource
		String refResource = rule.getRefResource();
		// 获取当前resource规则的流控策略
		int strategy = rule.getStrategy();
		// 如果没有关联resource，视为没有数据统计节点
		if (StringUtil.isEmpty(refResource)) {
			return null;
		}
		// 如果流控策略是关联流控策略，则使用关联resource的ClusterNode作为数据统计节点
		if (strategy == RuleConstant.STRATEGY_RELATE) {
			return ClusterBuilderSlot.getClusterNode(refResource);
		}
		// 如果流控策略是链式流控策略
		if (strategy == RuleConstant.STRATEGY_CHAIN) {
			// 如果链式调用并不在同一个Context中，则无法获取数据统计节点
			if (!refResource.equals(context.getName())) {
				return null;
			}
			// 否则使用当前Context使用的数据统计节点
			return node;
		}
		// 没有找到合法的节点，返回null
		return null;
	}

	/**
	 * 过滤origin名称，排除"default"或者"other"的origin名称
	 * 也就是请求必须制定明确的origin名称
	 * @param origin 指定的origin名称
	 * @return 是否还有自定义的origin名称
	 */
	private static boolean filterOrigin(String origin) {
		// origin不可以为"default"或者"other"
		return !RuleConstant.LIMIT_APP_DEFAULT.equals(origin) && !RuleConstant.LIMIT_APP_OTHER.equals(origin);
	}

	/**
	 * 根据请求和策略选择数据统计节点
	 * @param rule    指定流控规则
	 * @param context 请求上下文
	 * @param node    上下文关联的节点
	 * @return 提供数据的数据统计节点
	 */
	static Node selectNodeByRequesterAndStrategy(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node) {
		// 必须参数校验
		String limitApp = rule.getLimitApp();
		int strategy = rule.getStrategy();
		String origin = context.getOrigin();
		// 如果origin名称指定了除"default"或"other"之外的origin名称
		if (limitApp.equals(origin) && filterOrigin(origin)) {
			// 并且策略是直接流控控制
			if (strategy == RuleConstant.STRATEGY_DIRECT) {
				// 直接返回当前上下文正在处理的数据统计节点
				return context.getOriginNode();
			}
			// 否则，使用关联的数据统计节点
			return selectReferenceNode(rule, context, node);
		} else if (RuleConstant.LIMIT_APP_DEFAULT.equals(limitApp)) {
			// 如果origin名称范围是"default"
			if (strategy == RuleConstant.STRATEGY_DIRECT) {
				// 并且策略是直接流控控制，直接返回ClusterNode作为数据统计节点
				return node.getClusterNode();
			}
			// 否则，使用关联的数据统计节点
			return selectReferenceNode(rule, context, node);
		} else if (RuleConstant.LIMIT_APP_OTHER.equals(limitApp)
				&& FlowRuleManager.isOtherOrigin(origin, rule.getResource())) {
			// 如果策略是其他origin的流控策略，并且
			// 并且策略是直接流控控制，直接返回当前上下文正在处理的数据统计节点
			if (strategy == RuleConstant.STRATEGY_DIRECT) {
				return context.getOriginNode();
			}
			// 否则，使用关联的数据统计节点
			return selectReferenceNode(rule, context, node);
		}
		// 策略校验没有通过，没有获取到数据统计节点
		return null;
	}

	/**
	 * 降级为本地流控规则校验
	 * @param rule         流控规则
	 * @param context      上下文
	 * @param node         默认节点
	 * @param acquireCount 需要获取的资源
	 * @param prioritized  是否开启优先级策略
	 * @return 是否通过规则
	 */
    private static boolean fallbackToLocalOrPass(FlowRule rule, Context context, DefaultNode node, int acquireCount,
												 boolean prioritized) {
		// 集群开启了如果请求失败，就降级到本地流控规则校验策略
        if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
			// 本地校验流控规则
            return passLocalCheck(rule, context, node, acquireCount, prioritized);
		} else {
			// 至此，流控规则不会生效，直接通过
			return true;
		}
	}

	/**
	 * @return 获取服务端服务
	 */
	private static TokenService pickClusterService() {
		if (ClusterStateManager.isClient()) {
			return TokenClientProvider.getClient();
		}
		if (ClusterStateManager.isServer()) {
			return EmbeddedClusterTokenServerProvider.getServer();
		}
		return null;
	}

	/**
	 * 解析从控制台获取的流控信息
	 * @param result       控制台返回的流控结果
	 * @param rule         流控规则
	 * @param context      上下文
	 * @param node         默认节点
	 * @param acquireCount 需要获取的count数量
	 * @param prioritized  是否开启优先级策略
	 * @return 是否通过流控规则
	 */
	private static boolean applyTokenResult(/*@NonNull*/ TokenResult result, FlowRule rule, Context context,
														 DefaultNode node,
														 int acquireCount, boolean prioritized) {
		switch (result.getStatus()) {
			case TokenResultStatus.OK:
				// 没有被拦截，正常返回
				return true;
			case TokenResultStatus.SHOULD_WAIT:
				// 请求频繁，需要等待
				try {
					Thread.sleep(result.getWaitInMs());
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				return true;
			case TokenResultStatus.NO_RULE_EXISTS:
			case TokenResultStatus.BAD_REQUEST:
			case TokenResultStatus.FAIL:
			case TokenResultStatus.TOO_MANY_REQUEST:
				// 请求繁忙，降级到本地规则校验
				return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
			case TokenResultStatus.BLOCKED:
			default:
				return false;
		}
	}

	/**
	 * 校验流控规则
	 * @param rule         需要进行校验的流控规则
	 * @param context      上下文
	 * @param node         默认节点
	 * @param acquireCount 需要获取的资源
	 * @param prioritized  是否开启优先级策略
	 * @return 是否通过流控规则
	 */
	public boolean canPassCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node, int acquireCount,
											 boolean prioritized) {
		String limitApp = rule.getLimitApp();
		if (limitApp == null) {
			return true;
		}

		// 开启集群模式，进行集群模式校验
		if (rule.isClusterMode()) {
			return passClusterCheck(rule, context, node, acquireCount, prioritized);
		}
		// 进行本地流控校验
		return passLocalCheck(rule, context, node, acquireCount, prioritized);
	}
}