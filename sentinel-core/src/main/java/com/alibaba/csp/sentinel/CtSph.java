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
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.MethodResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.SlotChainProvider;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * {@inheritDoc}
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 * @see Sph
 */
public class CtSph implements Sph {

	private static final Object[] OBJECTS0 = new Object[0];
	private static final Object LOCK = new Object();
	/**
	 * 通过{@link ResourceWrapper#equals(Object)}进行比较，判断相等的资源会共享同一个{@link ProcessorSlotChain}
	 * 不论是否在同一个{@link Context}
	 */
	private static volatile Map<ResourceWrapper, ProcessorSlotChain> chainMap = new HashMap<ResourceWrapper, ProcessorSlotChain>();

	/**
	 * Get current size of created slot chains.
	 * @return size of created slot chains
	 * @since 0.2.0
	 */
	public static int entrySize() {
		return chainMap.size();
	}

	/**
	 * Reset the slot chain map. Only for internal test.
	 * @since 0.2.0
	 */
	static void resetChainMap() {
		chainMap.clear();
	}

	/**
	 * Only for internal test.
	 * @since 0.2.0
	 */
	static Map<ResourceWrapper, ProcessorSlotChain> getChainMap() {
		return chainMap;
	}

	private AsyncEntry asyncEntryWithNoChain(ResourceWrapper resourceWrapper, Context context) {
		AsyncEntry entry = new AsyncEntry(resourceWrapper, null, context);
		entry.initAsyncContext();
		// The async entry will be removed from current context as soon as it has been created.
		entry.cleanCurrentEntryInLocal();
		return entry;
	}

	private AsyncEntry asyncEntryWithPriorityInternal(ResourceWrapper resourceWrapper, int count, boolean prioritized,
													  Object... args) throws BlockException {
		Context context = ContextUtil.getContext();
		if (context instanceof NullContext) {
			// The {@link NullContext} indicates that the amount of context has exceeded the threshold,
			// so here init the entry only. No rule checking will be done.
			return asyncEntryWithNoChain(resourceWrapper, context);
		}
		if (context == null) {
			// Using default context.
			context = MyContextUtil.myEnter(Constants.CONTEXT_DEFAULT_NAME, "", resourceWrapper.getType());
		}

		// Global switch is turned off, so no rule checking will be done.
		if (!Constants.ON) {
			return asyncEntryWithNoChain(resourceWrapper, context);
		}

		ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);

		// Means processor cache size exceeds {@link Constants.MAX_SLOT_CHAIN_SIZE}, so no rule checking will be done.
		if (chain == null) {
			return asyncEntryWithNoChain(resourceWrapper, context);
		}

