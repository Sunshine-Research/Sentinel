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
package com.alibaba.csp.sentinel.property;

/**
 * 持有了当前配置的值，并且负责在配置需要更新时，通知所有{@link PropertyListener}
 * 需要注意的是每次{@link #updateValue(Object newValue)}调用都需要通知listeners，仅当{@code newValue}不等于旧值是，才会发生替换
 */
public interface SentinelProperty<T> {

	/**
	 * 将属性更新listener添加到Sentinel属性中
	 * Sentinel属性支持多listener
	 * @param listener 需要添加的listener
	 */
	void addListener(PropertyListener<T> listener);

	/**
	 * 移除Sentinel属性listener
	 * @param listener 需要移除的listener
	 */
	void removeListener(PropertyListener<T> listener);

	/**
	 * 更新属性的值，并通知所有的listener
	 * @param newValue 需要更新的值
	 * @return 如果属性中的值已经被更新，返回true，否则，返回false
	 */
	boolean updateValue(T newValue);
}
