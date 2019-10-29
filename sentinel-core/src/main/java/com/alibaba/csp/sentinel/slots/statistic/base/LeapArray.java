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
package com.alibaba.csp.sentinel.slots.statistic.base;

import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sentinel数据统计度量标准基础数据结构
 * 环形数组使用滑动窗口算法来计算数据，每个bucket覆盖了{@code windowLengthInMs}时间间隔
 * 总时间间隔是{@link #intervalInMs}，所以所有的bucket数量是：
 * {@code sampleCount = intervalInMs / windowLengthInMs}.
 */
public abstract class LeapArray<T> {
	/**
	 * 仅用于当前bucket失效
	 */
	private final ReentrantLock updateLock = new ReentrantLock();
	/**
	 * 滑动窗口的时间间隔
	 */
    protected int windowLengthInMs;
	/**
	 * 滑动窗口的数量
	 */
    protected int sampleCount;

    protected final AtomicReferenceArray<WindowWrap<T>> array;
	/**
	 * 统计的时间间隔
	 */
	protected int intervalInMs;

	/**
	 * 根据滑动窗口数量和统计时间间隔计算滑动窗口的时间
	 * @param sampleCount  滑动窗口的数量
	 * @param intervalInMs 统计时间间隔，单位ms
     */
    public LeapArray(int sampleCount, int intervalInMs) {
        AssertUtil.isTrue(sampleCount > 0, "bucket count is invalid: " + sampleCount);
        AssertUtil.isTrue(intervalInMs > 0, "total time interval of the sliding window should be positive");
        AssertUtil.isTrue(intervalInMs % sampleCount == 0, "time span needs to be evenly divided");

        this.windowLengthInMs = intervalInMs / sampleCount;
        this.intervalInMs = intervalInMs;
        this.sampleCount = sampleCount;

        this.array = new AtomicReferenceArray<>(sampleCount);
	}

	/**
	 * @return 获取当前的滑动窗口
     */
    public WindowWrap<T> currentWindow() {
        return currentWindow(TimeUtil.currentTimeMillis());
	}

	/**
	 * @param timeMillis 当前时间戳
	 * @return 一个新的用于数据统计的滑动窗口
     */
    public abstract T newEmptyBucket(long timeMillis);

    /**
     * Reset given bucket to provided start time and reset the value.
     *
     * @param startTime  the start time of the bucket in milliseconds
     * @param windowWrap current bucket
     * @return new clean bucket at given start time
     */
    protected abstract WindowWrap<T> resetWindowTo(WindowWrap<T> windowWrap, long startTime);

	/**
	 * 计算时间戳对应的滑动窗口索引
	 * @param timeMillis 给定时间戳
	 * @return 给定时间所在的滑动窗口
	 */
    private int calculateTimeIdx(/*@Valid*/ long timeMillis) {
        long timeId = timeMillis / windowLengthInMs;
        return (int)(timeId % array.length());
	}

	/**
	 * 计算滑动窗口的起始时间
	 * @param timeMillis 给定时间戳
	 * @return 滑动窗口的起始时间
	 */
    protected long calculateWindowStart(/*@Valid*/ long timeMillis) {
        return timeMillis - timeMillis % windowLengthInMs;
	}

	/**
	 * 根据提供的时间戳获取滑动窗口
	 * @param timeMillis 合法的时间戳
	 * @return 根据提供的时间戳获取滑动窗口，如果时间不合法，返回null
     */
    public WindowWrap<T> currentWindow(long timeMillis) {
        if (timeMillis < 0) {
            return null;
		}
		// 计算时间索引
        int idx = calculateTimeIdx(timeMillis);
		// 计算滑动窗口的起始时间
        long windowStart = calculateWindowStart(timeMillis);

		/*
		 * 从唤醒数组中获取滑动窗口对象
		 * 1. 如果没有此滑动窗口，创建一个新的bucket
		 * 2. 如果bucket是最新的，直接返回此bucket
		 * 3. 如果bucket已经失效，重置当前的bucket并清除所有失效的bucket
         */
        while (true) {
            WindowWrap<T> old = array.get(idx);
            if (old == null) {
                /*
                 *     B0       B1      B2    NULL      B4
                 * ||_______|_______|_______|_______|_______||___
                 * 200     400     600     800     1000    1200  timestamp
                 *                             ^
                 *                          time=888
				 * 如果此处没有滑动窗口，需要在窗口起始位置创建一个新的窗口，并且通过CAS进行设置
				 * 进有一个线程可以成功更新
                 */
                WindowWrap<T> window = new WindowWrap<T>(windowLengthInMs, windowStart, newEmptyBucket(timeMillis));
                if (array.compareAndSet(idx, null, window)) {
					// 返回成功创建的窗口
                    return window;
				} else {
					// 竞争失败，线程会yield()直到窗口可用
                    Thread.yield();
                }
            } else if (windowStart == old.windowStart()) {
				// 如果窗口启动时间没有发生变化
                /*
                 *     B0       B1      B2     B3      B4
                 * ||_______|_______|_______|_______|_______||___
                 * 200     400     600     800     1000    1200  timestamp
                 *                             ^
                 *                          time=888
				 * B3窗口的起始时间是800，所以此窗口是最新的
				 */
				// 直接返回此窗口
                return old;
            } else if (windowStart > old.windowStart()) {
				// 如果重新计算的窗口起始时间和已有的窗口起始时间是不一样的
                /*
                 *   (old)
                 *             B0       B1      B2    NULL      B4
                 * |_______||_______|_______|_______|_______|_______||___
                 * ...    1200     1400    1600    1800    2000    2200  timestamp
                 *                              ^
                 *                           time=1676
                 *          startTime of Bucket 2: 400, deprecated, should be reset
                 *
                 * If the start timestamp of old bucket is behind provided time, that means
                 * the bucket is deprecated. We have to reset the bucket to current {@code windowStart}.
                 * Note that the reset and clean-up operations are hard to be atomic,
                 * so we need a update lock to guarantee the correctness of bucket update.
				 * 如果
                 * The update lock is conditional (tiny scope) and will take effect only when
                 * bucket is deprecated, so in most cases it won't lead to performance loss.
                 */
                if (updateLock.tryLock()) {
                    try {
                        // Successfully get the update lock, now we reset the bucket.
                        return resetWindowTo(old, windowStart);
                    } finally {
                        updateLock.unlock();
                    }
				} else {
					// 竞争失败，线程会yield()直到窗口可用
                    Thread.yield();
                }
            } else if (windowStart < old.windowStart()) {
				// 如果重新计算的时间小于窗口的起始时间，需要重新创建一个窗口
                return new WindowWrap<T>(windowLengthInMs, windowStart, newEmptyBucket(timeMillis));
            }
        }
	}

	/**
	 * 获取给定的时间戳的上一个窗口
	 * @param timeMillis 合法时间戳
	 * @return 给定的时间戳的上一个窗口
     */
    public WindowWrap<T> getPreviousWindow(long timeMillis) {
        if (timeMillis < 0) {
            return null;
		}
		// 计算给定时间戳的窗口位置
        long timeId = (timeMillis - windowLengthInMs) / windowLengthInMs;
        int idx = (int)(timeId % array.length());
        timeMillis = timeMillis - windowLengthInMs;
		// 获取计算出来的窗口
        WindowWrap<T> wrap = array.get(idx);
		// 校验窗口的状态
        if (wrap == null || isWindowDeprecated(wrap)) {
            return null;
		}
		// 如果当前窗口的起始位置+窗口间隔小于给定时间戳-窗口间隔，则没有给定时间戳的前一个窗口
        if (wrap.windowStart() + windowLengthInMs < (timeMillis)) {
            return null;
		}
		// 返回给定时间戳的前一个窗口
        return wrap;
	}

	/**
	 * @return 获取当前时间戳的上一个窗口
     */
    public WindowWrap<T> getPreviousWindow() {
        return getPreviousWindow(TimeUtil.currentTimeMillis());
	}

	/**
	 * 获取给定时间戳的滑动窗口的数据统计值
	 * @param timeMillis 合法时间戳
	 * @return 滑动窗口的数据统计值
     */
    public T getWindowValue(long timeMillis) {
        if (timeMillis < 0) {
            return null;
		}
		// 计算时间戳对应的滑动窗口索引
        int idx = calculateTimeIdx(timeMillis);
		// 获取滑动窗口
        WindowWrap<T> bucket = array.get(idx);
		// 校验滑动窗口状态
        if (bucket == null || !bucket.isTimeInWindow(timeMillis)) {
            return null;
		}
		// 返回滑动窗口储存的值
        return bucket.value();
    }

    /**
     * Check if a bucket is deprecated, which means that the bucket
     * has been behind for at least an entire window time span.
	 * 检查滑动窗口状态是否失效
     * @param windowWrap a non-null bucket
     * @return true if the bucket is deprecated; otherwise false
     */
    public boolean isWindowDeprecated(/*@NonNull*/ WindowWrap<T> windowWrap) {
        return isWindowDeprecated(TimeUtil.currentTimeMillis(), windowWrap);
    }

    public boolean isWindowDeprecated(long time, WindowWrap<T> windowWrap) {
        return time - windowWrap.windowStart() > intervalInMs;
    }

    /**
     * Get valid bucket list for entire sliding window.
     * The list will only contain "valid" buckets.
     *
     * @return valid bucket list for entire sliding window.
     */
    public List<WindowWrap<T>> list() {
        return list(TimeUtil.currentTimeMillis());
    }

    public List<WindowWrap<T>> list(long validTime) {
        int size = array.length();
        List<WindowWrap<T>> result = new ArrayList<WindowWrap<T>>(size);

        for (int i = 0; i < size; i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null || isWindowDeprecated(validTime, windowWrap)) {
                continue;
            }
            result.add(windowWrap);
        }

        return result;
    }

    /**
     * Get all buckets for entire sliding window including deprecated buckets.
     *
     * @return all buckets for entire sliding window
     */
    public List<WindowWrap<T>> listAll() {
        int size = array.length();
        List<WindowWrap<T>> result = new ArrayList<WindowWrap<T>>(size);

        for (int i = 0; i < size; i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null) {
                continue;
            }
            result.add(windowWrap);
        }

        return result;
    }

    /**
     * Get aggregated value list for entire sliding window.
     * The list will only contain value from "valid" buckets.
     *
     * @return aggregated value list for entire sliding window
     */
    public List<T> values() {
        return values(TimeUtil.currentTimeMillis());
    }

    public List<T> values(long timeMillis) {
        if (timeMillis < 0) {
            return new ArrayList<T>();
        }
        int size = array.length();
        List<T> result = new ArrayList<T>(size);

        for (int i = 0; i < size; i++) {
            WindowWrap<T> windowWrap = array.get(i);
            if (windowWrap == null || isWindowDeprecated(timeMillis, windowWrap)) {
                continue;
            }
            result.add(windowWrap.value());
        }
        return result;
    }

    /**
     * Get the valid "head" bucket of the sliding window for provided timestamp.
     * Package-private for test.
     *
     * @param timeMillis a valid timestamp in milliseconds
     * @return the "head" bucket if it exists and is valid; otherwise null
     */
    WindowWrap<T> getValidHead(long timeMillis) {
        // Calculate index for expected head time.
        int idx = calculateTimeIdx(timeMillis + windowLengthInMs);

        WindowWrap<T> wrap = array.get(idx);
        if (wrap == null || isWindowDeprecated(wrap)) {
            return null;
        }

        return wrap;
    }

    /**
     * Get the valid "head" bucket of the sliding window at current timestamp.
     *
     * @return the "head" bucket if it exists and is valid; otherwise null
     */
    public WindowWrap<T> getValidHead() {
        return getValidHead(TimeUtil.currentTimeMillis());
    }

    /**
     * Get sample count (total amount of buckets).
     *
     * @return sample count
     */
    public int getSampleCount() {
        return sampleCount;
    }

    /**
     * Get total interval length of the sliding window in milliseconds.
     *
     * @return interval in second
     */
    public int getIntervalInMs() {
        return intervalInMs;
    }

    /**
     * Get total interval length of the sliding window.
     *
     * @return interval in second
     */
    public double getIntervalInSecond() {
        return intervalInMs / 1000.0;
    }

    public void debug(long time) {
        StringBuilder sb = new StringBuilder();
        List<WindowWrap<T>> lists = list(time);
        sb.append("Thread_").append(Thread.currentThread().getId()).append("_");
        for (WindowWrap<T> window : lists) {
            sb.append(window.windowStart()).append(":").append(window.value().toString());
        }
        System.out.println(sb.toString());
    }

    public long currentWaiting() {
        // TODO: default method. Should remove this later.
        return 0;
    }

    public void addWaiting(long time, int acquireCount) {
        // Do nothing by default.
        throw new UnsupportedOperationException();
    }
}
