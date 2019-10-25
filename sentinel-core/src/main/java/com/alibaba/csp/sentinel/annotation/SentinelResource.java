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
package com.alibaba.csp.sentinel.annotation;

import com.alibaba.csp.sentinel.EntryType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotation indicates a definition of Sentinel resource.
 *
 * @author Eric Zhao
 * @since 0.1.1
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface SentinelResource {

    /**
	 * @return 资源名称
     */
    String value() default "";

    /**
	 * @return 资源调用方向，默认是向外部进行调用
     */
    EntryType entryType() default EntryType.OUT;

    /**
	 * blockHandler函数的访问范围需要是public
	 * 返回类型需要与资源方法相匹配
	 * 参数类型需要与资源方法相匹配，并在最后添加一个额外的参数，类型是BlockException
	 * blockHandler参数默认需要和原方法在一个类型中
	 * @return 出现阻塞时的异常调用函数，如果没有，默认为""
     */
    String blockHandler() default "";

    /**
	 * 如果出现blockHandler不在资源方法所在的类中，可以使用blockHandlerClass来指定blockHandler方法对应的class
	 * 需要注意的是blockHandler方法必须为static类型
	 * @return blockHandler方法所在的类，虽然是数组，但是至多只能提供一个类
     */
    Class<?>[] blockHandlerClass() default {};

    /**
	 * fallback()可以处理除可忽略异常外的所有异常，但是要求：
	 * 返回值类型必须与资源方法保持一致
	 * 方法参数列表必须和资源方法保持一致
	 * 参数可以在后面选配一个Throwable类型用于接收对应的异常
	 * 需要和资源方法再同一个类中
	 * @return 资源在被控制后对异常的处理逻辑
     */
    String fallback() default "";

    /**
	 * defaultFallback方法用于默认的fallback方法名称，用于通用的fallback逻辑
	 * defaultFallback可以针对除可忽略异常外的所有异常进行处理，但是要求：
	 * 返回值类型必须与资源方法保持一致
	 * 方法参数列表必须和资源方法保持一致
	 * 参数可以在后面选配一个Throwable类型用于接收对应的异常
	 * 需要和资源方法再同一个类中
	 * @return 用于默认的fallback方法名称
     * @since 1.6.0
     */
    String defaultFallback() default "";

    /**
	 * 如果fallback不能和资源方法声明在同一个类中，可以使用fallbackClass指定fallback方法所在的位置
	 * 需要注意的是，fallback方法必须声明为static
	 * @return fallback方法所在的类，虽然是数组，但是至多只能提供一个类
     * @since 1.6.0
     */
    Class<?>[] fallbackClass() default {};

    /**
	 * @return 需要追踪的异常，默认需要追踪Throwable异常
     * @since 1.5.1
     */
    Class<? extends Throwable>[] exceptionsToTrace() default {Throwable.class};
    
    /**
	 * 可以忽略的异常
	 * 需要注意的是exceptionsToTrace()和exceptionsToIgnore()在同一时间不能相交，或者exceptionsToIgnore()有更高的优先级
     * @return the list of exception classes to ignore, empty by default
     * @since 1.6.0
     */
    Class<? extends Throwable>[] exceptionsToIgnore() default {};
}
