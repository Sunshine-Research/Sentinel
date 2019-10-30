/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.param;

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.util.StringUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * resource度量标准存储器
 * @since 1.6.1
 */
public final class ParameterMetricStorage {

	/**
	 * 存储所有resource->度量标准的缓存
	 */
	private static final Map<String, ParameterMetric> metricsMap = new ConcurrentHashMap<>();

	/**
	 * 对指定resource进行上锁
	 */
	private static final Object LOCK = new Object();

	private ParameterMetricStorage() {
	}

	/**
	 * 实例化参数的度量标注，并对给定的resource进行索引映射
	 * @param resourceWrapper 需要实例化的resource
	 * @param rule            相关规则
	 */
	public static void initParamMetricsFor(ResourceWrapper resourceWrapper, /*@Valid*/ ParamFlowRule rule) {
		if (resourceWrapper == null || resourceWrapper.getName() == null) {
			return;
		}
		// 获取resource
		String resourceName = resourceWrapper.getName();

		ParameterMetric metric;
		// 获取resource的度量标准
		if ((metric = metricsMap.get(resourceName)) == null) {
			synchronized (LOCK) {
				if ((metric = metricsMap.get(resourceName)) == null) {
					metric = new ParameterMetric();
					metricsMap.put(resourceWrapper.getName(), metric);
					RecordLog.info("[ParameterMetricStorage] Creating parameter metric for: " + resourceWrapper.getName());
				}
			}
		}
		// 初始化给定规则的计数器
		metric.initialize(rule);
	}

	/**
	 * 获取给定resource的度量标准
	 * @param resourceWrapper resource
	 * @return resource的度量标准
	 */
	public static ParameterMetric getParamMetric(ResourceWrapper resourceWrapper) {
		if (resourceWrapper == null || resourceWrapper.getName() == null) {
			return null;
		}
		return metricsMap.get(resourceWrapper.getName());
	}

	/**
	 * 根据resource name获取度量标准
	 * @param resourceName resource name
	 * @return 给定resource name的度量标准
	 */
	public static ParameterMetric getParamMetricForResource(String resourceName) {
		if (resourceName == null) {
			return null;
		}
		return metricsMap.get(resourceName);
	}

	/**
	 * 清除指定resource name的度量标准
	 * @param resourceName resource name
	 */
	public static void clearParamMetricForResource(String resourceName) {
		if (StringUtil.isBlank(resourceName)) {
			return;
		}
		metricsMap.remove(resourceName);
		RecordLog.info("[ParameterMetricStorage] Clearing parameter metric for: " + resourceName);
	}

	/**
	 * 获取所有resource的度量标准
	 * @return 所有resource的度量标准
	 */
	static Map<String, ParameterMetric> getMetricsMap() {
		return metricsMap;
	}
}
