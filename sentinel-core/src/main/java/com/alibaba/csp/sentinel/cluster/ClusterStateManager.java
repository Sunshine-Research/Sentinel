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
package com.alibaba.csp.sentinel.cluster;

import com.alibaba.csp.sentinel.cluster.client.ClusterTokenClient;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServer;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.init.InitExecutor;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.property.DynamicSentinelProperty;
import com.alibaba.csp.sentinel.property.PropertyListener;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Sentinel集群模式下的全局状态管理器
 * 可以在token client和token server两种模式下进行切换
 * @since 1.4.0
 */
public final class ClusterStateManager {
	/**
	 * 集群状态——Client
	 */
    public static final int CLUSTER_CLIENT = 0;
	/**
	 * 集群状态——Server
	 */
	public static final int CLUSTER_SERVER = 1;
	/**
	 * 集群状态——未启动
	 */
	public static final int CLUSTER_NOT_STARTED = -1;
	/**
	 * 属性变更listener
	 */
    private static final PropertyListener<Integer> PROPERTY_LISTENER = new ClusterStatePropertyListener();
	/**
	 * 切换模式的时间间隔，默认是5
	 */
	private static final int MIN_INTERVAL = 5 * 1000;

	private static volatile SentinelProperty<Integer> stateProperty = new DynamicSentinelProperty<Integer>();
	/**
	 * 默认模式为未启动状态
	 */
	private static volatile int mode = CLUSTER_NOT_STARTED;

    static {
        InitExecutor.doInit();
        stateProperty.addListener(PROPERTY_LISTENER);
	}

	/**
	 * 上一次更新状态的时间戳
	 */
	private static volatile long lastModified = -1;

    public static int getMode() {
        return mode;
    }

    public static boolean isClient() {
        return mode == CLUSTER_CLIENT;
    }

    public static boolean isServer() {
        return mode == CLUSTER_SERVER;
	}

	/**
	 * 注册属性
	 * @param property
	 */
	public static void registerProperty(SentinelProperty<Integer> property) {
		// 由于属性修改，需要锁住属性修改listener
		synchronized (PROPERTY_LISTENER) {
			RecordLog.info("[ClusterStateManager] Registering new property to cluster state manager");
			stateProperty.removeListener(PROPERTY_LISTENER);
			// 更新listener，重新设置集群属性
			property.addListener(PROPERTY_LISTENER);
			stateProperty = property;
		}
	}

	/**
	 * 切换当前模式至client模式
	 * 如果Sentinel当前正以server模式进行工作，需要先关闭，再启动client
     */
    public static boolean setToClient() {
		// 当前模式已经是cluster模式，不进行处理，直接返回true
        if (mode == CLUSTER_CLIENT) {
			return true;
        }
		// 先将模式切换为client模式
		mode = CLUSTER_CLIENT;
		// 判断模式切换是否需要时间间隔
		sleepIfNeeded();
		// 更新最近一次更新模式的时间
        lastModified = TimeUtil.currentTimeMillis();
		return startClient();
	}

	private static boolean stopClient() {
        try {
            ClusterTokenClient tokenClient = TokenClientProvider.getClient();
			if (tokenClient != null) {
				tokenClient.stop();
				RecordLog.info("[ClusterStateManager] Stopping the cluster token client");
                return true;
			} else {
				RecordLog.warn("[ClusterStateManager] Cannot stop cluster token client (no server SPI found)");
                return false;
            }
		} catch (Exception ex) {
			RecordLog.warn("[ClusterStateManager] Error when stopping cluster token client", ex);
			return false;
		}
	}

	/**
	 * 启动client模式
	 * @return 是否成功启动client
	 */
	private static boolean startClient() {
		try {
			// 获取当前集群的server
			EmbeddedClusterTokenServer server = EmbeddedClusterTokenServerProvider.getServer();
			// 如果是切换为client模式，则需要关闭server模式的客户端
			if (server != null) {
				server.stop();
			}
			// 获取token client
            ClusterTokenClient tokenClient = TokenClientProvider.getClient();
			if (tokenClient != null) {
				// 启动token client，并返回成功启动，核心是启动NettyBootStrap，建立NIO连接
				tokenClient.start();
				RecordLog.info("[ClusterStateManager] Changing cluster mode to client");
				return true;
			} else {
				RecordLog.warn("[ClusterStateManager] Cannot change to client (no client SPI found)");
				// 没有找到可用的token client，返回启动失败
                return false;
			}
		} catch (Exception ex) {
			RecordLog.warn("[ClusterStateManager] Error when changing cluster mode to client", ex);
			// 出现异常，返回启动失败
			return false;
		}
	}

	/**
	 * 切换模式至server端模式
	 * 如果Sentinel目前正处于client模式，也会先进行关闭，再以server模式启动
     */
	public static boolean setToServer() {
		// 模式没有发生变化，无需切换
        if (mode == CLUSTER_SERVER) {
			return true;
        }
		// 切换模式至server端模式
		mode = CLUSTER_SERVER;
		// 切换模式需要时间间隔
		sleepIfNeeded();
		// 更新最近一次切换模式的时间
        lastModified = TimeUtil.currentTimeMillis();
		return startServer();
    }

