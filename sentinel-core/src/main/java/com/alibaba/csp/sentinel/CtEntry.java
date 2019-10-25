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
import com.alibaba.csp.sentinel.context.NullContext;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;

/**
 * Linked entry within current context.
 * @author jialiang.linjl
 * @author Eric Zhao
 */
class CtEntry extends Entry {

	protected Entry parent = null;
	protected Entry child = null;

	protected ProcessorSlot<Object> chain;
	protected Context context;

	CtEntry(ResourceWrapper resourceWrapper, ProcessorSlot<Object> chain, Context context) {
		super(resourceWrapper);
		this.chain = chain;
		this.context = context;

		setUpEntryFor(context);
	}

	private void setUpEntryFor(Context context) {
		// The entry should not be associated to NullContext.
		if (context instanceof NullContext) {
			return;
		}
		this.parent = context.getCurEntry();
		if (parent != null) {
			((CtEntry) parent).child = this;
		}
		context.setCurEntry(this);
	}

	@Override
	public void exit(int count, Object... args) throws ErrorEntryFreeException {
		trueExit(count, args);
	}

	/**
	 * 给定entry退出当前上下文
	 * @param context entry申请时加入的上下文
	 * @param count   释放的令牌数量
	 * @param args    用户的请求参数
	 * @throws ErrorEntryFreeException entry不匹配，抛出释放异常
	 */
	protected void exitForContext(Context context, int count, Object... args) throws ErrorEntryFreeException {
		// 存在上下文
		if (context != null) {
			// 如果是NullContext，无需进行清理，直接返回
			if (context instanceof NullContext) {
				return;
			}
			// 如果上下文中，当前处理的节点，并不是给定节点
			if (context.getCurEntry() != this) {
				// 获取上下文中当前entry的resource name
				String curEntryNameInContext = context.getCurEntry() == null ? null : context.getCurEntry().getResourceWrapper().getName();
				// 清理上一个entry的调用栈
				CtEntry e = (CtEntry) context.getCurEntry();
				while (e != null) {
					e.exit(count, args);
					e = (CtEntry) e.parent;
				}
				String errorMessage = String.format("The order of entry exit can't be paired with the order of entry"
						+ ", current entry in context: <%s>, but expected: <%s>", curEntryNameInContext, resourceWrapper.getName());
				// 抛出释放异常错误
				throw new ErrorEntryFreeException(errorMessage);
			} else {
				// 如果存在ProcessorSlot，使用ProcessorSlot释放给定entry
				if (chain != null) {
					chain.exit(context, resourceWrapper, count, args);
				}
				// 恢复调用栈
				context.setCurEntry(parent);
				// 清理parent entry的尾部entry
				if (parent != null) {
					((CtEntry) parent).child = null;
				}
				// 如果没有parent entry
				if (parent == null) {
					// 确认是否使用默认的上下文
					// 默认上下文是自动进入，自动退出的
					if (ContextUtil.isDefaultContext(context)) {
						ContextUtil.exit();
					}
				}
				// 清理当前entry在上下文中的引用，避免发生重复释放
				clearEntryContext();
			}
		}
	}

	/**
	 * 清理当前entry在上下文中的引用，避免发生重复释放
	 */
	protected void clearEntryContext() {
		this.context = null;
	}

	@Override
	protected Entry trueExit(int count, Object... args) throws ErrorEntryFreeException {
		exitForContext(context, count, args);

		return parent;
	}

	@Override
	public Node getLastNode() {
		return parent == null ? null : parent.getCurNode();
	}
}