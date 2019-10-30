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

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slots.statistic.cache.CacheMap;
import com.alibaba.csp.sentinel.slots.statistic.cache.ConcurrentLinkedHashMapWrapper;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 热点参数度量标准
 * @since 0.2.0
 */
public class ParameterMetric {

	/**
	 * 最大线程数
	 */
	private static final int THREAD_COUNT_MAX_CAPACITY = 4000;
	/**
	 * 基准最大参数计数容量
	 */
	private static final int BASE_PARAM_MAX_CAPACITY = 4000;
	/**
	 * 系统最大参数计数容量
	 */
	private static final int TOTAL_MAX_CAPACITY = 20_0000;

	/**
	 * 乐观锁
	 */
	private final Object lock = new Object();

	/**
	 * 格式：(rule, (value, timeRecorder))
     * @since 1.6.0
     */
    private final Map<ParamFlowRule, CacheMap<Object, AtomicLong>> ruleTimeCounters = new HashMap<>();
	/**
	 * 格式：(rule, (value, tokenCounter))
     * @since 1.6.0
     */
    private final Map<ParamFlowRule, CacheMap<Object, AtomicLong>> ruleTokenCounter = new HashMap<>();
	/**
	 * 格式：(rule, (rule, threadCounter))
	 */
	private final Map<Integer, CacheMap<Object, AtomicInteger>> threadCountMap = new HashMap<>();

	/**
	 * 获取给定参数规则的token计数器
	 * @param rule 合法参数规则
	 * @return 关联的token counter
     * @since 1.6.0
     */
    public CacheMap<Object, AtomicLong> getRuleTokenCounter(ParamFlowRule rule) {
        return ruleTokenCounter.get(rule);
    }

	/**
	 * 获取给定参数规则的time record counter
	 * @param rule 合法参数规则
	 * @return 关联的token counter
     * @since 1.6.0
     */
    public CacheMap<Object, AtomicLong> getRuleTimeCounter(ParamFlowRule rule) {
        return ruleTimeCounters.get(rule);
	}

	/**
	 * 清除所有的计数器
	 */
	public void clear() {
		synchronized (lock) {
			threadCountMap.clear();
			ruleTimeCounters.clear();
			ruleTokenCounter.clear();
		}
	}

	/**
	 * 初始化给定规则的计数器
	 * @param rule 需要进行初始化度量标准的规则
	 */
	public void initialize(ParamFlowRule rule) {
		// 不存在当前规则的时间计数器，进行初始化
		if (!ruleTimeCounters.containsKey(rule)) {
			// 乐观同步操作
			synchronized (lock) {
				if (ruleTimeCounters.get(rule) == null) {
					// 获取基准参数热点的最大容量*窗口大小，和最大容量的最小值
					long size = Math.min(BASE_PARAM_MAX_CAPACITY * rule.getDurationInSec(), TOTAL_MAX_CAPACITY);
					// 对当前规则只缓存计算参数数量大小的计数器
					ruleTimeCounters.put(rule, new ConcurrentLinkedHashMapWrapper<Object, AtomicLong>(size));
				}
			}
		}
		// 不存在当前规则的token计数器，进行初始化
		if (!ruleTokenCounter.containsKey(rule)) {
			// 乐观同步操作
			synchronized (lock) {
				if (ruleTokenCounter.get(rule) == null) {
					// 获取基准参数热点的最大容量*窗口大小，和最大容量的最小值
					long size = Math.min(BASE_PARAM_MAX_CAPACITY * rule.getDurationInSec(), TOTAL_MAX_CAPACITY);
					// 对当前规则只缓存计算参数数量大小的计数器
					ruleTokenCounter.put(rule, new ConcurrentLinkedHashMapWrapper<Object, AtomicLong>(size));
				}
			}
		}
		// 不存在当前规则的线程计数器，进行初始化
		if (!threadCountMap.containsKey(rule.getParamIdx())) {
			// 乐观同步操作
			synchronized (lock) {
				if (threadCountMap.get(rule.getParamIdx()) == null) {
					// 直接初始化一个最大线程数量的计数器映射集合
					threadCountMap.put(rule.getParamIdx(),
							new ConcurrentLinkedHashMapWrapper<Object, AtomicInteger>(THREAD_COUNT_MAX_CAPACITY));
				}
			}
		}
	}

