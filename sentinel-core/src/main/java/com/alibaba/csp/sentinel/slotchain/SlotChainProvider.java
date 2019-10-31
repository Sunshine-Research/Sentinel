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

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slots.DefaultSlotChainBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 用于创建slot chain的提供者
 * 通过构造slot chain建造器API来构造
 * @since 0.2.0
 */
public final class SlotChainProvider {

    private static volatile SlotChainBuilder builder = null;

    private static final ServiceLoader<SlotChainBuilder> LOADER = ServiceLoader.load(SlotChainBuilder.class);

    /**
	 * load和pick进行不是线程安全的，因为是通过{@code lookProcessChain}调用的，并且在创建slot chain的时候已经加锁了，所以还OK
	 * @return 创建的slot chain
     */
    public static ProcessorSlotChain newSlotChain() {
		// 可以使用建造方法构建的情况下，使用建造方法构造
        if (builder != null) {
            return builder.build();
        }
		// 没有建造者，会创建一个建造者
		// 使用自定义建造者或者默认建造者
        resolveSlotChainBuilder();
		// 如果仍未空，可能在加载builder中出现异常状态，使用DefaultSlotChainBuilder
        if (builder == null) {
            RecordLog.warn("[SlotChainProvider] Wrong state when resolving slot chain builder, using default");
            builder = new DefaultSlotChainBuilder();
        }
		// 返回建造者构建的slot chain
        return builder.build();
    }

	/**
	 * 加载slot chain builder，加载自定义builder或者默认的builder
	 */
	private static void resolveSlotChainBuilder() {
		List<SlotChainBuilder> list = new ArrayList<SlotChainBuilder>();
		boolean hasOther = false;
		// 遍历通过SPI加载的slot chain
		for (SlotChainBuilder builder : LOADER) {
			if (builder.getClass() != DefaultSlotChainBuilder.class) {
				hasOther = true;
				list.add(builder);
			}
		}
		// 获取自定义的DefaultSlotChainBuilder建造者
		if (hasOther) {
			builder = list.get(0);
		} else {
			// 没有自定义的builder，使用默认的DefaultSlotChainBuilder进行构造
			builder = new DefaultSlotChainBuilder();
		}

		RecordLog.info("[SlotChainProvider] Global slot chain builder resolved: "
				+ builder.getClass().getCanonicalName());
	}

    private SlotChainProvider() {}
}
