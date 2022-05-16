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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Adaptive;
import com.alibaba.dubbo.common.extension.SPI;

/**
 * Transporter. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Transport_Layer">Transport Layer</a>
 * <a href="http://en.wikipedia.org/wiki/Client%E2%80%93server_model">Client/Server</a>
 *
 * @see com.alibaba.dubbo.remoting.Transporters
 *
 * 在接口方法上加@Adaptive注解，dubbo会动态生成适配器类
 *
 * 这个接口的bind和connect方法上都有@Adaptive注解，有该注解的方法的参数必须包含URL，ExtensionLoader会通过createAdaptiveExtensionClassCode方法动态生成一个Transporter$Adaptive类
 *
 * package com.alibaba.dubbo.remoting;
 * import com.alibaba.dubbo.common.extension.ExtensionLoader;
 * public class Transporter$Adaptive implements com.alibaba.dubbo.remoting.Transporter{
 *
 *     public com.alibaba.dubbo.remoting.Client connect(com.alibaba.dubbo.common.URL arg0, com.alibaba.dubbo.remoting.ChannelHandler arg1) throws com.alibaba.dubbo.remoting.RemotingException {
 *         //URL参数为空则抛出异常。
 *         if (arg0 == null)
 *             throw new IllegalArgumentException("url == null");
 *
 *         com.alibaba.dubbo.common.URL url = arg0;
 *         //这里的getParameter方法可以在源码中具体查看
 *         String extName = url.getParameter("client", url.getParameter("transporter", "netty"));
 *         if(extName == null)
 *             throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.remoting.Transporter) name from url(" + url.toString() + ") use keys([client, transporter])");
 *         //这里我在后面会有详细介绍
 *         com.alibaba.dubbo.remoting.Transporter extension = (com.alibaba.dubbo.remoting.Transporter)ExtensionLoader.getExtensionLoader
 *
 *         (com.alibaba.dubbo.remoting.Transporter.class).getExtension(extName);
 *         return extension.connect(arg0, arg1);
 *     }
 *     public com.alibaba.dubbo.remoting.Server bind(com.alibaba.dubbo.common.URL arg0, com.alibaba.dubbo.remoting.ChannelHandler arg1) throws com.alibaba.dubbo.remoting.RemotingException {
 *         if (arg0 == null)
 *             throw new IllegalArgumentException("url == null");
 *         com.alibaba.dubbo.common.URL url = arg0;
 *         String extName = url.getParameter("server", url.getParameter("transporter", "netty"));
 *         if(extName == null)
 *             throw new IllegalStateException("Fail to get extension(com.alibaba.dubbo.remoting.Transporter) name from url(" + url.toString() + ") use keys([server, transporter])");
 *         com.alibaba.dubbo.remoting.Transporter extension = (com.alibaba.dubbo.remoting.Transporter)ExtensionLoader.getExtensionLoader
 *         (com.alibaba.dubbo.remoting.Transporter.class).getExtension(extName);
 *
 *         return extension.bind(arg0, arg1);
 *     }
 * }
 *
 * 所有扩展点都通过传递URL携带配置信息，所以适配器中的方法必须携带URL参数，才能根据URL中的配置来选择对应的扩展实现。
 * @Adaptive注解中有一些key值，比如connect方法的注解中有两个key，分别为“client”和“transporter”，URL会首先去取client对应的value来作为我上述（一）注解@SPI中写到的key值，如果为空，
 * 则去取transporter对应的value，如果还是为空，则会根据SPI默认的key，也就是netty去调用扩展的实现类，如果@SPI没有设定默认值，则会抛出IllegalStateException异常。
 *
 * 这样就比较清楚这个适配器如何去选择哪个实现类作为本次需要调用的类，这里最关键的还是强调了dubbo以URL为总线，
 * 运行过程中所有的状态数据信息都可以通过URL来获取，
 * 比如当前系统采用什么序列化，采用什么通信，采用什么负载均衡等信息，都是通过URL的参数来呈现的，所以在框架运行过程中，
 * 运行到某个阶段需要相应的数据，都可以通过对应的Key从URL的参数列表中获取
 */
@SPI("netty")
public interface Transporter {

    /**
     * Bind a server.
     *
     * @param url     server url
     * @param handler
     * @return server
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#bind(URL, ChannelHandler...)
     */
    @Adaptive({Constants.SERVER_KEY, Constants.TRANSPORTER_KEY})
    Server bind(URL url, ChannelHandler handler) throws RemotingException;

    /**
     * Connect to a server.
     *
     * @param url     server url
     * @param handler
     * @return client
     * @throws RemotingException
     * @see com.alibaba.dubbo.remoting.Transporters#connect(URL, ChannelHandler...)
     */
    @Adaptive({Constants.CLIENT_KEY, Constants.TRANSPORTER_KEY})
    Client connect(URL url, ChannelHandler handler) throws RemotingException;

}