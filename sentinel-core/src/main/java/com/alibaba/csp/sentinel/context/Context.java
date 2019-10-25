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
package com.alibaba.csp.sentinel.context;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphO;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;

/**
 * 上下文类
 * 此类包含了当前调用的metadata：
 * 入口Node：当前调用树的跟节点
 * 当前Entry：当前调用的节点
 * 当前Node：和Entry有关的数据分析
 * origin：如果想要分开控制不同的invoker/consumer，origin是很有用的
 * 每次{@link SphU}#entry() or {@link SphO}#entry()调用都应该在同一个上下文中
 * 如果没有使用显示调用 {@link ContextUtil}#enter() explicitly，就会使用默认的上下文
 *
 * 在同一个上下文中如果多次调用{@link SphU}#entry()，就会产生一个调用树
 *
 * 相同的resource在不同的上下文中，令牌数量的计算也是分开的，具体请看{@link NodeSelectorSlot}
 */
public class Context {

	/**
	 * 上下文名称
	 */
	private final String name;
	/**
	 * 是否是异步的
	 */
	private final boolean async;
	/**
	 * 调用树的入口节点
	 */
	private DefaultNode entranceNode;
	/**
	 * 当前正在处理的entry
	 */
	private Entry curEntry;
	/**
	 * 当前上下文的origin名称，通常证明不同的invoker，比如service服务名称或者origin的ip地址
	 */
	private String origin = "";

	public Context(DefaultNode entranceNode, String name) {
		this(name, entranceNode, false);
	}

	public Context(String name, DefaultNode entranceNode, boolean async) {
		this.name = name;
		this.entranceNode = entranceNode;
		this.async = async;
	}

	/**
	 * 创建异步的上下文
	 * @param entranceNode 上下文的入口node
	 * @param name         上下文名称
	 * @return 新创建的上下文
	 * @since 0.2.0
	 */
	public static Context newAsyncContext(DefaultNode entranceNode, String name) {
		return new Context(name, entranceNode, true);
	}

	public boolean isAsync() {
		return async;
	}

	public String getName() {
		return name;
	}

	public Node getCurNode() {
		return curEntry.getCurNode();
	}

	public Context setCurNode(Node node) {
		this.curEntry.setCurNode(node);
		return this;
	}

	public Entry getCurEntry() {
		return curEntry;
	}

	public Context setCurEntry(Entry curEntry) {
		this.curEntry = curEntry;
		return this;
	}

	public String getOrigin() {
		return origin;
	}

	public Context setOrigin(String origin) {
		this.origin = origin;
		return this;
	}

	public double getOriginTotalQps() {
		return getOriginNode() == null ? 0 : getOriginNode().totalQps();
	}

	public double getOriginBlockQps() {
		return getOriginNode() == null ? 0 : getOriginNode().blockQps();
	}

	public double getOriginPassReqQps() {
		return getOriginNode() == null ? 0 : getOriginNode().successQps();
	}

	public double getOriginPassQps() {
		return getOriginNode() == null ? 0 : getOriginNode().passQps();
	}

	public long getOriginTotalRequest() {
		return getOriginNode() == null ? 0 : getOriginNode().totalRequest();
	}

	public long getOriginBlockRequest() {
		return getOriginNode() == null ? 0 : getOriginNode().blockRequest();
	}

	public double getOriginAvgRt() {
		return getOriginNode() == null ? 0 : getOriginNode().avgRt();
	}

	public int getOriginCurThreadNum() {
		return getOriginNode() == null ? 0 : getOriginNode().curThreadNum();
	}

	public DefaultNode getEntranceNode() {
		return entranceNode;
	}

	/**
	 * @return 获取当前节点的parent节点
	 */
	public Node getLastNode() {
		if (curEntry != null && curEntry.getLastNode() != null) {
			return curEntry.getLastNode();
		} else {
			return entranceNode;
		}
	}

	public Node getOriginNode() {
		return curEntry == null ? null : curEntry.getOriginNode();
	}

	@Override
	public String toString() {
		return "Context{" +
				"name='" + name + '\'' +
				", entranceNode=" + entranceNode +
				", curEntry=" + curEntry +
				", origin='" + origin + '\'' +
				", async=" + async +
				'}';
	}
}