	/**
	 * 启动服务端
	 * @return
	 */
	private static boolean startServer() {
		try {
			// 停止token client
			ClusterTokenClient tokenClient = TokenClientProvider.getClient();
			if (tokenClient != null) {
				tokenClient.stop();
			}
			// 获取初始化好的token server
			EmbeddedClusterTokenServer server = EmbeddedClusterTokenServerProvider.getServer();
			if (server != null) {
				// 启动token server，并返回启动成功
				server.start();
				RecordLog.info("[ClusterStateManager] Changing cluster mode to server");
				return true;
			} else {
				RecordLog.warn("[ClusterStateManager] Cannot change to server (no server SPI found)");
				// 没有找到token server，返回启动失败
				return false;
			}
		} catch (Exception ex) {
			RecordLog.warn("[ClusterStateManager] Error when changing cluster mode to server", ex);
			// 出现异常，启动失败
			return false;
		}
	}

	/**
	 * 停止token server
	 * @return
	 */
	private static boolean stopServer() {
		try {
			EmbeddedClusterTokenServer server = EmbeddedClusterTokenServerProvider.getServer();
			if (server != null) {
				// 停止token server，返回停止成功
				server.stop();
				RecordLog.info("[ClusterStateManager] Stopping the cluster server");
				return true;
			} else {
				RecordLog.warn("[ClusterStateManager] Cannot stop server (no server SPI found)");
				// 没有找到对应的token server，返回停止失败
				return false;
			}
		} catch (Exception ex) {
			RecordLog.warn("[ClusterStateManager] Error when stopping server", ex);
			// 出现异常，返回停止失败
			return false;
		}
	}

	public static long getLastModified() {
		return lastModified;
	}

	/**
	 * 状态变更操作间隔需要一定的时间段，要超过{@code MIN_INTERVAL}，默认是5s
     */
    private static void sleepIfNeeded() {
        if (lastModified <= 0) {
            return;
        }
        long now = TimeUtil.currentTimeMillis();
		long durationPast = now - lastModified;
		// 计算当前时间和上次变更的时间之差，看差值是否已经超过{@code MIN_INTERVAL}
		// 存在差值，则需要进行睡眠
        long estimated = durationPast - MIN_INTERVAL;
        if (estimated < 0) {
            try {
                Thread.sleep(-estimated);
            } catch (InterruptedException e) {
                e.printStackTrace();
			}
		}
	}

	/**
	 * 更新集群状态
	 * @param state 需要设置的集群状态
	 * @return
	 */
    private static boolean applyStateInternal(Integer state) {
		// 对于未知状态和未启动之前的状态值，不予设置
        if (state == null || state < CLUSTER_NOT_STARTED) {
			return false;
        }
		// 当前集群状态没有发生变化，直接返回更新成功
        if (state == mode) {
            return true;
        }
        try {
            switch (state) {
				case CLUSTER_CLIENT:
					// 更新为token client
                    return setToClient();
				case CLUSTER_SERVER:
					// 更新为token server
                    return setToServer();
				case CLUSTER_NOT_STARTED:
					// 停止当前服务
                    setStop();
                    return true;
                default:
                    RecordLog.warn("[ClusterStateManager] Ignoring unknown cluster state: " + state);
                    return false;
            }
        } catch (Throwable t) {
            RecordLog.warn("[ClusterStateManager] Fatal error when applying state: " + state, t);
			return false;
        }
    }

	/**
	 * 停止服务
	 */
	private static void setStop() {
		// 如果当前模式已经为未启动状态，直接返回
		if (mode == CLUSTER_NOT_STARTED) {
			return;
		}
		RecordLog.info("[ClusterStateManager] Changing cluster mode to not-started");
		// 先将模式切换为未启动状态
		mode = CLUSTER_NOT_STARTED;
		// 切换模式需要时间间隔
		sleepIfNeeded();
		// 更新最近一次更新的时间戳
		lastModified = TimeUtil.currentTimeMillis();
		// 由于此时可能是出现的异常情况，所以为了兜底，对两种模式的客户端都进行关闭
		stopClient();
		stopServer();
	}

	/**
	 * 设置给定的集群模式
     * @param state 需要设置的状态
     */
    public static void applyState(Integer state) {
		stateProperty.updateValue(state);
    }

	/**
	 * 切换模式为server模式
	 */
	public static void markToServer() {
		mode = CLUSTER_SERVER;
	}

	/**
	 * 监听集群属性变更
	 */
	private static class ClusterStatePropertyListener implements PropertyListener<Integer> {
		@Override
		public synchronized void configLoad(Integer value) {
			applyStateInternal(value);
		}

		@Override
		public synchronized void configUpdate(Integer value) {
			applyStateInternal(value);
        }
    }
}
