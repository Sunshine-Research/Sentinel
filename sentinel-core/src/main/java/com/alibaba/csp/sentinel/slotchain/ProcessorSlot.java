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
 * 一些存储过程的容器，以及完成过程时的通知方式
 */
public interface ProcessorSlot<T> {

    /**
	 * slot入口
	 * @param context         当前上下文
	 * @param resourceWrapper 当前resource
	 * @param param           泛型参数，通常是{@link com.alibaba.csp.sentinel.node.Node}
	 * @param count           需要获取的token数量
	 * @param prioritized     entry是否需要开启优先级策略whether the entry is prioritized
	 * @param args            调用的自定义参数
	 * @throws Throwable 阻塞异常或者其他未知错误
     */
    void entry(Context context, ResourceWrapper resourceWrapper, T param, int count, boolean prioritized,
               Object... args) throws Throwable;

    /**
	 * {@link #entry(Context, ResourceWrapper, Object, int, boolean, Object...)}方法的结束
	 * @param context         当前上下文
	 * @param resourceWrapper 当前resource
	 * @param obj             相关对象，比如Node
	 * @param count           需要获取的token数量
	 * @param prioritized     entry是否需要开启优先级策略whether the entry is prioritized
	 * @param args            调用的自定义参数
	 * @throws Throwable 阻塞异常或者其他未知错误
     */
    void fireEntry(Context context, ResourceWrapper resourceWrapper, Object obj, int count, boolean prioritized,
                   Object... args) throws Throwable;

    /**
	 * 退出slot
	 * @param context         当前上下文
	 * @param resourceWrapper 当前resource
	 * @param count           需要获取的token数量
	 * @param args            调用的自定义参数
     */
    void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args);

    /**
	 * {@link #exit(Context, ResourceWrapper, int, Object...)}方法的完成
	 * @param context         当前上下文
	 * @param resourceWrapper 当前resource
	 * @param count           需要获取的token数量
	 * @param args            调用的自定义参数
     */
    void fireExit(Context context, ResourceWrapper resourceWrapper, int count, Object... args);
}
