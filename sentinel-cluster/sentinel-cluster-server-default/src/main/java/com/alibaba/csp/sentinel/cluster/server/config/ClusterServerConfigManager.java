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
package com.alibaba.csp.sentinel.cluster.server.config;

import com.alibaba.csp.sentinel.cluster.ClusterConstants;
import com.alibaba.csp.sentinel.cluster.flow.rule.ClusterFlowRuleManager;
import com.alibaba.csp.sentinel.cluster.flow.rule.ClusterParamFlowRuleManager;
import com.alibaba.csp.sentinel.cluster.flow.statistic.ClusterMetricStatistics;
import com.alibaba.csp.sentinel.cluster.flow.statistic.ClusterParamMetricStatistics;
import com.alibaba.csp.sentinel.cluster.flow.statistic.limit.GlobalRequestLimiter;
import com.alibaba.csp.sentinel.cluster.registry.ConfigSupplierRegistry;
import com.alibaba.csp.sentinel.cluster.server.ServerConstants;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.PropertyListener;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleUtil;
import com.alibaba.csp.sentinel.util.AssertUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集群Server配置管理器
 * @since 1.4.0
 */
public final class ClusterServerConfigManager {
	/**
	 * 每个namespace的流控配置
	 * namespace->flowConfig
	 */
	private static final Map<String, ServerFlowConfig> NAMESPACE_CONF = new ConcurrentHashMap<>();
	/**
	 * 服务端配置修改listener
	 */
	private static final List<ServerTransportConfigObserver> TRANSPORT_CONFIG_OBSERVERS = new ArrayList<>();
	/**
	 * 数据传输属性listener
	 */
	private static final PropertyListener<ServerTransportConfig> TRANSPORT_PROPERTY_LISTENER
			= new ServerGlobalTransportPropertyListener();
	/**
	 * 全局流控配置属性listener
	 */
	private static final PropertyListener<ServerFlowConfig> GLOBAL_FLOW_PROPERTY_LISTENER
			= new ServerGlobalFlowPropertyListener();

	/**
	 * 集群全局流控配置 start
	 */
	/**
	 * 命名空间更新属性listener
	 */
	private static final PropertyListener<Set<String>> NAMESPACE_SET_PROPERTY_LISTENER
			= new ServerNamespaceSetPropertyListener();
	/**
	 * 是否是嵌入式状态
	 */
	private static boolean embedded = false;
	/**
	 * 集群Server模式默认的服务端口
	 */
	private static volatile int port = ClusterConstants.DEFAULT_CLUSTER_SERVER_PORT;
	/**
	 * 空闲的时间
	 */
	private static volatile int idleSeconds = ServerTransportConfig.DEFAULT_IDLE_SECONDS;
	/**
	 * 集群需要管理的命名空间
	 */
	private static volatile Set<String> namespaceSet = Collections.singleton(ServerConstants.DEFAULT_NAMESPACE);
	/**
	 * 集群全局流控配置 end
	 */
	/**
	 * 默认超出的数量，默认为1
	 */
	private static volatile double exceedCount = ServerFlowConfig.DEFAULT_EXCEED_COUNT;
	/**
	 * 最大可占有的比率——默认为1
	 */
	private static volatile double maxOccupyRatio = ServerFlowConfig.DEFAULT_MAX_OCCUPY_RATIO;
	/**
	 * 窗口时间间隔，默认1000ms
	 */
	private static volatile int intervalMs = ServerFlowConfig.DEFAULT_INTERVAL_MS;
	/**
	 *
	 */
	private static volatile int sampleCount = ServerFlowConfig.DEFAULT_SAMPLE_COUNT;
	/**
	 * 允许通过的最大QPS，默认是30000个
	 */
	private static volatile double maxAllowedQps = ServerFlowConfig.DEFAULT_MAX_ALLOWED_QPS;
	/**
	 * 集群server端全局传输的属性配置
	 * 是动态属性
	 */
	private static SentinelProperty<ServerTransportConfig> transportConfigProperty = new DynamicSentinelProperty<>();
	/**
	 * 集群服务命名空间属性集合
	 */
	private static SentinelProperty<Set<String>> namespaceSetProperty = new DynamicSentinelProperty<>();
	/**
	 * 集群服务全局流控配置属性
	 */
	private static SentinelProperty<ServerFlowConfig> globalFlowProperty = new DynamicSentinelProperty<>();

	static {
		transportConfigProperty.addListener(TRANSPORT_PROPERTY_LISTENER);
		globalFlowProperty.addListener(GLOBAL_FLOW_PROPERTY_LISTENER);
		namespaceSetProperty.addListener(NAMESPACE_SET_PROPERTY_LISTENER);
	}