	/**
	 * 减少请求线程数量
	 * @param args 自定义请求参数
	 */
	@SuppressWarnings("rawtypes")
	public void decreaseThreadCount(Object... args) {
		if (args == null) {
			return;
		}

		try {
			for (int index = 0; index < args.length; index++) {
				// 获取缓存的线程计数器
				CacheMap<Object, AtomicInteger> threadCount = threadCountMap.get(index);
				if (threadCount == null) {
					continue;
				}
				// 获取参数名称
				Object arg = args[index];
				if (arg == null) {
					continue;
				}
				// 如果参数类型是Collection的子类
				if (Collection.class.isAssignableFrom(arg.getClass())) {
					// 遍历arg中所有的元素
					for (Object value : ((Collection)arg)) {
						// 获取旧值
						AtomicInteger oldValue = threadCount.putIfAbsent(value, new AtomicInteger());
						if (oldValue != null) {
							// 如果存在旧值，而非新插入的
							// --操作
							int currentValue = oldValue.decrementAndGet();
							// 如果之前oldValue=0或1，--后变为负数，没有意义，移除此参数的计数器
							if (currentValue <= 0) {
								threadCount.remove(value);
							}
						}

					}
				} else if (arg.getClass().isArray()) {
					// 如果参数类型是数组
					int length = Array.getLength(arg);
					// 同样需要遍历数组中所有的参数
					for (int i = 0; i < length; i++) {
						Object value = Array.get(arg, i);
						// 获取旧值
						AtomicInteger oldValue = threadCount.putIfAbsent(value, new AtomicInteger());
						if (oldValue != null) {
							// 旧值--操作
							int currentValue = oldValue.decrementAndGet();
							// 移除无效计数器
							if (currentValue <= 0) {
								threadCount.remove(value);
							}
						}

					}
				} else {
					// 一般情况下，是单个值
					// 获取旧值
					AtomicInteger oldValue = threadCount.putIfAbsent(arg, new AtomicInteger());
					if (oldValue != null) {
						// 旧值--操作
						int currentValue = oldValue.decrementAndGet();
						// 移除无效计数器
						if (currentValue <= 0) {
							threadCount.remove(arg);
						}
					}

				}

			}
		} catch (Throwable e) {
			RecordLog.warn("[ParameterMetric] Param exception", e);
		}
	}

	/**
	 * 添加请求线程数量
	 * 和decrementThreadCount()方法类似，操作相反
	 * @param args 自定义参数
	 */
	@SuppressWarnings("rawtypes")
	public void addThreadCount(Object... args) {
		if (args == null) {
			return;
		}

		try {
			for (int index = 0; index < args.length; index++) {
				// 获取缓存的线程计数器
				CacheMap<Object, AtomicInteger> threadCount = threadCountMap.get(index);
				if (threadCount == null) {
					continue;
				}
				// 获取参数名称
				Object arg = args[index];
				if (arg == null) {
					continue;
				}
				// 如果参数类型是Collection的子类
				if (Collection.class.isAssignableFrom(arg.getClass())) {
					// 遍历arg中所有的元素
					for (Object value : ((Collection)arg)) {
						// 获取旧值
						AtomicInteger oldValue = threadCount.putIfAbsent(value, new AtomicInteger());
						if (oldValue != null) {
							// 如果存在旧值，而非新插入的
							// ++操作
							oldValue.incrementAndGet();
						} else {
							// 如果没有旧值，就需要添加一个值为1的计数器
							threadCount.put(value, new AtomicInteger(1));
						}

					}
				} else if (arg.getClass().isArray()) {
					// 如果参数类型是数组
					int length = Array.getLength(arg);
					// 同样需要遍历数组中所有的参数
					for (int i = 0; i < length; i++) {
						Object value = Array.get(arg, i);
						// 获取旧值
						AtomicInteger oldValue = threadCount.putIfAbsent(value, new AtomicInteger());
						if (oldValue != null) {
							// 旧值++操作
							oldValue.incrementAndGet();
						} else {
							// 如果没有旧值，就需要添加一个值为1的计数器
							threadCount.put(value, new AtomicInteger(1));
						}

					}
				} else {
					// 一般情况下，是单个值
					// 获取旧值
					AtomicInteger oldValue = threadCount.putIfAbsent(arg, new AtomicInteger());
					if (oldValue != null) {
						// 旧值++操作
						oldValue.incrementAndGet();
					} else {
						// 如果没有旧值，就需要添加一个值为1的计数器
						threadCount.put(arg, new AtomicInteger(1));
					}

				}

			}

		} catch (Throwable e) {
			RecordLog.warn("[ParameterMetric] Param exception", e);
		}
	}

    public long getThreadCount(int index, Object value) {
        CacheMap<Object, AtomicInteger> cacheMap = threadCountMap.get(index);
        if (cacheMap == null) {
            return 0;
        }

        AtomicInteger count = cacheMap.get(value);
        return count == null ? 0L : count.get();
    }

	/**
	 * 获取全部token计数器
	 * @return 全部token计数器
     */
    Map<ParamFlowRule, CacheMap<Object, AtomicLong>> getRuleTokenCounterMap() {
        return ruleTokenCounter;
	}

	/**
	 * 获取全部线程计数器
	 * @return 全部线程计数器
	 */
	Map<Integer, CacheMap<Object, AtomicInteger>> getThreadCountMap() {
		return threadCountMap;
	}

	/**
	 * 获取全部时间计数器
	 * @return 全部时间计数器
	 */
	Map<ParamFlowRule, CacheMap<Object, AtomicLong>> getRuleTimeCounterMap() {
		return ruleTimeCounters;
	}
}
