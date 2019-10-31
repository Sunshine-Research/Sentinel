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

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.property.SimplePropertyListener;

/**
 * 占领等待时间属性，用于提供占领的等待时间
 * @since 1.5.0
 */
public class OccupyTimeoutProperty {

    /**
	 * 最大占领等待时间，单位ms
	 * 开启优先级策略的请求，可以占领未来数据分析窗口产生的token，而{@code occupyTimeout}规定了token可以被占用的最大时间
	 * 需要注意的是，等待时间不能超过滑动窗口时间
	 * 不要直接修改这个值，请使用{@link #updateTimeout(int)}方法进行修改
	 * 否则修改可能不会生效
     */
    private static volatile int occupyTimeout = 500;

    public static void register2Property(SentinelProperty<Integer> property) {
        property.addListener(new SimplePropertyListener<Integer>() {
            @Override
            public void configUpdate(Integer value) {
                if (value != null) {
                    updateTimeout(value);
                }
            }
        });
    }

    public static int getOccupyTimeout() {
        return occupyTimeout;
    }

    /**
	 * 更新等待时间
	 * 需要注意的是等待时间不能超过滑动窗口时间
	 * 如果超过了滑动窗口的时间，新的等待时间不会被更新
	 * @param newInterval 更新后的值
	 */
	public static void updateTimeout(int newInterval) {
		if (newInterval < 0) {
			RecordLog.warn("[OccupyTimeoutProperty] Illegal timeout value will be ignored: " + occupyTimeout);
			return;
		}
		if (newInterval > IntervalProperty.INTERVAL) {
			RecordLog.warn("[OccupyTimeoutProperty] Illegal timeout value will be ignored: " + occupyTimeout
					+ ", should <= " + IntervalProperty.INTERVAL);
			return;
		}
		if (newInterval != occupyTimeout) {
			occupyTimeout = newInterval;
        }
        RecordLog.info("[OccupyTimeoutProperty] occupyTimeout updated to: " + occupyTimeout);
    }
}