		AsyncEntry asyncEntry = new AsyncEntry(resourceWrapper, chain, context);
		try {
			chain.entry(context, resourceWrapper, null, count, prioritized, args);
			// Initiate the async context only when the entry successfully passed the slot chain.
			asyncEntry.initAsyncContext();
			// The asynchronous call may take time in background, and current context should not be hanged on it.
			// So we need to remove current async entry from current context.
			asyncEntry.cleanCurrentEntryInLocal();
		} catch (BlockException e1) {
			// When blocked, the async entry will be exited on current context.
			// The async context will not be initialized.
			asyncEntry.exitForContext(context, count, args);
			throw e1;
		} catch (Throwable e1) {
			// This should not happen, unless there are errors existing in Sentinel internal.
			// When this happens, async context is not initialized.
			RecordLog.warn("Sentinel unexpected exception in asyncEntryInternal", e1);

			asyncEntry.cleanCurrentEntryInLocal();
		}
		return asyncEntry;
	}

	private AsyncEntry asyncEntryInternal(ResourceWrapper resourceWrapper, int count, Object... args)
			throws BlockException {
		return asyncEntryWithPriorityInternal(resourceWrapper, count, false, args);
	}

	/**
	 * 根据优先级申请entry
	 * @param resourceWrapper resource包装对象
	 * @param count           申请的令牌数量
	 * @param prioritized     是否开启优先级
	 * @param args            开发者提供的参数
	 * @return 申请结果entry
	 * @throws BlockException 触发阈值限制，抛出阻塞异常
	 */
	private Entry entryWithPriority(ResourceWrapper resourceWrapper, int count, boolean prioritized, Object... args)
			throws BlockException {
		Context context = ContextUtil.getContext();
		// 如果当前线程使用的是NullContext，证明已经不会被规则控制
		if (context instanceof NullContext) {
			// 只实例化entry即可，无需校验规则
			return new CtEntry(resourceWrapper, null, context);
		}
		// 当前线程还没有上线文，使用默认的上下文
		if (context == null) {
			context = MyContextUtil.myEnter(Constants.CONTEXT_DEFAULT_NAME, "", resourceWrapper.getType());
		}

		// Sentinel的全局开关关闭，也不会进行规则校验
		if (!Constants.ON) {
			return new CtEntry(resourceWrapper, null, context);
		}
		// 此时已经证明了有Context
		// 创建ProcessorSlot
		ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);


		// chain为null意味着已经超过slot chain的总数上限，不会进行规则校验
		// 创建Entry之后直接返回
		if (chain == null) {
			return new CtEntry(resourceWrapper, null, context);
		}

		Entry e = new CtEntry(resourceWrapper, chain, context);
		try {
			// 在链上申请，相当于过一遍Sentinel
			// 由于链上插满了可扩展的槽，比如流控，降级都是可扩展的槽，此方法相当于按照槽的顺序过滤了一遍
			chain.entry(context, resourceWrapper, null, count, prioritized, args);
		} catch (BlockException e1) {
			// 触发规则限制，抛出异常
			e.exit(count, args);
			throw e1;
		} catch (Throwable e1) {
			// Sentinel内部错误，比较少见，记录下来，用于反馈
			RecordLog.info("Sentinel unexpected exception", e1);
		}
		return e;
	}

	/**
	 * 对当前resource进行所有规则的过滤
	 * 每个独立的resource会使用一个{@link ProcessorSlot}来进行规则校验，相同的resource会全局使用同一个{@link ProcessorSlot}
	 * 需要注意的是，{@link ProcessorSlot}的总数一定不能超过{@link Constants#MAX_SLOT_CHAIN_SIZE}的值，也就是6000个。
	 * 否则就不会进行规则校验
	 * 在这个条件下，所有的请求会直接通过，不会进行检查或者抛出异常
	 * @param resourceWrapper resource的包装类
	 * @param count           需要申请的令牌数量
	 * @param args            开发者提供的参数
	 * @return {@link Entry}  本次调用代表对象
	 * @throws BlockException 如果超出了其中一个规则的阈值，抛出阻塞异常
	 */
	public Entry entry(ResourceWrapper resourceWrapper, int count, Object... args) throws BlockException {
		return entryWithPriority(resourceWrapper, count, false, args);
	}

	/**
	 * 获取resource的ProcessorSlotChain，如果当前resource没有关联到ProcessorSlotChain，会创建一个新的ProcessorSlotChain
	 * <p>
	 * 如果在{@link ResourceWrapper#equals(Object)}判断相等的情况下，相同的resource会全局共用{@link ProcessorSlotChain}，不论是否在同一个Context中
	 * <p>
	 * 需要注意的的，{@link ProcessorSlot}数量不能超过{@link Constants#MAX_SLOT_CHAIN_SIZE}阈值
	 * 其他情况下会返回null
	 * @param resourceWrapper 目前resource
	 * @return resource关联的{@link ProcessorSlotChain}
	 */
	ProcessorSlot<Object> lookProcessChain(ResourceWrapper resourceWrapper) {
		// 从缓存中获取当前resource关联的ProcessorSlotChain
		ProcessorSlotChain chain = chainMap.get(resourceWrapper);
		// 如果当前resource没有关联，同步下创建一个
		if (chain == null) {
			synchronized (LOCK) {
				chain = chainMap.get(resourceWrapper);
				// 双重校验
				if (chain == null) {
					// entry大小限制，不能超过SLOT_CHAIN的阈值
					if (chainMap.size() >= Constants.MAX_SLOT_CHAIN_SIZE) {
						return null;
					}
					// 使用SlotChainProvider创建一个新的slot chain
					chain = SlotChainProvider.newSlotChain();
					// 迁移并缓存新创建的slot chain
					Map<ResourceWrapper, ProcessorSlotChain> newMap = new HashMap<ResourceWrapper, ProcessorSlotChain>(
							chainMap.size() + 1);
					newMap.putAll(chainMap);
					newMap.put(resourceWrapper, chain);
					chainMap = newMap;
				}
			}
		}
		return chain;
	}

	@Override
	public Entry entry(String name) throws BlockException {
		StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
		return entry(resource, 1, OBJECTS0);
	}

	@Override
	public Entry entry(Method method) throws BlockException {
		MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
		return entry(resource, 1, OBJECTS0);
	}

	@Override
	public Entry entry(Method method, EntryType type) throws BlockException {
		MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
		return entry(resource, 1, OBJECTS0);
	}

	@Override
	public Entry entry(String name, EntryType type) throws BlockException {
		StringResourceWrapper resource = new StringResourceWrapper(name, type);
		return entry(resource, 1, OBJECTS0);
	}

	@Override
	public Entry entry(Method method, EntryType type, int count) throws BlockException {
		MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
		return entry(resource, count, OBJECTS0);
	}

	@Override
	public Entry entry(String name, EntryType type, int count) throws BlockException {
		StringResourceWrapper resource = new StringResourceWrapper(name, type);
		return entry(resource, count, OBJECTS0);
	}

	@Override
	public Entry entry(Method method, int count) throws BlockException {
		MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
		return entry(resource, count, OBJECTS0);
	}

	@Override
	public Entry entry(String name, int count) throws BlockException {
		StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
		return entry(resource, count, OBJECTS0);
	}

	@Override
	public Entry entry(Method method, EntryType type, int count, Object... args) throws BlockException {
		MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
		return entry(resource, count, args);
	}

	@Override
	public Entry entry(String name, EntryType type, int count, Object... args) throws BlockException {
		// 由于传入的是资源名称，使用StringResourceWrapper
		StringResourceWrapper resource = new StringResourceWrapper(name, type);
		// 进行资源上报
		return entry(resource, count, args);
	}

	@Override
	public AsyncEntry asyncEntry(String name, EntryType type, int count, Object... args) throws BlockException {
		StringResourceWrapper resource = new StringResourceWrapper(name, type);
		return asyncEntryInternal(resource, count, args);
	}

	@Override
	public Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized) throws BlockException {
		StringResourceWrapper resource = new StringResourceWrapper(name, type);
		return entryWithPriority(resource, count, prioritized);
	}

	@Override
	public Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized, Object... args)
			throws BlockException {
		StringResourceWrapper resource = new StringResourceWrapper(name, type);
		return entryWithPriority(resource, count, prioritized, args);
	}

	/**
	 * This class is used for skip context name checking.
	 */
	private final static class MyContextUtil extends ContextUtil {
		static Context myEnter(String name, String origin, EntryType type) {
			return trueEnter(name, origin);
		}
	}
}
