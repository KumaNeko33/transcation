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

import com.github.myth.common.bean.context.MythTransactionContext;
import com.github.myth.common.bean.entity.MythTransaction;
import com.github.myth.common.enums.MythStatusEnum;
import com.github.myth.common.utils.LogUtil;
import com.github.myth.core.concurrent.threadlocal.TransactionContextLocal;
import com.github.myth.core.service.MythTransactionHandler;
import com.github.myth.core.service.impl.MythTransactionManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>Description: .</p>
 * Myth分布式事务参与者， 参与分布式事务的接口会进入该handler
 *
 * @author xiaoyu(Myth)
 * @version 1.0
 * @date 2017/11/30 10:11
 * @since JDK 1.8
 */
@Component
public class ActorMythTransactionHandler implements MythTransactionHandler {


    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ActorMythTransactionHandler.class);

    private static final Lock LOCK = new ReentrantLock();


    private final MythTransactionManager mythTransactionManager;


    @Autowired
    public ActorMythTransactionHandler(MythTransactionManager mythTransactionManager) {
        this.mythTransactionManager = mythTransactionManager;
    }


    /**
     * Myth分布式事务处理接口
     *
     * @param point                  point 切点
     * @param mythTransactionContext myth事务上下文
     * @return Object
     * @throws Throwable 异常
     */
    @Override
    public Object handler(ProceedingJoinPoint point, MythTransactionContext mythTransactionContext) throws Throwable {

        try {
            //处理并发问题
            LOCK.lock();
            //先保存事务日志（通过QUEUE阻塞队列消费），这时传入的参数：事务上下文 mythTransactionContext 中 在order中的@myth切面中 已存有一个 事务上下文，已经设置了事务trans_id和事务参与者角色role
            //如果是account项目的实现类方法切面@Myth进来，则在myth库的myth_account_service表中创建 新的事务记录（它的trans_id和role都已在事务上下文mythTransactionContext中 设置好）
            //如果是inventory项目的实现类方法切面@Myth进来，则在myth库的myth_inventory_service表中创建 新的事务记录（它的trans_id和role都已在事务上下文mythTransactionContext中 设置好，都相同）
            mythTransactionManager.actorTransaction(point, mythTransactionContext);

            //发起调用（执行try方法），继续执行AccountServiceImpl中的加了@Myth切面注解的方法，即进行扣除 account表的余额
            final Object proceed = point.proceed();
//proceed走完，即扣除完 account表的余额，
            //加了@Myth切面注解的方法执行成功, 往下走，更新补偿数据状态为commit，这时因为是account项目的，
            // 所以更新的myth库中的tableName=myth_account_service(tableName在account项目初始化时设置好表中的事务记录状态status=1 提交commit
            mythTransactionManager.updateStatus(mythTransactionContext.getTransId(),
                    MythStatusEnum.COMMIT.getCode());//进行这一步时表myth_account_service才创建了事务记录，说明这时QUEUE中创建 新的事务记录 的消息被消费？

            return proceed;//return proceed之前先执行 finally中的内容

        } catch (Throwable throwable) {
            LogUtil.error(LOGGER, "执行分布式事务接口失败,事务id：{}", mythTransactionContext::getTransId);
            mythTransactionManager.updateStatus(mythTransactionContext.getTransId(),
                    MythStatusEnum.FAILURE.getCode());
            throw throwable;
        } finally {
            LOCK.unlock();
            TransactionContextLocal.getInstance().remove();//清除事务上下文

        }
    }
}
