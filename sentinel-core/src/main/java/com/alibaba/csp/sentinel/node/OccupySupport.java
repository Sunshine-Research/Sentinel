/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.node;

/**
 * 透支未来token支持接口
 */
public interface OccupySupport {

	/**
	 * 尝试占有较晚滑动窗口的token，如果占领成功，一个在{@link OccupyTimeoutProperty}设置的，少于{@code occupyTimeout}将会返回
	 * 每次占领未来滑动窗口的token，当前线程需要睡眠相应的时间以便进行平滑的QPS，我们不可以无限的占领未来滑动窗口的token
	 * 睡眠时间受到{@link OccupyTimeoutProperty}的{@code occupyTimeout}设置
	 * @param currentTime  当前时间戳
	 * @param acquireCount 需要获取的token数量
	 * @param threshold    QPS阈值
	 * @return 需要进行睡眠等待的时间，如果时间≥{@link OccupyTimeoutProperty}的{@code occupyTimeout}的时间，意味占领失败，请求将会被立即拒绝
	 */
	long tryOccupyNext(long currentTime, int acquireCount, double threshold);

	/**
	 * @return 当期正在等待token的数量
	 */
	long waiting();

	/**
	 * 添加已经占领未来窗口token的请求
	 * @param futureTime   未来token可以被添加上的时间戳
	 * @param acquireCount 需要获取的token数量
	 */
	void addWaitingRequest(long futureTime, int acquireCount);

	/**
	 * 添加已经占领的通过请求，代表着通过的请求占用了未来窗口的token
	 * @param acquireCount 需要获取token的数量
	 */
	void addOccupiedPass(int acquireCount);

	/**
	 * @return 当前已经占领的通过QPS
	 */
	double occupiedPassQps();
}
