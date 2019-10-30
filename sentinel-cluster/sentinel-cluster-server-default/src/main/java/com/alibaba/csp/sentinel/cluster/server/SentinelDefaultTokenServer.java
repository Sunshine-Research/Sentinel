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
package com.alibaba.csp.sentinel.cluster.server;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.registry.ConfigSupplierRegistry;
import com.alibaba.csp.sentinel.cluster.server.config.ClusterServerConfigManager;
import com.alibaba.csp.sentinel.cluster.server.config.ServerTransportConfig;
import com.alibaba.csp.sentinel.cluster.server.config.ServerTransportConfigObserver;
import com.alibaba.csp.sentinel.cluster.server.connection.ConnectionManager;
import com.alibaba.csp.sentinel.init.InitExecutor;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.util.HostNameUtil;
import com.alibaba.csp.sentinel.util.StringUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sentinel默认的集群Token Server
 * @since 1.4.0
 */
public class SentinelDefaultTokenServer implements ClusterTokenServer {

	static {
		// 进行实例化
		InitExecutor.doInit();
	}

	/**
	 * 是否开启嵌入模式
	 */
	private final boolean embedded;
	/**
	 * 乐观锁
	 */
	private final AtomicBoolean shouldStart = new AtomicBoolean(false);
	/**
	 * 集群的Token Server实例
	 */
    private ClusterTokenServer server;
	/**
	 * Token Server端口号
	 */
	private int port;

    public SentinelDefaultTokenServer() {
		// 默认不开启嵌入模式
        this(false);
    }

    public SentinelDefaultTokenServer(boolean embedded) {
        this.embedded = embedded;
		// 添加集群服务端配置监听器，监听服务端配置的变化
        ClusterServerConfigManager.addTransportConfigChangeObserver(new ServerTransportConfigObserver() {
            @Override
            public void onTransportConfigChange(ServerTransportConfig config) {
                changeServerConfig(config);
            }
		});
		// 初始化新服务端
        initNewServer();
    }

    private void initNewServer() {
        if (server != null) {
            return;
		}
		// 获取服务端的接口
        int port = ClusterServerConfigManager.getPort();
        if (port > 0) {
			// 创建传输的Token Server
            this.server = new NettyTransportServer(port);
            this.port = port;
		}
	}

	/**
	 * 同步更新服务端的配置变化
	 * @param config 新的服务端配置
	 */
	private synchronized void changeServerConfig(ServerTransportConfig config) {
		if (config == null || config.getPort() <= 0) {
			return;
		}
		// 更新port
		int newPort = config.getPort();
		if (newPort == port) {
			return;
		}
		try {
			// 停止并重新替换、启动Server
			if (server != null) {
				stopServer();
			}
			this.server = new NettyTransportServer(newPort);
			this.port = newPort;
			startServerIfScheduled();
		} catch (Exception ex) {
			RecordLog.warn("[SentinelDefaultTokenServer] Failed to apply modification to token server", ex);
		}
	}

	/**
	 * 开启服务
	 * @throws Exception IO异常
	 */
	private void startServerIfScheduled() throws Exception {
		if (shouldStart.get()) {
			if (server != null) {
				server.start();
				// 集群状态标记为Server
				ClusterStateManager.markToServer();
				if (embedded) {
					// 嵌入模式下，处理嵌入模式启动
					RecordLog.info("[SentinelDefaultTokenServer] Running in embedded mode");
					handleEmbeddedStart();
				}
			}
		}
	}

	/**
	 * 关闭服务
	 * @throws Exception IO异常
	 */
	private void stopServer() throws Exception {
		if (server != null) {
			// 停止服务端，并处理嵌入模式下的停止
			server.stop();
			if (embedded) {
				handleEmbeddedStop();
			}
		}
	}

	/**
	 * 处理嵌入式停止
	 */
	private void handleEmbeddedStop() {
		String namespace = ConfigSupplierRegistry.getNamespaceSupplier().get();
		if (StringUtil.isNotEmpty(namespace)) {
			// 移除当前Server的连接
			ConnectionManager.removeConnection(namespace, HostNameUtil.getIp());
		}
	}

	/**
	 * 处理嵌入式模式启动
	 */
	private void handleEmbeddedStart() {
		// 获取当前的命名空间
		String namespace = ConfigSupplierRegistry.getNamespaceSupplier().get();
		if (StringUtil.isNotEmpty(namespace)) {
			// 标记Token Server的模式是嵌入模式
			ClusterServerConfigManager.setEmbedded(true);
			// 创建对应集合容器，并加载
			if (!ClusterServerConfigManager.getNamespaceSet().contains(namespace)) {
				Set<String> namespaceSet = new HashSet<>(ClusterServerConfigManager.getNamespaceSet());
				namespaceSet.add(namespace);
				ClusterServerConfigManager.loadServerNamespaceSet(namespaceSet);
			}

			// 将自己添加到连接中
			ConnectionManager.addConnection(namespace, HostNameUtil.getIp());
		}
	}

    @Override
    public void start() throws Exception {
        if (shouldStart.compareAndSet(false, true)) {
            startServerIfScheduled();
        }
    }

    @Override
    public void stop() throws Exception {
        if (shouldStart.compareAndSet(true, false)) {
            stopServer();
        }
    }
}
