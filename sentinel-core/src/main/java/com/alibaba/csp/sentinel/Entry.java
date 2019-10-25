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
package com.alibaba.csp.sentinel;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Each {@link SphU}#entry() will return an {@link Entry}. This class holds information of current invocation:<br/>
 * 每次调用{@link SphU}#entry()方法时都会返回{@link Entry}
 * 这个类包含了并发调用时的信息，比如：
 * 申请资源时的创建时间，用于响应时间统计
 * 并发节点，用于resource在并发上下文中的分析
 * 后端Node，用于统计指定的Node，后端Node一般指的是微服务名称
 *
 * 上下文可能会持有parent或child entry来形成树，由于上线总是chinyou持有调用树中当前的entry
 * 所以每次调用{@link Entry#exit()}应修改{@link Context#setCurEntry(Entry)}
 */
public abstract class Entry implements AutoCloseable {

    private static final Object[] OBJECTS0 = new Object[0];
	/**
	 * resource信息
	 */
	protected ResourceWrapper resourceWrapper;
	/**
	 * 申请Entry的时间
	 */
    private long createTime;
	/**
	 * 当前节点
	 */
	private Node curNode;
	/**
	 * 指定的后端服务
     */
    private Node originNode;
	/**
	 * 抛出的异常
	 */
	private Throwable error;

    public Entry(ResourceWrapper resourceWrapper) {
        this.resourceWrapper = resourceWrapper;
        this.createTime = TimeUtil.currentTimeMillis();
    }

    public ResourceWrapper getResourceWrapper() {
        return resourceWrapper;
	}

	/**
	 * 完成当前的entry，并恢复上线文中的元素栈
	 * @throws ErrorEntryFreeException 如果上下文中的entry和当前的entry不匹配，抛出entry释放代码
     */
    public void exit() throws ErrorEntryFreeException {
        exit(1, OBJECTS0);
    }

    public void exit(int count) throws ErrorEntryFreeException {
        exit(count, OBJECTS0);
    }

    /**
     * Equivalent to {@link #exit()}. Support try-with-resources since JDK 1.7.
     *
     * @since 1.5.0
     */
    @Override
    public void close() {
        exit();
    }

    /**
     * Exit this entry. This method should invoke if and only if once at the end of the resource protection.
     *
     * @param count tokens to release.
     * @param args extra parameters
     * @throws ErrorEntryFreeException, if {@link Context#getCurEntry()} is not this entry.
     */
    public abstract void exit(int count, Object... args) throws ErrorEntryFreeException;

    /**
     * Exit this entry.
     *
     * @param count tokens to release.
     * @param args extra parameters
     * @return next available entry after exit, that is the parent entry.
     * @throws ErrorEntryFreeException, if {@link Context#getCurEntry()} is not this entry.
     */
    protected abstract Entry trueExit(int count, Object... args) throws ErrorEntryFreeException;

    /**
     * Get related {@link Node} of the parent {@link Entry}.
     *
     * @return
     */
    public abstract Node getLastNode();

    public long getCreateTime() {
        return createTime;
    }

    public Node getCurNode() {
        return curNode;
    }

    public void setCurNode(Node node) {
        this.curNode = node;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    /**
     * Get origin {@link Node} of the this {@link Entry}.
     *
     * @return origin {@link Node} of the this {@link Entry}, may be null if no origin specified by
     * {@link ContextUtil#enter(String name, String origin)}.
     */
    public Node getOriginNode() {
        return originNode;
    }

    public void setOriginNode(Node originNode) {
        this.originNode = originNode;
    }

}