	private ClusterServerConfigManager() {
	}

	/**
	 * 注册命名空间集合属性
	 * 是动态数据源
	 * @param property 服务端命名空间动态属性集合
	 */
	public static void registerNamespaceSetProperty(SentinelProperty<Set<String>> property) {
		AssertUtil.notNull(property, "namespace set dynamic property cannot be null");
		// 迁移的方式是将新property替换为旧的property，所以需要重置listener
		synchronized (NAMESPACE_SET_PROPERTY_LISTENER) {
			RecordLog.info(
					"[ClusterServerConfigManager] Registering new namespace set dynamic property to Sentinel server "
							+ "config manager");
			namespaceSetProperty.removeListener(NAMESPACE_SET_PROPERTY_LISTENER);
			property.addListener(NAMESPACE_SET_PROPERTY_LISTENER);
			namespaceSetProperty = property;
		}
	}

	/**
	 * 注册集群数据传输动态属性配置
	 * @param property 集群数据传输动态属性配置
	 */
	public static void registerServerTransportProperty(SentinelProperty<ServerTransportConfig> property) {
		AssertUtil.notNull(property, "cluster server transport config dynamic property cannot be null");
		// 迁移的方式是将新property替换为旧的property，所以需要重置listener
		synchronized (TRANSPORT_PROPERTY_LISTENER) {
			RecordLog.info(
					"[ClusterServerConfigManager] Registering new server transport dynamic property to Sentinel server "
							+ "config manager");
			transportConfigProperty.removeListener(TRANSPORT_PROPERTY_LISTENER);
			property.addListener(TRANSPORT_PROPERTY_LISTENER);
			transportConfigProperty = property;
		}
	}

	/**
	 * 注册服务全局流控配置动态属性
	 * @param property 服务全局流控配置动态属性
	 */
	public static void registerGlobalServerFlowProperty(SentinelProperty<ServerFlowConfig> property) {
		AssertUtil.notNull(property, "cluster server flow config dynamic property cannot be null");
		// 迁移的方式是将新property替换为旧的property，所以需要重置listener
		synchronized (GLOBAL_FLOW_PROPERTY_LISTENER) {
			RecordLog.info(
					"[ClusterServerConfigManager] Registering new server global flow dynamic property "
							+ "to Sentinel server config manager");
			globalFlowProperty.removeListener(GLOBAL_FLOW_PROPERTY_LISTENER);
			property.addListener(GLOBAL_FLOW_PROPERTY_LISTENER);
			globalFlowProperty = property;
		}
	}

	/**
	 * Load provided server namespace set to property in memory.
	 * @param namespaceSet valid namespace set
	 */
	public static void loadServerNamespaceSet(Set<String> namespaceSet) {
		namespaceSetProperty.updateValue(namespaceSet);
	}

	/**
	 * 加载服务传输配置到内存属性中
	 * @param config 合法的集群服务传输配置
	 */
	public static void loadGlobalTransportConfig(ServerTransportConfig config) {
		transportConfigProperty.updateValue(config);
	}

	/**
	 * 加载服务全局流控配置到内存属性中
	 * @param config 合法的服务全局流控配置
	 */
	public static void loadGlobalFlowConfig(ServerFlowConfig config) {
		globalFlowProperty.updateValue(config);
	}

	/**
	 * 为特定的namespace加载服务流控配置
	 * @param namespace 合法的命名空间
	 * @param config    合法的命名空间的流控配置
	 */
	public static void loadFlowConfig(String namespace, ServerFlowConfig config) {
		AssertUtil.notEmpty(namespace, "namespace cannot be empty");
		// TODO: Support namespace-scope server flow config.
		globalFlowProperty.updateValue(config);
	}

	/**
	 * 添加服务传输配置变更listener，listener将在配置更新后立即调用
	 * @param observer 合法的传输配置listener
	 */
	public static void addTransportConfigChangeObserver(ServerTransportConfigObserver observer) {
		AssertUtil.notNull(observer, "observer cannot be null");
		TRANSPORT_CONFIG_OBSERVERS.add(observer);
	}

