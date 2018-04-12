/*
 *
 * Copyright 2017-2018 549477611@qq.com(xiaoyu)
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.github.myth.dubbo.filter;


import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcResult;
import com.github.myth.annotation.MessageTypeEnum;
import com.github.myth.annotation.Myth;
import com.github.myth.common.bean.context.MythTransactionContext;
import com.github.myth.common.bean.entity.MythInvocation;
import com.github.myth.common.bean.entity.MythParticipant;
import com.github.myth.common.constant.CommonConstant;
import com.github.myth.common.exception.MythRuntimeException;
import com.github.myth.common.utils.GsonUtils;
import com.github.myth.core.concurrent.threadlocal.TransactionContextLocal;
import com.github.myth.core.service.impl.MythTransactionManager;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * dubbo过滤器，作用：因为dubbo接口无法使用切面注解拦截，故采用rpc框架的某些特性来帮助我们获取到 @Myth注解信息
 *  dubbo过滤器实现步骤：
 *  1.dubbo初始化过程加载META-INF/dubbo/internal/，META-INF/dubbo/，META-INF/services/三个路径(classloaderresource)下面的com.alibaba.dubbo.rpc.Filter文件
 * 本项目中是使用META-INF/dubbo/路径(classloaderresource)下面的com.alibaba.dubbo.rpc.Filter文件 来加载自定义过滤器
 *  2.文件配置每行 Name=FullClassName ，必须是实现Filter接口
 * 本项目的com.alibaba.dubbo.rpc.Filter文件中的配置为 MythTransactionFilter=com.github.myth.dubbo.filter.DubboMythTransactionFilter ，符合这一格式
 *  3.  @Activate标注扩展能被自动激活，如下所示
 *  4.  @Activate如果group（provider|consumer）匹配才被加载
 *  5.  @Activate的value字段标明过滤条件，不写则所有条件下都会被加载，写了则只有dubbo URL中包含该参数名且参数值不为空才被加载
 * @author xiaoyu
 */
@Activate(group = {Constants.SERVER_KEY, Constants.CONSUMER})
public class DubboMythTransactionFilter implements Filter {

    private MythTransactionManager mythTransactionManager;

    public void setMythTransactionManager(MythTransactionManager mythTransactionManager) {
        this.mythTransactionManager = mythTransactionManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String methodName = invocation.getMethodName();
        Class clazz = invoker.getInterface();
        Class[] args = invocation.getParameterTypes();
        final Object[] arguments = invocation.getArguments();

        Method method = null;
        Myth myth = null;
        try {
            method = clazz.getDeclaredMethod(methodName, args);
            myth = method.getAnnotation(Myth.class);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
        //只有方法上打了@Myth注解的(即myth != null)才会进入后续逻辑，否则直接执行返回
        if (Objects.nonNull(myth)) {
            try {
                //这里ThreadLocal里已经设置了 之前在Order的makePayment的@Myth注解切面处理器 StartMythTransactionHandler调用mythTransactionManager.begin(point);方法新增的 事务上下文 MythTransactionContext
                // 并通过TransactionContextLocal.getInstance().set(context);存入了ThreadLocal,同一个线程共享这个变量，所以此时mythTransactionContext不为null
                final MythTransactionContext mythTransactionContext =
                        TransactionContextLocal.getInstance().get();
                if (Objects.nonNull(mythTransactionContext)) {
//                    服务器消费方使用RpcContext.getContext().setAttachment(); 传递参数。服务提供方使用RpcContext.getContext().getAttachments();获取参数
//                    RpcContext是一个ThreadLocal的临时状态记录器，当接收到RPC请求，或发起RPC请求时，RpcContext的状态都会变化。比如A调用B，B再调用C，则B机器上，
//                  在B调用C之前，RpcContext记录的是A调用B的信息，在B调用C之后，RpcContext记录的是B调用C。
//                    客户端：RpcContext.getContext().setAttachment("sourceid", "15700007");--A
//                    调dubbo接口：smsService.smsSend();
//                    服务端：RpcContext.getContext().getAttachment("sourceid");--B
//                    一定要注意，调接口时，必须是A直接到B，如果A没有直接到B，而是先到C，再由C到B，那么在B里getAttachment()，就获取不到值了
                    RpcContext.getContext()
                            .setAttachment(CommonConstant.MYTH_TRANSACTION_CONTEXT,
                                    GsonUtils.getInstance().toJson(mythTransactionContext));//转成json字符串在存储于上下文中
                }
                //封装调用点,事务参与者
                final MythParticipant participant =
                        buildParticipant(mythTransactionContext, myth,
                                method, clazz, arguments, args);
                if (Objects.nonNull(participant)) {
                    //根据配置将 事务调用点 保存到数据库myth表的事务记录中：发起者order为保存到myth_order_service的字段invocation中
                    mythTransactionManager.registerParticipant(participant);
                }
                // 正式调用dubbo服务，但因为account项目中的AccountServiceImpl的payment方法也使用了@Myth注解，则同发起者order一样会触发@Myth切面拦截DubboMythTransactionInterceptor的interceptor()方法，处理
                return invoker.invoke(invocation);

            } catch (RpcException e) {
                e.printStackTrace();
                return new RpcResult();
            }
        } else {
//            不带@Myth注解的dubbo方法则直接执行返回
            return invoker.invoke(invocation);
        }
    }

    private MythParticipant buildParticipant(MythTransactionContext mythTransactionContext,
                                             Myth myth, Method method,
                                             Class clazz, Object[] arguments, Class... args)
            throws MythRuntimeException {

        if (Objects.nonNull(mythTransactionContext)) {

            MythInvocation mythInvocation = new MythInvocation(clazz,
                    method.getName(),
                    args, arguments);
            //事务消息目的地，account或inventory
            final String destination = myth.destination();
            //默认点对点
            final Integer pattern = myth.pattern().getCode();


            //封装调用点,事务参与者
            return new MythParticipant(
                    mythTransactionContext.getTransId(),
                    destination,
                    pattern,
                    mythInvocation);

        }

        return null;


    }
}
