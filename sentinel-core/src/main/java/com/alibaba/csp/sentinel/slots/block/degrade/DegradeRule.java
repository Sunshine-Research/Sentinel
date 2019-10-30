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
package com.alibaba.csp.sentinel.slots.block.degrade;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 降级用于resource处于不稳定的状态，这些resource将在下一个定义的时间范围内降级
 * 有两种方式可以确认resource是否处于稳定状态：
 * 平均响应时间（{@code DEGRADE_GRADE_RT}）：当平均响应时间超出阈值（count' in 'DegradeRule', ms级别）
 * resource将会进入quasi-degraded状态，如果接下来的5次请求的响应时间依然超出阈值，resource就会被降级，
 * 意味着在下一个窗口，所有对此resource的请求都会被阻塞住
 * 异常比率：异常的QPS比率超过了阈值，所有对此resource的请求都会被阻塞住
 */
public class DegradeRule extends AbstractRule {

	/**
	 * 平均响应时间最大超出阈值个数
	 * 用于在超出平均响应时间后，过后的阈值个数请求，如果继续超过平均响应时间，则开启降级策略
	 */
	private static final int RT_MAX_EXCEED_N = 5;

	/**
	 * 调度降级恢复任务的线程池
	 */
	@SuppressWarnings("PMD.ThreadPoolCreationRule")
	private static ScheduledExecutorService pool = Executors.newScheduledThreadPool(
			Runtime.getRuntime().availableProcessors(), new NamedThreadFactory("sentinel-degrade-reset-task", true));
	/**
	 * 进行降级规则检查之前的乐观锁
	 */
	private final AtomicBoolean cut = new AtomicBoolean(false);
	/**
	 * 响应时间阈值或者异常比率阈值数量
	 */
	private double count;
	/**
	 * 降级恢复时间，单位s
	 */
	private int timeWindow;
	/**
	 * 降级策略
	 * 0：平均响应时间
	 * 1：异常比率
	 */
	private int grade = RuleConstant.DEGRADE_GRADE_RT;
	/**
	 * 平均响应时间超过设定阈值后的请求个数计数器
	 */
	private AtomicLong passCount = new AtomicLong(0);

	public DegradeRule() {
	}

	public DegradeRule(String resourceName) {
		setResource(resourceName);
	}

	public int getGrade() {
		return grade;
	}

	public DegradeRule setGrade(int grade) {
		this.grade = grade;
		return this;
	}

	public double getCount() {
		return count;
	}

	public DegradeRule setCount(double count) {
		this.count = count;
		return this;
	}

	private boolean isCut() {
		return cut.get();
	}

	private void setCut(boolean cut) {
		this.cut.set(cut);
	}

	public AtomicLong getPassCount() {
		return passCount;
	}

	public int getTimeWindow() {
		return timeWindow;
	}

	public DegradeRule setTimeWindow(int timeWindow) {
		this.timeWindow = timeWindow;
		return this;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DegradeRule)) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}

		DegradeRule that = (DegradeRule) o;

		if (count != that.count) {
			return false;
		}
		if (timeWindow != that.timeWindow) {
			return false;
		}
		if (grade != that.grade) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int result = super.hashCode();
		result = 31 * result + new Double(count).hashCode();
		result = 31 * result + timeWindow;
		result = 31 * result + grade;
		return result;
	}

	/**
	 * 规则校验
	 * @param context      当前上下文
	 * @param node         当前节点
	 * @param acquireCount 需要获取的token数量
	 * @param args         自定义参数
	 * @return 是否通过降级策略
	 */
	@Override
	public boolean passCheck(Context context, DefaultNode node, int acquireCount, Object... args) {
		// 获取乐观锁
		if (cut.get()) {
			return false;
		}
		// 获取ClusterNode，用于提供Cluster数据信息
		ClusterNode clusterNode = ClusterBuilderSlot.getClusterNode(this.getResource());
		if (clusterNode == null) {
			return true;
		}

		if (grade == RuleConstant.DEGRADE_GRADE_RT) {
			// 响应时间降级策略
			// 从ClusterNode获取实时统计的成功请求的平均响应时间
			double rt = clusterNode.avgRt();
			// 如果计算出的平均响应时间小于阈值，判定通过
			if (rt < this.count) {
				passCount.set(0);
				return true;
			}

			// 否则一经超过阈值，将启动降级策略
			// 如果接下来的五次请求依然超出了阈值，才会走最后的降级策略
			if (passCount.incrementAndGet() < RT_MAX_EXCEED_N) {
				// 接下来的五次请求，如果没有超过阈值，那么依然会判定通过降级检查
				return true;
			}
		} else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO) {
			// 异常比率策略
			// 获取当前resource的发生异常QPS
			double exception = clusterNode.exceptionQps();
			// 获取当前resource的成功请求QPS
			double success = clusterNode.successQps();
			// 获取当前resource的总QPS
			double total = clusterNode.totalQps();
			// 如果通总QPS小于阈值上线，判定通过降级规则检查
			if (total < RT_MAX_EXCEED_N) {
				return true;
			}
			// 真正成功的请求数=成功请求数-异常请求数
			double realSuccess = success - exception;
			// 如果没有真正成功的请求数，并且异常数量小于设定的阈值
			if (realSuccess <= 0 && exception < RT_MAX_EXCEED_N) {
				// 判定通过降级规则检查
				return true;
			}
			// 如果异常比率占成功比率，没有超过设定的阈值
			if (exception / success < count) {
				// 判定通过降级规则检查
				return true;
			}
		} else if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
			// 获取当前resource出现的异常数量，统计的是每分钟的数量
			double exception = clusterNode.totalException();
			// 如果resource每分钟的异常数量没有超出设定的阈值
			if (exception < count) {
				// 判定通过降级规则检查
				return true;
			}
		}
		// 总结一下，只有在以下情况会走到这里
		// 平均响应时间：计算的平均响应时间大于阈值，接下来的五次请求也已经超过缓冲的阈值
		// 异常比率：每秒钟出现的异常数量已经超过设定的阈值，有真正成功的请求，或者异常数量超过缓冲的异常数量
		// 异常数量：每分钟出现的异常数量已经超过阈值
		// 首先乐观锁设置为不可用
		if (cut.compareAndSet(false, true)) {
			// 需要进行重置
			ResetTask resetTask = new ResetTask(this);
			// 根据降级时间进行恢复
			pool.schedule(resetTask, timeWindow, TimeUnit.SECONDS);
		}

		return false;
	}

	@Override
	public String toString() {
		return "DegradeRule{" +
				"resource=" + getResource() +
				", grade=" + grade +
				", count=" + count +
				", limitApp=" + getLimitApp() +
				", timeWindow=" + timeWindow +
				"}";
	}

	private static final class ResetTask implements Runnable {

		private DegradeRule rule;

		ResetTask(DegradeRule rule) {
			this.rule = rule;
		}

		@Override
		public void run() {
			// 计数器归零，释放乐观锁
			rule.getPassCount().set(0);
			rule.cut.set(false);
		}
	}
}