	/**
	 * 更新命名空间集合属性
	 * @param newSet 需要进行更新的命名空间属性集合
	 */
	private static void applyNamespaceSetChange(Set<String> newSet) {
		if (newSet == null) {
			return;
		}
		RecordLog.info("[ClusterServerConfigManager] Server namespace set will be update to: " + newSet);
		// 命名空间集合不能更新为空，默认命名空间为"default"
		if (newSet.isEmpty()) {
			ClusterServerConfigManager.namespaceSet = Collections.singleton(ServerConstants.DEFAULT_NAMESPACE);
			return;
		}

		newSet = new HashSet<>(newSet);
		// Always add the `default` namespace to the namespace set.
		// 必须添加"default"命名空间到命名空间集合中
		newSet.add(ServerConstants.DEFAULT_NAMESPACE);

		// 如果Token Server开启了嵌入模式
		if (embedded) {
			// Token Server本身也是服务的一部分，所以也需要命名空间
			// 默认情况下，添加的命名空间是应用名称
			newSet.add(ConfigSupplierRegistry.getNamespaceSupplier().get());
		}

		Set<String> oldSet = ClusterServerConfigManager.namespaceSet;
		if (oldSet != null && !oldSet.isEmpty()) {
			for (String ns : oldSet) {
				// Remove the cluster rule property for deprecated namespace set.
				if (!newSet.contains(ns)) {
					ClusterFlowRuleManager.removeProperty(ns);
					ClusterParamFlowRuleManager.removeProperty(ns);
				}
			}
		}

		ClusterServerConfigManager.namespaceSet = newSet;
		for (String ns : newSet) {
			// Register the rule property if needed.
			ClusterFlowRuleManager.registerPropertyIfAbsent(ns);
			ClusterParamFlowRuleManager.registerPropertyIfAbsent(ns);
			// Initialize the global QPS limiter for the namespace.
			GlobalRequestLimiter.initIfAbsent(ns);
		}
	}

	/**
	 * 更新token服务端
	 * @param config 服务传输配置
	 */
	private static void updateTokenServer(ServerTransportConfig config) {
		int newPort = config.getPort();
		AssertUtil.isTrue(newPort > 0, "token server port should be valid (positive)");
		if (newPort == port) {
			return;
		}
		// 更新服务端配置为最新的即可欧
		ClusterServerConfigManager.port = newPort;
		// 通知服务传输listener，传输配置已经更新
		for (ServerTransportConfigObserver observer : TRANSPORT_CONFIG_OBSERVERS) {
			observer.onTransportConfigChange(config);
		}
	}

	public static boolean isValidTransportConfig(ServerTransportConfig config) {
		return config != null && config.getPort() > 0 && config.getPort() <= 65535;
	}

	public static boolean isValidFlowConfig(ServerFlowConfig config) {
		return config != null && config.getMaxOccupyRatio() >= 0 && config.getExceedCount() >= 0
				&& config.getMaxAllowedQps() >= 0
				&& FlowRuleUtil.isWindowConfigValid(config.getSampleCount(), config.getIntervalMs());
	}

	public static double getExceedCount(String namespace) {
		AssertUtil.notEmpty(namespace, "namespace cannot be empty");
		ServerFlowConfig config = NAMESPACE_CONF.get(namespace);
		if (config != null) {
			return config.getExceedCount();
		}
		return exceedCount;
	}

	public static double getMaxOccupyRatio(String namespace) {
		AssertUtil.notEmpty(namespace, "namespace cannot be empty");
		ServerFlowConfig config = NAMESPACE_CONF.get(namespace);
		if (config != null) {
			return config.getMaxOccupyRatio();
		}
		return maxOccupyRatio;
	}

	public static int getIntervalMs(String namespace) {
		AssertUtil.notEmpty(namespace, "namespace cannot be empty");
		ServerFlowConfig config = NAMESPACE_CONF.get(namespace);
		if (config != null) {
			return config.getIntervalMs();
		}
		return intervalMs;
	}

	/**
	 * 获取当前命名空间的样本数量
	 * @param namespace 合法的命名空间
	 * @return 命名空间的样本数量，如果命名空间没有流控配置，使用全局样本数量
	 */
	public static int getSampleCount(String namespace) {
		AssertUtil.notEmpty(namespace, "namespace cannot be empty");
		ServerFlowConfig config = NAMESPACE_CONF.get(namespace);
		if (config != null) {
			return config.getSampleCount();
		}
		return sampleCount;
	}

	public static double getMaxAllowedQps() {
		return maxAllowedQps;
	}

	public static void setMaxAllowedQps(double maxAllowedQps) {
		ClusterServerConfigManager.maxAllowedQps = maxAllowedQps;
	}

	public static double getMaxAllowedQps(String namespace) {
		AssertUtil.notEmpty(namespace, "namespace cannot be empty");
		ServerFlowConfig config = NAMESPACE_CONF.get(namespace);
		if (config != null) {
			return config.getMaxAllowedQps();
		}
		return maxAllowedQps;
	}

	public static double getExceedCount() {
		return exceedCount;
	}

	public static double getMaxOccupyRatio() {
		return maxOccupyRatio;
	}

	public static Set<String> getNamespaceSet() {
		return namespaceSet;
	}

