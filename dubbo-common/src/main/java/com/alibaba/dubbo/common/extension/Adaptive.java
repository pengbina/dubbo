/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.URL;

import java.lang.annotation.*;

/**
 * Provide helpful information for {@link ExtensionLoader} to inject dependency extension instance.
 *
 * @see ExtensionLoader
 * @see URL
 *
 * 该注解可以使用在类，接口，枚举，方法上面，如果标注在接口的方法上时，可以根据参数动态获取实现类，
 * 在第一次getExtension时，会自动生成和编译一个动态的Adaptive，达到动态实现类的效果。
 * 如果标注在实现类上的时候主要是为了直接固定对应的实现而不需要动态生成代码实现。
 * 该注解有一个参数value,是个string数组类型，表示可以通过多个元素依次查找实现类。
 *
 * 该注解的作用是决定哪个自适应拓展类被注入，该目标拓展类是由URL中的参数决定，URL中参数key由该注解的value给出，该key的value作为目标拓展类名称。
 *
 * 如果注解中有多个值，则根据下标从小到大去URL中查找有无对应的key，一旦找到就用该key的value作为目标拓展类名称。
 * 如果这些值在url中都没有对应的key，使用spi上的默认值。
 * @Adaptive注解可以作用的类上与方法上， 绝大部分情况下，该注解是作用在方法上，当 Adaptive 注解在类上时，Dubbo 不会为该类生成代理类。
 * 注解在方法（接口方法）上时， Dubbo 则会为该方法生成代理类。
 * Adaptive 注解在接口方法上，表示拓展的加载逻辑需由框架自动生成。注解在类上，表示拓展的加载逻辑由人工编码完成。
 *
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Adaptive {
    /**
     * Decide which target extension to be injected. The name of the target extension is decided by the parameter passed
     * in the URL, and the parameter names are given by this method.
     * <p>
     * If the specified parameters are not found from {@link URL}, then the default extension will be used for
     * dependency injection (specified in its interface's {@link SPI}).
     * <p>
     * For examples, given <code>String[] {"key1", "key2"}</code>:
     * <ol>
     * <li>find parameter 'key1' in URL, use its value as the extension's name</li>
     * <li>try 'key2' for extension's name if 'key1' is not found (or its value is empty) in URL</li>
     * <li>use default extension if 'key2' doesn't appear either</li>
     * <li>otherwise, throw {@link IllegalStateException}</li>
     * </ol>
     * If default extension's name is not give on interface's {@link SPI}, then a name is generated from interface's
     * class name with the rule: divide classname from capital char into several parts, and separate the parts with
     * dot '.', for example: for {@code com.alibaba.dubbo.xxx.YyyInvokerWrapper}, its default name is
     * <code>String[] {"yyy.invoker.wrapper"}</code>. This name will be used to search for parameter from URL.
     *
     * @return parameter key names in URL
     */
    String[] value() default {};

}