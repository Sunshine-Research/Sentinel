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

import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.lang.reflect.Method;

/**
 * 获取用于资源保护的{@link Entry}
 * 如果触发了阻塞标准，抛出{@link BlockException}异常
 * 如果成功获取到{@link Entry}，证明允许调用通过
 */
public interface Sph {

	/**
	 * 创建一个受保护的资源
	 * @param name 需要保护的资源名称
	 * @return 资源保护凭证
	 * @throws BlockException 触发阻塞标准，抛出异常
	 */
	Entry entry(String name) throws BlockException;

	/**
	 * Create a protected method.
	 * @param method the protected method
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(Method method) throws BlockException;

	/**
	 * Create a protected method.
	 * @param method the protected method
	 * @param count  the count that the resource requires
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(Method method, int count) throws BlockException;

	/**
	 * Create a protected resource.
	 * @param name  the unique string for the resource
	 * @param count the count that the resource requires
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(String name, int count) throws BlockException;

	/**
	 * Create a protected method.
	 * @param method the protected method
	 * @param type   the resource is an inbound or an outbound method. This is used
	 *               to mark whether it can be blocked when the system is unstable
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(Method method, EntryType type) throws BlockException;

	/**
	 * Create a protected resource.
	 * @param name the unique name for the protected resource
	 * @param type the resource is an inbound or an outbound method. This is used
	 *             to mark whether it can be blocked when the system is unstable
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(String name, EntryType type) throws BlockException;

	/**
	 * Create a protected method.
	 * @param method the protected method
	 * @param type   the resource is an inbound or an outbound method. This is used
	 *               to mark whether it can be blocked when the system is unstable
	 * @param count  the count that the resource requires
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(Method method, EntryType type, int count) throws BlockException;

	/**
	 * Create a protected resource.
	 * @param name  the unique name for the protected resource
	 * @param type  the resource is an inbound or an outbound method. This is used
	 *              to mark whether it can be blocked when the system is unstable
	 * @param count the count that the resource requires
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(String name, EntryType type, int count) throws BlockException;

	/**
	 * Create a protected resource.
	 * @param method the protected method
	 * @param type   the resource is an inbound or an outbound method. This is used
	 *               to mark whether it can be blocked when the system is unstable
	 * @param count  the count that the resource requires
	 * @param args   the parameters of the method. It can also be counted by setting
	 *               hot parameter rule
	 * @return entry get.
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(Method method, EntryType type, int count, Object... args) throws BlockException;

	/**
	 * Create a protected resource.
	 * @param name  the unique name for the protected resource
	 * @param type  the resource is an inbound or an outbound method. This is used
	 *              to mark whether it can be blocked when the system is unstable
	 * @param count the count that the resource requires
	 * @param args  the parameters of the method. It can also be counted by setting hot parameter rule
	 * @return entry get
	 * @throws BlockException if the block criteria is met
	 */
	Entry entry(String name, EntryType type, int count, Object... args) throws BlockException;

	/**
	 * Create a protected asynchronous resource.
	 * @param name  the unique name for the protected resource
	 * @param type  the resource is an inbound or an outbound method. This is used
	 *              to mark whether it can be blocked when the system is unstable
	 * @param count the count that the resource requires
	 * @param args  the parameters of the method. It can also be counted by setting hot parameter rule
	 * @return created asynchronous entry
	 * @throws BlockException if the block criteria is met
	 * @since 0.2.0
	 */
	AsyncEntry asyncEntry(String name, EntryType type, int count, Object... args) throws BlockException;

	/**
	 * Create a protected resource with priority.
	 * @param name        the unique name for the protected resource
	 * @param type        the resource is an inbound or an outbound method. This is used
	 *                    to mark whether it can be blocked when the system is unstable
	 * @param count       the count that the resource requires
	 * @param prioritized whether the entry is prioritized
	 * @return entry get
	 * @throws BlockException if the block criteria is met
	 * @since 1.4.0
	 */
	Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized) throws BlockException;

	/**
	 * Create a protected resource with priority.
	 * @param name        the unique name for the protected resource
	 * @param type        the resource is an inbound or an outbound method. This is used
	 *                    to mark whether it can be blocked when the system is unstable
	 * @param count       the count that the resource requires
	 * @param prioritized whether the entry is prioritized
	 * @param args        the parameters of the method. It can also be counted by setting hot parameter
	 *                    rule
	 * @return entry get
	 * @throws BlockException if the block criteria is met
	 * @since 1.5.0
	 */
	Entry entryWithPriority(String name, EntryType type, int count, boolean prioritized, Object... args)
			throws BlockException;
}
