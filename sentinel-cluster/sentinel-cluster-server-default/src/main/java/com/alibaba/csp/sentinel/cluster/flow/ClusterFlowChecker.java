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
package com.alibaba.csp.sentinel.cluster.flow;

import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.flow.rule.ClusterFlowRuleManager;
import com.alibaba.csp.sentinel.cluster.flow.statistic.ClusterMetricStatistics;
import com.alibaba.csp.sentinel.cluster.flow.statistic.data.ClusterFlowEvent;
import com.alibaba.csp.sentinel.cluster.flow.statistic.limit.GlobalRequestLimiter;
import com.alibaba.csp.sentinel.cluster.flow.statistic.metric.ClusterMetric;
import com.alibaba.csp.sentinel.cluster.server.config.ClusterServerConfigManager;
import com.alibaba.csp.sentinel.cluster.server.log.ClusterServerStatLogUtil;
import com.alibaba.csp.sentinel.slots.block.ClusterRuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;

/**
 * 集群流控规则校验器
 * @since 1.4.0
 */
final class ClusterFlowChecker {

	/**
	 * 计算全局阈值
	 * @param rule 给定规则
	 * @return 全局阈值
	 */
	private static double calcGlobalThreshold(FlowRule rule) {
		// 获取规则设定的阈值
		double count = rule.getCount();
		// 根据集群配置阈值类型进行判断
		switch (rule.getClusterConfig().getThresholdType()) {
			case ClusterRuleConstant.FLOW_THRESHOLD_GLOBAL:
				// 如果开启的是全局阈值，返回设置的值
				return count;
			case ClusterRuleConstant.FLOW_THRESHOLD_AVG_LOCAL:
			default:
				// 其他情况下，会进入默认设定，count为单机阈值
				// 计算公式=count*集群连接数
				int connectedCount = ClusterFlowRuleManager.getConnectedCount(rule.getClusterConfig().getFlowId());
				return count * connectedCount;
		}
	}

	/**
	 * 允许通过
	 * @param flowId 集群流控规则ID
	 * @return 是否通过
	 */
	static boolean allowProceed(long flowId) {
		// 根据集群流控规则ID获取集群的命名空间
		String namespace = ClusterFlowRuleManager.getNamespace(flowId);
		// 判读当前命名空间是否可以通过全局请求限制器
		return GlobalRequestLimiter.tryPass(namespace);
	}

	/**
	 * 获取集群token
	 * @param rule         给定规则
	 * @param acquireCount 需要获取的token数量
	 * @param prioritized  是否开启限流
	 * @return 请求token结果
	 */
	static TokenResult acquireClusterToken(/*@Valid*/ FlowRule rule, int acquireCount, boolean prioritized) {
		Long id = rule.getClusterConfig().getFlowId();
		// 判断当前请求是否可以通过
		// 不通过则代表当前已经向集群发送太多请求
		if (!allowProceed(id)) {
			return new TokenResult(TokenResultStatus.TOO_MANY_REQUEST);
		}
		// 获取当前集群规则的度量标准
		ClusterMetric metric = ClusterMetricStatistics.getMetric(id);
		// 没有当前规则的度量标准，直接返回请求失败
		if (metric == null) {
			return new TokenResult(TokenResultStatus.FAIL);
		}
		// 获取最新的QPS
		double latestQps = metric.getAvg(ClusterFlowEvent.PASS_REQUEST);
		// 获取全局的请求阈值
		double globalThreshold = calcGlobalThreshold(rule) * ClusterServerConfigManager.getExceedCount();
		// 获取剩余token数量
		double nextRemaining = globalThreshold - latestQps - acquireCount;

		if (nextRemaining >= 0) {
			// TODO: checking logic and metric operation should be separated.
			// 如果存在剩余的token，更新度量标准
			metric.add(ClusterFlowEvent.PASS, acquireCount);
			metric.add(ClusterFlowEvent.PASS_REQUEST, 1);
			if (prioritized) {
				// 添加优先级策略
				metric.add(ClusterFlowEvent.OCCUPIED_PASS, acquireCount);
			}
			// 返回token请求结果
			return new TokenResult(TokenResultStatus.OK)
					.setRemaining((int) nextRemaining)
					.setWaitInMs(0);
		} else {
			// 如果超支
			if (prioritized) {
				// 在开启优先级策略的情况下，尝试占领即将补充的桶
				double occupyAvg = metric.getAvg(ClusterFlowEvent.WAITING);
				// 由于开启了优先级策略，所以可以进行等待，计算等待的时间，返回等待的结果
				if (occupyAvg <= ClusterServerConfigManager.getMaxOccupyRatio() * globalThreshold) {
					int waitInMs = metric.tryOccupyNext(ClusterFlowEvent.PASS, acquireCount, globalThreshold);
					if (waitInMs > 0) {
						ClusterServerStatLogUtil.log("flow|waiting|" + id);
						return new TokenResult(TokenResultStatus.SHOULD_WAIT)
								.setRemaining(0)
								.setWaitInMs(waitInMs);
					}
				}
			}
			// 进行阻塞
			metric.add(ClusterFlowEvent.BLOCK, acquireCount);
			metric.add(ClusterFlowEvent.BLOCK_REQUEST, 1);
			ClusterServerStatLogUtil.log("flow|block|" + id, acquireCount);
			ClusterServerStatLogUtil.log("flow|block_request|" + id, 1);
			if (prioritized) {
				// 添加优先级阻塞
				metric.add(ClusterFlowEvent.OCCUPIED_BLOCK, acquireCount);
				ClusterServerStatLogUtil.log("flow|occupied_block|" + id, 1);
			}
			// 返回阻塞的结果
			return blockedResult();
		}
	}

	/**
	 * 构建阻塞结果
	 * @return 阻塞结果
	 */
	private static TokenResult blockedResult() {
		return new TokenResult(TokenResultStatus.BLOCKED)
				.setRemaining(0)
				.setWaitInMs(0);
	}

    private ClusterFlowChecker() {}
}
