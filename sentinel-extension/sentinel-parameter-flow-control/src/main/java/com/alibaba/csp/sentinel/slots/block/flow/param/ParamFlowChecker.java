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
package com.alibaba.csp.sentinel.slots.block.flow.param;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.statistic.cache.CacheMap;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 热点数据流控规则校验器
 * @since 0.2.0
 */
public final class ParamFlowChecker {

	private ParamFlowChecker() {
	}

	/**
	 * 热点数据流控规则校验
	 * @param resourceWrapper 指定resource
	 * @param rule            指定热点数据流控规则
	 * @param count           获取的token数量
	 * @param args            自定义参数
	 * @return
	 */
	public static boolean passCheck(ResourceWrapper resourceWrapper, /*@Valid*/ ParamFlowRule rule, /*@Valid*/ int count,
									Object... args) {
		if (args == null) {
			return true;
		}
		// 获取规则设置的参数索引位置
		int paramIdx = rule.getParamIdx();
		// 如果超出了自定义参数的范围，判定通过
		if (args.length <= paramIdx) {
			return true;
		}

		// 获取参数的值，如果没有明确的值，判定通过
		Object value = args[paramIdx];
		if (value == null) {
			return true;
		}

		// 如果是集群莫模式，并且设定的是QPS策略
		if (rule.isClusterMode() && rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
			// 进行集群规则检查
			return passClusterCheck(resourceWrapper, rule, count, value);
		}
		// 进行本地规则检查
		return passLocalCheck(resourceWrapper, rule, count, value);
	}

