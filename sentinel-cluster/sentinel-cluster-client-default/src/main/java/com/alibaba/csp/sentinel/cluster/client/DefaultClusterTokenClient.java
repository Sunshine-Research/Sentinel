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
package com.alibaba.csp.sentinel.cluster.client;

import com.alibaba.csp.sentinel.cluster.ClusterConstants;
import com.alibaba.csp.sentinel.cluster.ClusterErrorMessages;
import com.alibaba.csp.sentinel.cluster.ClusterTransportClient;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenServerDescriptor;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientAssignConfig;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientConfigManager;
import com.alibaba.csp.sentinel.cluster.client.config.ServerChangeObserver;
import com.alibaba.csp.sentinel.cluster.log.ClusterClientStatLogUtil;
import com.alibaba.csp.sentinel.cluster.request.ClusterRequest;
import com.alibaba.csp.sentinel.cluster.request.data.FlowRequestData;
import com.alibaba.csp.sentinel.cluster.request.data.ParamFlowRequestData;
import com.alibaba.csp.sentinel.cluster.response.ClusterResponse;
import com.alibaba.csp.sentinel.cluster.response.data.FlowTokenResponseData;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.util.StringUtil;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of {@link ClusterTokenClient}.
 * 默认{@link ClusterTokenClient}的实现
 * @author Eric Zhao
 * @since 1.4.0
 */
public class DefaultClusterTokenClient implements ClusterTokenClient {

	/**
	 * 乐观锁
	 */
	private final AtomicBoolean shouldStart = new AtomicBoolean(false);
	/**
	 * 集群传输客户端
	 */
	private ClusterTransportClient transportClient;
	/**
	 * Token Server描述
	 */
	private TokenServerDescriptor serverDescriptor;

    public DefaultClusterTokenClient() {
        ClusterClientConfigManager.addServerChangeObserver(new ServerChangeObserver() {
            @Override
            public void onRemoteServerChange(ClusterClientAssignConfig assignConfig) {
                changeServer(assignConfig);
            }
        });
        initNewConnection();
	}

	/**
	 * 判断TokenServer服务是否想等
	 * 比较二者的host和port
	 * @param descriptor TokenServer描述符
	 * @param config     集群分配配置
	 * @return TokenServer是否相等
	 */
	private boolean serverEqual(TokenServerDescriptor descriptor, ClusterClientAssignConfig config) {
		if (descriptor == null || config == null) {
			return false;
		}
		return descriptor.getHost().equals(config.getServerHost()) && descriptor.getPort() == config.getServerPort();
	}

	/**
	 * 初始化新连接
	 */
	private void initNewConnection() {
		// 如果已经建立和token server的链接，无需初始化，直接返回
		if (transportClient != null) {
			return;
		}
		// 获取TokenServer地址信息
		String host = ClusterClientConfigManager.getServerHost();
		int port = ClusterClientConfigManager.getServerPort();
		if (StringUtil.isBlank(host) || port <= 0) {
			return;
		}

		try {
			// 默认构建Netty客户端进行传输
			this.transportClient = new NettyTransportClient(host, port);
			this.serverDescriptor = new TokenServerDescriptor(host, port);
			RecordLog.info("[DefaultClusterTokenClient] New client created: " + serverDescriptor);
		} catch (Exception ex) {
			RecordLog.warn("[DefaultClusterTokenClient] Failed to initialize new token client", ex);
		}
	}

	/**
	 * 变更TokenServer
	 * @param config 新的集群配置
	 */
	private void changeServer(/*@Valid*/ ClusterClientAssignConfig config) {
		// 如果没有发生变化，直接返回
		if (serverEqual(serverDescriptor, config)) {
			return;
		}
		try {
			// 集群地址发生变化的情况下，首先停止传输客户端
			if (transportClient != null) {
				transportClient.stop();
			}
			// 并创建一套全新的传输设备
			this.transportClient = new NettyTransportClient(config.getServerHost(), config.getServerPort());
			this.serverDescriptor = new TokenServerDescriptor(config.getServerHost(), config.getServerPort());
			startClientIfScheduled();
			RecordLog.info("[DefaultClusterTokenClient] New client created: " + serverDescriptor);
		} catch (Exception ex) {
			RecordLog.warn("[DefaultClusterTokenClient] Failed to change remote token server", ex);
		}
	}

	/**
	 * 启动客户端
	 * @throws Exception 建立IO通道时的异常
	 */
	private void startClientIfScheduled() throws Exception {
		// 获取到锁的情况下，启动传输客户端
		if (shouldStart.get()) {
			if (transportClient != null) {
				transportClient.start();
			} else {
				RecordLog.warn("[DefaultClusterTokenClient] Cannot start transport client: client not created");
			}
		}
	}

