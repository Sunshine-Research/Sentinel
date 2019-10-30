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
package com.alibaba.csp.sentinel.cluster.flow.statistic.limit;

import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.LongAdder;
import com.alibaba.csp.sentinel.slots.statistic.base.UnaryLeapArray;
import com.alibaba.csp.sentinel.util.AssertUtil;

import java.util.List;

/**
 * 请求限制器
 * @since 1.4.1
 */
public class RequestLimiter {
	/**
	 * 滑动窗口数据
	 */
    private final LeapArray<LongAdder> data;
	/**
	 * 允许通过的QPS
	 */
	private double qpsAllowed;

    public RequestLimiter(double qpsAllowed) {
        this(new UnaryLeapArray(10, 1000), qpsAllowed);
    }

    RequestLimiter(LeapArray<LongAdder> data, double qpsAllowed) {
        AssertUtil.isTrue(qpsAllowed >= 0, "max allowed QPS should > 0");
        this.data = data;
        this.qpsAllowed = qpsAllowed;
	}

	/**
	 * 当前窗口的值+1
	 */
	public void increment() {
		data.currentWindow().value().increment();
	}

	/**
	 * 获取当前窗口指定数量的token
	 * @param x 需要获取的token
	 */
	public void add(int x) {
		data.currentWindow().value().add(x);
	}

	/**
	 * 获取
	 * @return
	 */
	public long getSum() {
		// 切换到当前窗口
		data.currentWindow();
		long success = 0;
		// 获取当前窗口的数据
		List<LongAdder> list = data.values();
		// 计算每个子窗口的数据之和
		for (LongAdder window : list) {
			success += window.sum();
		}
		return success;
	}

	/**
	 * 获取QPS
	 * 计算公式=总数/时间间隔
	 * @return QPS
	 */
	public double getQps() {
		return getSum() / data.getIntervalInSecond();
	}

	/**
	 * 获取允许通过的QPS
	 * @return
	 */
	public double getQpsAllowed() {
		return qpsAllowed;
	}

	/**
	 * 判断是否可以通过
	 * @return
	 */
	public boolean canPass() {
		// 如果当前QPS+1没有超过设定的允许通过阈值，判定可通过
		return getQps() + 1 <= qpsAllowed;
	}

    public RequestLimiter setQpsAllowed(double qpsAllowed) {
        this.qpsAllowed = qpsAllowed;
        return this;
	}

	/**
	 * 尝试通过
	 * @return 通过结果
	 */
	public boolean tryPass() {
		if (canPass()) {
			// 可以通过，添加1，并返回true
			add(1);
			return true;
		}
		// 不可通过，返回false
		return false;
	}
}
