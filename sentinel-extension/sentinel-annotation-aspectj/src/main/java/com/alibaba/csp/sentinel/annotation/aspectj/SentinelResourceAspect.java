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
package com.alibaba.csp.sentinel.annotation.aspectj;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

import java.lang.reflect.Method;

/**
 * Aspect for methods with {@link SentinelResource} annotation.
 *
 * @author Eric Zhao
 */
@Aspect
public class SentinelResourceAspect extends AbstractSentinelAspectSupport {

    @Pointcut("@annotation(com.alibaba.csp.sentinel.annotation.SentinelResource)")
    public void sentinelResourceAnnotationPointcut() {
    }

    @Around("sentinelResourceAnnotationPointcut()")
    public Object invokeResourceWithSentinel(ProceedingJoinPoint pjp) throws Throwable {
		// 解析切点植入的方法
        Method originMethod = resolveMethod(pjp);
		// 获取业务方法的@SentinelResource的注解信息
        SentinelResource annotation = originMethod.getAnnotation(SentinelResource.class);
        if (annotation == null) {
			// 如果方法实际上没有使用注解，那么不应该走到这里，抛出非法状态异常
            throw new IllegalStateException("Wrong state for SentinelResource annotation");
        }
		// 获取需要控制的资源名称
        String resourceName = getResourceName(annotation.value(), originMethod);
		// 获取控制的资源类型
        EntryType entryType = annotation.entryType();
        Entry entry = null;
        try {
			// 创建entry，并上报当前请求的资源信息
            entry = SphU.entry(resourceName, entryType, 1, pjp.getArgs());
			return pjp.proceed();
        } catch (BlockException ex) {
			// 对阻塞异常进行处理
            return handleBlockException(pjp, annotation, ex);
        } catch (Throwable ex) {
            Class<? extends Throwable>[] exceptionsToIgnore = annotation.exceptionsToIgnore();
			// 首先判断异常是否可以忽略
            if (exceptionsToIgnore.length > 0 && exceptionBelongsTo(ex, exceptionsToIgnore)) {
                throw ex;
            }
			// 接下来判断
            if (exceptionBelongsTo(ex, annotation.exceptionsToTrace())) {
                traceException(ex, annotation);
                return handleFallback(pjp, annotation, ex);
            }

			// 没有可以处理fallback函数可以处理此异常，只能抛出
            throw ex;
        } finally {
            if (entry != null) {
				// 请求占用完成，释放请求
                entry.exit(1, pjp.getArgs());
            }
        }
    }
}