	/**
	 * 关闭传出Client
	 * @throws Exception 建立IO通道时的异常
	 */
	private void stopClientIfStarted() throws Exception {
		if (shouldStart.compareAndSet(true, false)) {
			if (transportClient != null) {
				transportClient.stop();
			}
		}
	}

    @Override
    public void start() throws Exception {
		// 设置乐观锁，代表当前client处于已开启的状态
        if (shouldStart.compareAndSet(false, true)) {
            startClientIfScheduled();
        }
    }

    @Override
    public void stop() throws Exception {
        stopClientIfStarted();
    }

    @Override
    public int getState() {
        if (transportClient == null) {
            return ClientConstants.CLIENT_STATUS_OFF;
        }
        return transportClient.isReady() ? ClientConstants.CLIENT_STATUS_STARTED : ClientConstants.CLIENT_STATUS_OFF;
    }

    @Override
    public TokenServerDescriptor currentServer() {
        return serverDescriptor;
    }

    @Override
    public TokenResult requestToken(Long flowId, int acquireCount, boolean prioritized) {
		// 请求参数校验
        if (notValidRequest(flowId, acquireCount)) {
            return badRequest();
        }
		// 构建请求数据和请求
        FlowRequestData data = new FlowRequestData().setCount(acquireCount)
            .setFlowId(flowId).setPriority(prioritized);
        ClusterRequest<FlowRequestData> request = new ClusterRequest<>(ClusterConstants.MSG_TYPE_FLOW, data);
        try {
			// 发送获取令牌请求，获取结果
            TokenResult result = sendTokenRequest(request);
			// 记录请求信息
            logForResult(result);
            return result;
        } catch (Exception ex) {
            ClusterClientStatLogUtil.log(ex.getMessage());
            return new TokenResult(TokenResultStatus.FAIL);
		}
	}

	/**
	 * 为参数列表请求token
	 * @param flowId       全局唯一规则ID
	 * @param acquireCount 需要获取的数量
	 * @param params       请求的参数列表
	 * @return token请求结果
	 */
	@Override
	public TokenResult requestParamToken(Long flowId, int acquireCount, Collection<Object> params) {
		// 非法请求，直接返回失败的请求结果
		if (notValidRequest(flowId, acquireCount) || params == null || params.isEmpty()) {
			return badRequest();
		}
		// 构建请求
		ParamFlowRequestData data = new ParamFlowRequestData().setCount(acquireCount)
				.setFlowId(flowId).setParams(params);
		ClusterRequest<ParamFlowRequestData> request = new ClusterRequest<>(ClusterConstants.MSG_TYPE_PARAM_FLOW, data);
		try {
			// 发送请求
			TokenResult result = sendTokenRequest(request);
			// 记录请求结果
			logForResult(result);
			// 返回请求结果
			return result;
		} catch (Exception ex) {
			ClusterClientStatLogUtil.log(ex.getMessage());
			// 出现异常的情况下，返回请求失败结果
			return new TokenResult(TokenResultStatus.FAIL);
		}
	}

	/**
	 * 记录请求结果
	 * @param result 请求结果
	 */
	private void logForResult(TokenResult result) {
		switch (result.getStatus()) {
			case TokenResultStatus.NO_RULE_EXISTS:
				ClusterClientStatLogUtil.log(ClusterErrorMessages.NO_RULES_IN_SERVER);
				break;
			case TokenResultStatus.TOO_MANY_REQUEST:
				ClusterClientStatLogUtil.log(ClusterErrorMessages.TOO_MANY_REQUESTS);
				break;
			default:
		}
	}

	/**
	 * 发送获取token请求，默认使用Netty传输
	 * @param request 集群请求
	 * @return token请求结果
	 * @throws Exception IO异常
	 */
	private TokenResult sendTokenRequest(ClusterRequest request) throws Exception {
		if (transportClient == null) {
			RecordLog.warn(
					"[DefaultClusterTokenClient] Client not created, please check your config for cluster client");
			return clientFail();
		}
		ClusterResponse response = transportClient.sendRequest(request);
		TokenResult result = new TokenResult(response.getStatus());
		if (response.getData() != null) {
			// 构建响应
			FlowTokenResponseData responseData = (FlowTokenResponseData)response.getData();
			result.setRemaining(responseData.getRemainingCount())
					.setWaitInMs(responseData.getWaitInMs());
		}
		return result;
	}

    private boolean notValidRequest(Long id, int count) {
        return id == null || id <= 0 || count <= 0;
    }

    private TokenResult badRequest() {
        return new TokenResult(TokenResultStatus.BAD_REQUEST);
    }

    private TokenResult clientFail() {
        return new TokenResult(TokenResultStatus.FAIL);
    }
}
