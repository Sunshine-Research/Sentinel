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
package com.alibaba.csp.sentinel.slotchain;

import com.alibaba.csp.sentinel.context.Context;

/**
 * 默认的ProcessorSlot调用链
 */
public class DefaultProcessorSlotChain extends ProcessorSlotChain {

	/**
	 * 构建head节点
	 */
	AbstractLinkedProcessorSlot<?> first = new AbstractLinkedProcessorSlot<Object>() {

		@Override
		public void entry(Context context, ResourceWrapper resourceWrapper, Object t, int count, boolean prioritized, Object... args)
				throws Throwable {
			super.fireEntry(context, resourceWrapper, t, count, prioritized, args);
		}

		@Override
		public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
			super.fireExit(context, resourceWrapper, count, args);
		}

	};
	/**
	 * 初始化时，tail节点也是head节点
	 */
	AbstractLinkedProcessorSlot<?> end = first;

    @Override
    public void addFirst(AbstractLinkedProcessorSlot<?> protocolProcessor) {
		// 添加到first节点之后
        protocolProcessor.setNext(first.getNext());
        first.setNext(protocolProcessor);
		// 如果当前责任链只有一个first节点，那么添加的protocolProcessor将会成为end节点
        if (end == first) {
            end = protocolProcessor;
        }
    }

    @Override
    public void addLast(AbstractLinkedProcessorSlot<?> protocolProcessor) {
		// end节点的下一个节点设为给定节点，同时将end节点设置为给定节点
		// 这么写是避免链断掉
        end.setNext(protocolProcessor);
        end = protocolProcessor;
    }

	/**
	 * 和{@link #addLast(AbstractLinkedProcessorSlot)}方法一样
	 * @param next 需要添加的processor
     */
    @Override
    public void setNext(AbstractLinkedProcessorSlot<?> next) {
        addLast(next);
    }

    @Override
    public AbstractLinkedProcessorSlot<?> getNext() {
        return first.getNext();
    }

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, Object t, int count, boolean prioritized, Object... args)
        throws Throwable {
        first.transformEntry(context, resourceWrapper, t, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        first.exit(context, resourceWrapper, count, args);
    }

}