	/**
	 * 本地热点参数规则检查
	 * @param resourceWrapper 指定resource
	 * @param rule            指定热点数据流控规则
	 * @param count           获取的token数量
	 * @param value           进行检查的参数值
	 * @return 是否通过热点参数规则检查
	 */
	private static boolean passLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
										  Object value) {
		try {
			// 参数类型是Collection的子类
			if (Collection.class.isAssignableFrom(value.getClass())) {
				// 遍历每个值，进行规则校验
				for (Object param : ((Collection) value)) {
					if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
						return false;
					}
				}
			} else if (value.getClass().isArray()) {
				// 参数类型是数组类型
				int length = Array.getLength(value);
				// 遍历每个值，进行规则校验
				for (int i = 0; i < length; i++) {
					Object param = Array.get(value, i);
					if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
						return false;
					}
				}
			} else {
				// 参数类型是值类型
				return passSingleValueCheck(resourceWrapper, rule, count, value);
			}
		} catch (Throwable e) {
			RecordLog.warn("[ParamFlowChecker] Unexpected error", e);
		}

		return true;
	}

	/**
	 * 对单个值进行规则校验
	 * @param resourceWrapper 指定resource
	 * @param rule            指定热点数据流控规则
	 * @param acquireCount    获取的token数量
	 * @param value           进行检查的参数值
	 * @return 是否通过热点参数规则检查
	 */
	static boolean passSingleValueCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int acquireCount,
										Object value) {
		// QPS模式
		if (rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
			if (rule.getControlBehavior() == RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER) {
				// 流控效果是匀速排队模式
				return passThrottleLocalCheck(resourceWrapper, rule, acquireCount, value);
			} else {
				// 其他模式，使用默认是本地检查策略
				return passDefaultLocalCheck(resourceWrapper, rule, acquireCount, value);
			}
		} else if (rule.getGrade() == RuleConstant.FLOW_GRADE_THREAD) {
			// 线程数量模式
			Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
			// 获取给定resource，当前参数的线程计数器
			long threadCount = getParameterMetric(resourceWrapper).getThreadCount(rule.getParamIdx(), value);
			// 如果属于需要进行单独处理的参数，则从单独处理的参数中获取阈值
			if (exclusionItems.contains(value)) {
				int itemThreshold = rule.getParsedHotItems().get(value);
				// 进行比较
				return ++threadCount <= itemThreshold;
			}
			// 否则，和规则全局的数量进行比较
			long threshold = (long) rule.getCount();
			return ++threadCount <= threshold;
		}

		return true;
	}

	/**
	 * 进行默认本地规则校验
	 * @param resourceWrapper 指定resource
	 * @param rule            指定热点数据流控规则
	 * @param acquireCount    获取的token数量
	 * @param value           进行检查的参数值
	 * @return 是否通过热点参数规则检查
	 */
	static boolean passDefaultLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int acquireCount,
										 Object value) {
		// 获取resource的规则度量标准
		ParameterMetric metric = getParameterMetric(resourceWrapper);
		// 获取tokenCounter和timeRecorderCounter
		CacheMap<Object, AtomicLong> tokenCounters = metric == null ? null : metric.getRuleTokenCounter(rule);
		CacheMap<Object, AtomicLong> timeCounters = metric == null ? null : metric.getRuleTimeCounter(rule);
		// 还没有对应的计数器，判定通过
		if (tokenCounters == null || timeCounters == null) {
			return true;
		}


		// 计算token阈值
		Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
		long tokenCount = (long) rule.getCount();
		if (exclusionItems.contains(value)) {
			tokenCount = rule.getParsedHotItems().get(value);
		}

		// 如果阈值为0，判定不通过
		if (tokenCount == 0) {
			return false;
		}

		// 最大值容量为token阈值+缓存阈值
		long maxCount = tokenCount + rule.getBurstCount();
		// 如果需要量大于最大容量，判定不通过
		if (acquireCount > maxCount) {
			return false;
		}

		while (true) {
			// 获取当前时间戳
			long currentTime = TimeUtil.currentTimeMillis();
			// 获取最近一次获取token的时间戳
			AtomicLong lastAddTokenTime = timeCounters.putIfAbsent(value, new AtomicLong(currentTime));
			if (lastAddTokenTime == null) {
				// 没有获取过token，补充token并立即消费{@code acquireCount}
				tokenCounters.putIfAbsent(value, new AtomicLong(maxCount - acquireCount));
				return true;
			}

			// 计算上次成功获取token后过去的时间
			long passTime = currentTime - lastAddTokenTime.get();
			// 仅会在数据统计窗口通过后才可以进行补充token一个简单的token桶算法
			// 如果过去了至少一个窗口期，就需要重新更新值
			if (passTime > rule.getDurationInSec() * 1000) {
				// 获取旧的QPS的值，如果没有，更新旧QPS为maxCount - acquireCount
				AtomicLong oldQps = tokenCounters.putIfAbsent(value, new AtomicLong(maxCount - acquireCount));
				// 如果不存在旧的QPS，证明还没有请求，当前请求必定通过
				if (oldQps == null) {
					// 可能不是精确的
					lastAddTokenTime.set(currentTime);
					return true;
				} else {
					// 获取剩余的QPS
					long restQps = oldQps.get();
					// 需要添加的token数量，计算公式为，过去的时间/窗口时间，作为系数，*最大阈值，获取本次请求的最大容量
					// 也就是在过去的时间内可以补充多少token
					long toAddCount = (passTime * tokenCount) / (rule.getDurationInSec() * 1000);
					// 计算新的容量
					// 剩余的QPS容量+可补充的token数量
					// 如果超过了最大容量，那么新的容量上限=最大容量-需要获取的token数目
					// 否则为剩余的QPS容量+添加的数量-需要获取的token数目
					long newQps = toAddCount + restQps > maxCount ? (maxCount - acquireCount)
							: (restQps + toAddCount - acquireCount);
					// 如果新容量＜0，直接判定不通过
					if (newQps < 0) {
						return false;
					}
					// 否则更新QPS，和最新的QPS时间，并判定通过
					if (oldQps.compareAndSet(restQps, newQps)) {
						lastAddTokenTime.set(currentTime);
						return true;
					}
					Thread.yield();
				}
			} else {
				// 如果还在当前的窗口期内，不需要补充token
				AtomicLong oldQps = tokenCounters.get(value);
				if (oldQps != null) {
					// 获取前一次的QPS
					long oldQpsValue = oldQps.get();
					// 如果旧的QPS值-acquireCount仍有剩余，则占有资源，判定通过
					if (oldQpsValue - acquireCount >= 0) {
						if (oldQps.compareAndSet(oldQpsValue, oldQpsValue - acquireCount)) {
							return true;
						}
					} else {
						// 否则判定失败
						return false;
					}
				}
				Thread.yield();
			}
		}
	}

	/**
	 * 匀速通过规则检查
	 * @param resourceWrapper 指定resource
	 * @param rule            指定热点数据流控规则
	 * @param acquireCount    获取的token数量
	 * @param value           进行检查的参数值
	 * @return 是否通过热点参数规则检查
	 */
	static boolean passThrottleLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int acquireCount,
										  Object value) {
		// 获取给定的resource的参数度量标准
		ParameterMetric metric = getParameterMetric(resourceWrapper);
		// 获取给定规则的计时器集合
		CacheMap<Object, AtomicLong> timeRecorderMap = metric == null ? null : metric.getRuleTimeCounter(rule);
		if (timeRecorderMap == null) {
			return true;
		}

		// 获取最大的token数量，可能指定了特定的参数阈值
		Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
		long tokenCount = (long) rule.getCount();
		if (exclusionItems.contains(value)) {
			tokenCount = rule.getParsedHotItems().get(value);
		}

		// 没有可供分配的token，直接判定不通过
		if (tokenCount == 0) {
			return false;
		}
		// 计算请求花费的时间
		// 请求花费的时间=(1.0 * 1000 * 请求token数量 * 窗口时间)/当前可申请的token数量值 的四舍五入值
		long costTime = Math.round(1.0 * 1000 * acquireCount * rule.getDurationInSec() / tokenCount);
		while (true) {
			// 获取当前的时间戳
			long currentTime = TimeUtil.currentTimeMillis();
			// 获取给定参数的timeRecorder
			AtomicLong timeRecorder = timeRecorderMap.putIfAbsent(value, new AtomicLong(currentTime));
			if (timeRecorder == null) {
				return true;
			}
			// 根据上一次请求通过的时间戳预估下一次请求的时间戳
			long lastPassTime = timeRecorder.get();
			long expectedTime = lastPassTime + costTime;
			// 如果预期完成的时间戳在当前时间之前，或者可以当前可以进行等待
			if (expectedTime <= currentTime || expectedTime - currentTime < rule.getMaxQueueingTimeMs()) {
				// 获取最近一次通过的时间戳
				AtomicLong lastPastTimeRef = timeRecorderMap.get(value);
				// 更新时间戳
				if (lastPastTimeRef.compareAndSet(lastPassTime, currentTime)) {
					// 计算等待时间
					long waitTime = expectedTime - currentTime;
					// 如果需要等待，则更新为预估的未来的时间戳
					if (waitTime > 0) {
						lastPastTimeRef.set(expectedTime);
						try {
							// 并进行等待
							TimeUnit.MILLISECONDS.sleep(waitTime);
						} catch (InterruptedException e) {
							RecordLog.warn("passThrottleLocalCheck: wait interrupted", e);
						}
					}
					// 判定通过
					return true;
				} else {
					// 否则进行yield()
					Thread.yield();
				}
			} else {
				// 当前时间窗口没有可通过的token，判定不通过
				return false;
			}
		}
	}

	/**
	 * 获取给定resource的参数度量标准
	 * @param resourceWrapper 指定的resource
	 * @return 给定resource的参数度量标准
	 */
	private static ParameterMetric getParameterMetric(ResourceWrapper resourceWrapper) {
		// 不应该为null
		return ParameterMetricStorage.getParamMetric(resourceWrapper);
	}

	/**
	 * value->Collection
	 * @param value 进行规则校验参数值
	 * @return 以Collection的为类型的value集合
	 */
	@SuppressWarnings("unchecked")
	private static Collection<Object> toCollection(Object value) {
		// 如果value是Collection集合，直接返回
		if (value instanceof Collection) {
			return (Collection<Object>) value;
		} else if (value.getClass().isArray()) {
			// 如果value是数组类型，添加到ArrayList中
			List<Object> params = new ArrayList<Object>();
			int length = Array.getLength(value);
			for (int i = 0; i < length; i++) {
				Object param = Array.get(value, i);
				params.add(param);
			}
			return params;
		} else {
			// 如果是单个值，就创建一个值的list
			return Collections.singletonList(value);
		}
	}

	/**
	 * 集群热点参数流控规则判定
	 * @param resourceWrapper 指定resource
	 * @param rule            指定热点数据流控规则
	 * @param count           获取的token数量
	 * @param value           进行检查的参数值
	 * @return 是否通过热点参数规则检查
	 */
	private static boolean passClusterCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
											Object value) {
		try {
			// 将参数统一包装为Collection类型
			Collection<Object> params = toCollection(value);
			// 获取token服务
			TokenService clusterService = pickClusterService();
			if (clusterService == null) {
				// 如果没有可用的集群client或者server，降级到本地规则校验，或者直接通过
				return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
			}
			// 请求token使用信息
			TokenResult result = clusterService.requestParamToken(rule.getClusterConfig().getFlowId(), count, params);
			switch (result.getStatus()) {
				case TokenResultStatus.OK:
					// 返回获取成功，判定通过
					return true;
				case TokenResultStatus.BLOCKED:
					// 返回阻塞，判定失败
					return false;
				default:
					// 其他情况，降级到本地规则校验，或者直接通过
					return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
			}
		} catch (Throwable ex) {
			RecordLog.warn("[ParamFlowChecker] Request cluster token for parameter unexpected failed", ex);
			// 异常情况下，降级到本地规则校验，或者直接通过
			return fallbackToLocalOrPass(resourceWrapper, rule, count, value);
		}
	}

	/**
	 * 降级到本地规则校验，或者直接通过
	 * @param resourceWrapper 指定resource
	 * @param rule            指定热点数据流控规则
	 * @param count           获取的token数量
	 * @param value           进行检查的参数值
	 * @return 是否通过热点参数规则检查
	 */
	private static boolean fallbackToLocalOrPass(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
												 Object value) {
		// 开启降级模式，采用本地规则校验
		if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
			return passLocalCheck(resourceWrapper, rule, count, value);
		} else {
			// 未开启降级模式，直接判定通过
			return true;
		}
	}

	/**
	 * 获取服务端token服务
	 * @return 服务端token服务
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
}
