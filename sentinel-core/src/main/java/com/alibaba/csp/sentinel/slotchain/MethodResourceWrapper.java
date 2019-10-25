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

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.util.IdUtil;
import com.alibaba.csp.sentinel.util.MethodUtil;

import java.lang.reflect.Method;

/**
 * 方法调用的ResourceWrapper
 */
public class MethodResourceWrapper extends ResourceWrapper {

	private transient Method method;

	/**
	 * 存储调用方法资源的Resource Wrapper
	 * @param method resource method name
	 * @param type   流向类型
	 */
	public MethodResourceWrapper(Method method, EntryType type) {
		this.method = method;
		this.name = MethodUtil.resolveMethodName(method);
		this.type = type;
	}

	@Override
	public String getName() {
		return name;
	}

	public Method getMethod() {
		return method;
	}

	@Override
	public String getShowName() {
		return IdUtil.truncate(this.name);
	}

	@Override
	public EntryType getType() {
		return type;
	}

}