	public static void setNamespaceSet(Set<String> namespaceSet) {
		applyNamespaceSetChange(namespaceSet);
	}

	public static int getPort() {
		return port;
	}

	public static int getIdleSeconds() {
		return idleSeconds;
	}

	public static int getIntervalMs() {
		return intervalMs;
	}

	public static int getSampleCount() {
		return sampleCount;
	}

	public static boolean isEmbedded() {
		return embedded;
	}

	/**
	 * 设置token server的嵌入属性
	 * 需要注意的是：开发者不能手动调用这个方法，嵌入标识应该在启动token server时设定
	 * @param embedded token server是否运行于embedded模式下
	 */
	public static void setEmbedded(boolean embedded) {
		ClusterServerConfigManager.embedded = embedded;
	}

	/**
	 * 服务命名空间集合属性listener
	 */
	private static class ServerNamespaceSetPropertyListener implements PropertyListener<Set<String>> {

		@Override
		public synchronized void configLoad(Set<String> set) {
			if (set == null || set.isEmpty()) {
				RecordLog.warn("[ClusterServerConfigManager] WARN: empty initial server namespace set");
				return;
			}
			// 设置命名空间集合
			applyNamespaceSetChange(set);
		}

		@Override
		public synchronized void configUpdate(Set<String> set) {
			// TODO: should debounce?
			applyNamespaceSetChange(set);
		}
	}

	/**
	 * 服务端全局传输信息属性listener
	 */
	private static class ServerGlobalTransportPropertyListener implements PropertyListener<ServerTransportConfig> {

		@Override
		public void configLoad(ServerTransportConfig config) {
			if (config == null) {
				RecordLog.warn("[ClusterServerConfigManager] Empty initial server transport config");
				return;
			}
			// 更新配置信息
			applyConfig(config);
		}

		@Override
		public void configUpdate(ServerTransportConfig config) {
			applyConfig(config);
		}

		/**
		 * 在同步的情况下，更新配置信息
		 * @param config 新的服务传输配置
		 */
		private synchronized void applyConfig(ServerTransportConfig config) {
			// 校验传输配置
			if (!isValidTransportConfig(config)) {
				RecordLog.warn(
						"[ClusterServerConfigManager] Invalid cluster server transport config, ignoring: " + config);
				return;
			}
			RecordLog.info("[ClusterServerConfigManager] Updating new server transport config: " + config);
			// 更新空闲时间
			if (config.getIdleSeconds() != idleSeconds) {
				idleSeconds = config.getIdleSeconds();
			}
			// 更新token服务端配置
			updateTokenServer(config);
		}
	}

	/**
	 * 服务端全局流控属性listener
	 */
	private static class ServerGlobalFlowPropertyListener implements PropertyListener<ServerFlowConfig> {

		@Override
		public void configUpdate(ServerFlowConfig config) {
			// 更新全局流控配置
			applyGlobalFlowConfig(config);
		}

		@Override
		public void configLoad(ServerFlowConfig config) {
			// 加载全局流控配置
			applyGlobalFlowConfig(config);
		}

		/**
		 * 同步设置全局流控配置
		 * @param config
		 */
		private synchronized void applyGlobalFlowConfig(ServerFlowConfig config) {
			// 校验更新的流控规则
			if (!isValidFlowConfig(config)) {
				RecordLog.warn(
						"[ClusterServerConfigManager] Invalid cluster server global flow config, ignoring: " + config);
				return;
			}
			RecordLog.info("[ClusterServerConfigManager] Updating new server global flow config: " + config);
			// 只会在值发生变化的情况下进行更新
			if (config.getExceedCount() != exceedCount) {
				exceedCount = config.getExceedCount();
			}
			if (config.getMaxOccupyRatio() != maxOccupyRatio) {
				maxOccupyRatio = config.getMaxOccupyRatio();
			}
			if (config.getMaxAllowedQps() != maxAllowedQps) {
				maxAllowedQps = config.getMaxAllowedQps();
				GlobalRequestLimiter.applyMaxQpsChange(maxAllowedQps);
			}
			int newIntervalMs = config.getIntervalMs();
			int newSampleCount = config.getSampleCount();
			if (newIntervalMs != intervalMs || newSampleCount != sampleCount) {
				if (newIntervalMs <= 0 || newSampleCount <= 0 || newIntervalMs % newSampleCount != 0) {
					RecordLog.warn("[ClusterServerConfigManager] Ignoring invalid flow interval or sample count");
				} else {
					intervalMs = newIntervalMs;
					sampleCount = newSampleCount;
					// 重置所有的计数器
					ClusterMetricStatistics.resetFlowMetrics();
					ClusterParamMetricStatistics.resetFlowMetrics();
				}
			}
		}
	}
}
