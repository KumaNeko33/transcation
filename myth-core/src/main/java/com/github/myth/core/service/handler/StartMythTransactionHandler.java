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

package com.github.myth.core.service.handler;

import com.github.myth.annotation.Myth;
import com.github.myth.annotation.PropagationEnum;
import com.github.myth.common.bean.context.MythTransactionContext;
import com.github.myth.common.bean.entity.MythTransaction;
import com.github.myth.common.enums.MythStatusEnum;
import com.github.myth.core.concurrent.threadlocal.TransactionContextLocal;
import com.github.myth.core.service.MythTransactionHandler;
import com.github.myth.core.service.impl.MythTransactionManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>Description: .</p>
 * Myth分布式事务发起者， 即分布式事务接口的入口 会进入该handler
 *
 * @author xiaoyu(Myth)
 * @version 1.0
 * @date 2017/11/30 10:11
 * @since JDK 1.8
 */
@Component
public class StartMythTransactionHandler implements MythTransactionHandler {


    private static final Lock LOCK = new ReentrantLock();


    private final MythTransactionManager mythTransactionManager;


    @Autowired
    public StartMythTransactionHandler(MythTransactionManager mythTransactionManager) {
        this.mythTransactionManager = mythTransactionManager;
    }


    /**
     * Myth分布式事务处理入口
     * 在走account流程时，其实发起者一直在 point.proceed(); 这里等待返回结果呢，这里需要等待orderService.orderPay业务方法全部执行完
     * 才会返回return，然而我们上面才走account一个扣款接口，还有inventory扣减库存接口，这里inventory接口与account接口角色都是参与者
     * ，流程上是一样的，只是业务不一样而已，这里也就不做过多介绍了，童鞋们自己过一遍即可。
     * @param point                  point 切点
     * @param mythTransactionContext myth事务上下文
     * @return Object
     * @throws Throwable 异常
     */
    @Override
    public Object handler(ProceedingJoinPoint point, MythTransactionContext mythTransactionContext) throws Throwable {

        try {
            //mythTransactionContext为null
            //主要防止并发问题，对事务日志的写造成压力，加了锁进行处理
            try {
//                ReentrantLock提供了lock()方法：
//                如果该锁定 没有 被另一个线程保持，则获取该锁定并立即返回，将锁定的保持计数设置为 1。
//                如果当前线程 已经保持该锁定，则将保持计数加 1，并且该方法立即返回。
//                如果该锁定 被另一个线程保持，则出于线程调度的目的，禁用当前线程，并且在获得锁定之前，该线程将一直处于休眠状态，此时锁定保持计数被设置为 1。
                LOCK.lock();
                mythTransactionManager.begin(point);//方法中CURRENT.set(mythTransaction);在mythTransactionManager的ThreadLocal中设置了一个事务记录，并且将事务记录保存到了数据库的表myth_order_service中
            } finally {
                LOCK.unlock();
            }
            //切点继续运行mian线程，调用point.proceed()正式进入到业务方法paymentService.makePayment中，point.proceed()没执行完不会执行finally方法
           return  point.proceed();

        } finally { //finally在 point.proceed()的执行结果放入 return返回变量中后才会执行，即下单流程走完后执行finally，执行完finally后，return 返回变量
            //finally表示方法返回前 无论如何都需要发送mq消息
            mythTransactionManager.sendMessage();//事务协调补偿,  而发起者order也会使用scheduledAutoRecover方法定时自动恢复，如果数据库中存在 事务状态为2（开始）的记录，则也调用CoordinatorServiceImpl的sendMessage()方法发送mq消息
            //难道发起者order的定时自动回复是为了 解决在执行mythTransactionManager.sendMessage();时 服务器宕机 导致事务未提交？
            mythTransactionManager.cleanThreadLocal();//移除ThreadLocal中的事务数据DTO
            TransactionContextLocal.getInstance().remove();//移除ThreadLocal中的事务上下文
        }
    }

}
