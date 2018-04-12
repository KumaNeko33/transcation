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

package com.github.myth.core.coordinator.impl;


import com.github.myth.annotation.Myth;
import com.github.myth.common.bean.context.MythTransactionContext;
import com.github.myth.common.bean.entity.MythInvocation;
import com.github.myth.common.bean.entity.MythParticipant;
import com.github.myth.common.bean.entity.MythTransaction;
import com.github.myth.common.bean.mq.MessageEntity;
import com.github.myth.common.config.MythConfig;
import com.github.myth.common.enums.CoordinatorActionEnum;
import com.github.myth.common.enums.MythRoleEnum;
import com.github.myth.common.enums.MythStatusEnum;
import com.github.myth.common.exception.MythException;
import com.github.myth.common.exception.MythRuntimeException;
import com.github.myth.common.serializer.ObjectSerializer;
import com.github.myth.common.utils.LogUtil;
import com.github.myth.core.concurrent.threadlocal.TransactionContextLocal;
import com.github.myth.core.concurrent.threadpool.MythTransactionThreadFactory;
import com.github.myth.core.concurrent.threadpool.MythTransactionThreadPool;
import com.github.myth.core.coordinator.CoordinatorService;
import com.github.myth.core.coordinator.command.CoordinatorAction;
import com.github.myth.core.helper.SpringBeanUtils;
import com.github.myth.core.service.ApplicationService;
import com.github.myth.core.service.MythMqSendService;
import com.github.myth.core.spi.CoordinatorRepository;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author xiaoyu
 */
@Service("coordinatorService")
public class CoordinatorServiceImpl implements CoordinatorService {

    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CoordinatorServiceImpl.class);

    //BlockingQueue:阻塞队列。当队列中没有数据的情况下，消费者端的所有线程都会被自动阻塞（挂起），直到有数据放入队列，线程被自动唤醒。
    private static BlockingQueue<CoordinatorAction> QUEUE;

    private MythConfig mythConfig;

    private CoordinatorRepository coordinatorRepository;

    private final ApplicationService applicationService;


    private static volatile MythMqSendService mythMqSendService;

    private static final Lock LOCK = new ReentrantLock();


    private ObjectSerializer serializer;

    @Autowired
    public CoordinatorServiceImpl(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    //mq事务消息对象的序列化工具
    @Override
    public void setSerializer(ObjectSerializer serializer) {
        this.serializer = serializer;
    }


    /**
     * 保存本地事务日志
     *
     * @param mythConfig 配置信息
     * @throws MythException 异常
     */
    @Override
    public void start(MythConfig mythConfig) throws MythException {
        this.mythConfig = mythConfig;

        coordinatorRepository = SpringBeanUtils.getInstance().getBean(CoordinatorRepository.class);//即之前注入spring容器的 JdbcCoordinatorRepository

        final String repositorySuffix = buildRepositorySuffix(mythConfig.getRepositorySuffix());//mythConfig的配置为repositorySuffix = order-service
        //初始化spi 协调资源存储，JDBC的话创建 事务表
        coordinatorRepository.init(repositorySuffix, mythConfig);
        //初始化 协调资源线程池，用来接收并消费mq事务消息
        initCoordinatorPool();

        //如果需要定时自动恢复 开启线程 调度线程池，进行恢复(order独有：定时恢复）
        if (mythConfig.getNeedRecover()) { //配置为true，注意这里有个开关needRecover， 根据注释得知只需要在事务发起方我们才需要开启，默认关闭状态，我们这里是order服务，即为事务发起方，所以需要开启
            scheduledAutoRecover();
        }
    }


    /**
     * 保存本地事务信息
     *
     * @param mythTransaction 实体对象
     * @return 主键 transId
     */
    @Override
    public String save(MythTransaction mythTransaction) {
        final int rows = coordinatorRepository.create(mythTransaction);
        if (rows > 0) {
            return mythTransaction.getTransId();
        }
        return null;

    }

    @Override
    public MythTransaction findByTransId(String transId) {
        return coordinatorRepository.findByTransId(transId);
    }

    /**
     * 删除补偿事务信息
     *
     * @param transId 事务id
     * @return true成功 false 失败
     */
    @Override
    public boolean remove(String transId) {
        return coordinatorRepository.remove(transId) > 0;
    }

    /**
     * 更新
     *
     * @param mythTransaction 实体对象
     * @return rows 1 成功
     * @throws MythRuntimeException 异常信息
     */
    @Override
    public int update(MythTransaction mythTransaction) throws MythRuntimeException {
        return coordinatorRepository.update(mythTransaction);
    }

    /**
     * 更新 List<MythParticipant>  只更新这一个字段数据
     *
     * @param mythTransaction 实体对象
     * @return rows 1 rows 1 成功
     * @throws MythRuntimeException 异常信息
     */
    @Override
    public int updateParticipant(MythTransaction mythTransaction) throws MythRuntimeException {
        return coordinatorRepository.updateParticipant(mythTransaction);
    }


    /**
     * 更新本地日志状态
     *
     * @param transId 事务id
     * @param status  状态
     * @return rows 1 rows 1 成功
     * @throws MythRuntimeException 异常信息
     */
    @Override
    public int updateStatus(String transId, Integer status) {
        return coordinatorRepository.updateStatus(transId, status);
    }

    /**
     * 提交补偿操作
     *
     * @param coordinatorAction 执行动作
     */
    @Override
    public Boolean submit(CoordinatorAction coordinatorAction) {
        try {
            //持久化
// 之前讲服务启动源码解析，专门开了一个线程池任务 MythTransactionThreadPool （在本类中）来消费QUEUE队列做消息持久化操作，对的，消息就是在这里放进去的，
// 然后已经初始化好的线程池 MythTransactionThreadPool 用new Worker()的excute方法中的QUEUE.take()消费 QUEUE队列的消息。
//            CoordinatorAction(action=SAVE, mythTransaction=MythTransaction(transId=1688194773, status=2, role=1, retriedCount=0, createTime=Wed Jan 17 09:38:34 CST 2018, lastTime=Wed Jan 17 09:38:34 CST 2018, version=1, targetClass=com.github.myth.demo.dubbo.order.service.impl.PaymentServiceImpl, targetMethod=makePayment, errorMsg=null, mythParticipants=[]))
            QUEUE.put(coordinatorAction);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /**
     * 接收到mq消息处理
     *
     * @param message 实体对象转换成byte[]后的数据
     * @return true 处理成功  false 处理失败
     */
    @Override
    public Boolean processMessage(byte[] message) {
        try {
            MessageEntity entity;
            try {
                entity = serializer.deSerialize(message, MessageEntity.class);
            } catch (MythException e) {
                e.printStackTrace();
                throw new MythRuntimeException(e.getMessage());
            }
            /*
             * 1 检查该事务有没被处理过，已经处理过且处理未失败的 则不处理
             * 2 发起调用，调用接口，进行处理
             * 3 记录本地日志
             */
            LOCK.lock();

            final String transId = entity.getTransId();
            final MythTransaction mythTransaction = findByTransId(transId);//查找本项目的对应myth库中事务记录表中 是否存在事务id为transId的记录，米有的话说明 之前的支付操作出现过异常，导致本项目扣款或扣货物失败，未生成事务记录

            //如果是空或者 事务记录的状态是失败的
            if (Objects.isNull(mythTransaction)
                    || mythTransaction.getStatus() == MythStatusEnum.FAILURE.getCode()) {
                try {

                    //设置事务上下文，这个类会传递给远端
                    MythTransactionContext context = new MythTransactionContext();

                    //设置事务id
                    context.setTransId(transId);

                    //设置为本地执行角色
                    context.setRole(MythRoleEnum.LOCAL.getCode());

                    TransactionContextLocal.getInstance().set(context);
                    //进行本地事务补偿：即将消息实体类 entity中的 事务调用类MythInvocation 进行调用，
                    executeLocalTransaction(entity.getMythInvocation());//MythInvocation(targetClass=interface com.github.myth.demo.dubbo.account.api.service.AccountService, methodName=payment, parameterTypes=[class com.github.myth.demo.dubbo.account.api.dto.AccountDTO], args=[AccountDTO(userId=10000, amount=100)])

                    //会进入LocalMythTransactionHandler  那里有保存

                } catch (Exception e) {
                    e.printStackTrace();
                    throw new MythRuntimeException(e.getMessage());
                } finally {
                    TransactionContextLocal.getInstance().remove();
                }
            }
        } finally {
            LOCK.unlock();
        }
        return Boolean.TRUE;
    }


    /**
     * 发送消息
     *
     * @param mythTransaction 消息体
     * @return true 处理成功  false 处理失败
     */
    @Override
    public Boolean sendMessage(MythTransaction mythTransaction) {
        //如果是发起者order，则这里的mythTransaction已经在 StartMythTransactionHandler调用的mythTransactionManager.begin(point);中
        // 通过CURRENT.set(mythTransaction);在mythTransactionManager的ThreadLocal中设置了一个事务记录，并且将事务记录保存到了数据库的表myth_order_service中
        // 进入finally的sendMessage方法之前，mythTransactionManager.begin(point);执行完时 mythTransaction中还并没有设置参与协调的方法集合List<MythParticipant> mythParticipants，即表myth_order_service中也没有设置invocation字段的值
        // StartMythTransactionHandler在mythTransactionManager.begin(point);执行完后，执行return  point.proceed();时，线程将正式进入到order的业务方法paymentService.makePayment 中
        // 而在执行makePayment方法中，会调用带@Myth注解的 dubbo服务接口 accountService.payment 和  inventoryService.decrease，如下
//        @Myth(destination = "account")
//        boolean payment(AccountDTO accountDTO);
//        这时在正式调用前会被自定义的dubbo过滤器 DubboMythTransactionFilter 进行拦截，此时过滤方法中的if (Objects.nonNull(myth))成立，进入代码块
        final List<MythParticipant> mythParticipants = mythTransaction.getMythParticipants();//participants参与者的意思
            /*
             * 这里的这个判断很重要，不为空，表示本地的方法执行成功，需要执行远端的rpc方法
             * 为什么呢，因为我会在切面的finally里面发送消息，意思是切面无论如何都需要发送mq消息
             * 那么考虑问题，如果本地执行成功，调用rpc的时候才需要发
             * 如果本地异常，则不需要发送mq ，此时mythParticipants为空
             */
//  但发起者order第一次调用sendMessage时mythTransaction.getMythParticipants();为空（下面方法直接跳过），说明方法makePayment还未执行成功
        //发送事务记录消息，若事务记录中的 mythTransaction.getMythParticipants();不为空，说明本地的 方法makePayment执行成功,可发送消息 进行所有项目的commit
        if (CollectionUtils.isNotEmpty(mythParticipants)) {

            for (MythParticipant mythParticipant : mythParticipants) {
                MessageEntity messageEntity =
                        new MessageEntity(mythParticipant.getTransId(),
                                mythParticipant.getMythInvocation());
                try {
                    final byte[] message = serializer.serialize(messageEntity);
                    getMythMqSendService().sendMessage(mythParticipant.getDestination(),//一个是account，一个是inventory
                            mythParticipant.getPattern(),//default是1
                            message);
                } catch (Exception e) {
                    e.printStackTrace();
                    return Boolean.FALSE;
                }
            }
            //这里为什么要这么做呢？ 主要是为了防止在极端情况下，发起者执行过程中，突然自身down 机
            //造成消息未发送，新增一个状态标记，如果出现这种情况，通过定时任务发送消息
            this.updateStatus(mythTransaction.getTransId(), MythStatusEnum.COMMIT.getCode());
        }
        return Boolean.TRUE;
    }

//    前面我们创建了一个线程池进行分布式消息的持久化操作，这里就是如何使用这些数据，创建一个调度线程，定时 取出指定有效时间范围内 且 消息状态为开始 的数据，
// 然后再往mq中投递消息
    private void scheduledAutoRecover() {
//        ScheduledThreadPoolExecutor 最主要的功能就是可以对其中的任务进行调度，比如延迟执行、定时执行等等。
//        ScheduledThreadPoolExecutor的构造参数：
//        1.int corePoolSize：线程池维护线程的最少数量
//        2. ThreadFactory threadFactory：线程工程类，线程池用它来制造线程
//        可选3. RejectedExecutionHandler handler：线程池对拒绝任务的处理策略
        new ScheduledThreadPoolExecutor(1,
//                MythTransactionThreadFactory实现ThreadFactory，创建定制化的线程Thread（比如创建有意义的线程名称，设为守护线程，设置线程优先级，处理未捕获的异常等）
                MythTransactionThreadFactory.create("MythAutoRecoverService",
                        true))
//scheduleWithFixedDelay (Runnable, long initialDelay, long period, TimeUnit timeunit)，period指的当前任务的结束执行时间到下个任务的开始执行时间。
                .scheduleWithFixedDelay(() -> {
                    LogUtil.debug(LOGGER, "auto recover execute delayTime:{}",
                            () -> mythConfig.getScheduledDelay());//120秒
                    try {
                        //获取延迟多长时间后（即事务记录的last_time小于 这个时间 的事务记录）的并且状态是开始（status==2)的事务信息,主要为了防止并发的时候，刚新增的数据被执行
                        final List<MythTransaction> mythTransactionList =
                                coordinatorRepository.listAllByDelay(acquireData());
                        if (CollectionUtils.isNotEmpty(mythTransactionList)) {
                            mythTransactionList
                                    .forEach(mythTransaction -> {
                                        //发送事务记录消息，若事务记录中的 mythTransaction.getMythParticipants();不为空，说明本地的 方法makePayment执行成功,可发送消息 进行所有项目的commit
                                        final Boolean success = sendMessage(mythTransaction);
                                        //发送成功 ，更改数据库的事务记录状态为1（提交）
                                        if (success) {
                                            coordinatorRepository.updateStatus(mythTransaction.getTransId(),
                                                    MythStatusEnum.COMMIT.getCode());
                                        }
                                    });
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, 30, mythConfig.getScheduledDelay(), TimeUnit.SECONDS);//scheduleWithFixedDelay方法配置getScheduledDelay()=120秒

    }

    private Date acquireData() {
        return new Date(LocalDateTime.now()
                .atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli() - (mythConfig.getRecoverDelayTime() * 1000));//getRecoverDelayTime()=120秒
    }

    private String buildRepositorySuffix(String repositorySuffix) {
        if (StringUtils.isNoneBlank(repositorySuffix)) {
            return repositorySuffix;
        } else {
            return applicationService.acquireName();
        }

    }

    @SuppressWarnings("unchecked")
    private void executeLocalTransaction(MythInvocation mythInvocation) throws Exception {
        if (Objects.nonNull(mythInvocation)) {
            final Class clazz = mythInvocation.getTargetClass();
            final String method = mythInvocation.getMethodName();
            final Object[] args = mythInvocation.getArgs();
            final Class[] parameterTypes = mythInvocation.getParameterTypes();
            final Object bean = SpringBeanUtils.getInstance().getBean(clazz);
            MethodUtils.invokeMethod(bean, method, args, parameterTypes);//该方法Invoke a named method whose parameter type matches the object type.
//            The behaviour of this method is less deterministic than invokeExactMethod(Object object,String methodName,Object [] args,Class[] parameterTypes). It loops through all methods with names that match and then executes the first it finds with compatible parameters.
//                    This method supports calls to methods taking primitive parameters via passing in wrapping classes. So, for example, a Boolean class would match a boolean primitive.
            LogUtil.debug(LOGGER, "Myth执行本地协调事务:{}", () -> mythInvocation.getTargetClass()
                    + ":" + mythInvocation.getMethodName());
        }
    }

    private void initCoordinatorPool() {
        synchronized (LOGGER) {
//            基于链表的阻塞队列，同ArrayListBlockingQueue类似，其内部也维持着一个数据缓冲队列（该队列由一个链表构成），当生产者往队列中放入一个数据时，
// 队列会从生产者手中获取数据，并缓存在队列内部，而生产者立即返回；只有当队列缓冲区达到最大值缓存容量时（LinkedBlockingQueue可以通过构造函数指定该值），
// 才会阻塞生产者队列，直到消费者从队列中消费掉一份数据，生产者线程会被唤醒，反之对于消费者这端的处理也基于同样的原理。而LinkedBlockingQueue之所以能够高效的处理并发数据，
// 还因为其对于生产者端和消费者端分别采用了独立的锁来控制数据同步，这也意味着在高并发的情况下生产者和消费者可以并行地操作队列中的数据，以此来提高整个队列的并发性能。
//            作为开发者，我们需要注意的是，如果构造一个LinkedBlockingQueue对象，而没有指定其容量大小，LinkedBlockingQueue会默认一个类似无限大小的容量（Integer.MAX_VALUE），
// 这样的话，如果生产者的速度一旦大于消费者的速度，也许还没有等到队列满阻塞产生，系统内存就有可能已被消耗殆尽了。
//            ArrayBlockingQueue和LinkedBlockingQueue是两个最普通也是最常用的阻塞队列，一般情况下，在处理多线程间的生产者消费者问题，使用这两个类足以。

//            首先初始化一个LinkedBlockingQueue阻塞队列QUEUE，该队列作用主要用于存放分布式消息内容，
// 其次创建了一个线程池，线程池中执行的任务Worker,主要消费QUEUE队列消息进行分布式消息的持久化操作
            QUEUE = new LinkedBlockingQueue<>(mythConfig.getCoordinatorQueueMax()); //5000
            final int coordinatorThreadMax = mythConfig.getCoordinatorThreadMax(); //8
            final MythTransactionThreadPool threadPool = SpringBeanUtils.getInstance().getBean(MythTransactionThreadPool.class);
            final ExecutorService executorService = threadPool.newCustomFixedThreadPool(coordinatorThreadMax);
            LogUtil.info(LOGGER, "启动协调资源操作线程数量为:{}", () -> coordinatorThreadMax);
            for (int i = 0; i < coordinatorThreadMax; i++) {
                //消费由于事务切面注解@Myth触发放入的QUEUE中的事务
                executorService.execute(new Worker());
            }
        }
    }

    private synchronized MythMqSendService getMythMqSendService() {
        if (mythMqSendService == null) {
            synchronized (CoordinatorServiceImpl.class) {//同步锁，保证多个线程只能有一个线程 来发送消息
                if (mythMqSendService == null) {
                    mythMqSendService = SpringBeanUtils.getInstance().getBean(MythMqSendService.class);
                }
            }
        }
        return mythMqSendService;
    }


    /**
     * 线程执行器
     */
    class Worker implements Runnable {

        @Override
        public void run() {
            execute();
        }

        private void execute() {
            while (true) {
                try {
                    //  take():取走BlockingQueue里排在首位的对象,若BlockingQueue为空,阻断进入等待状态直到
                    //  BlockingQueue有新的数据被加入;
                    final CoordinatorAction coordinatorAction = QUEUE.take();
                    if (coordinatorAction != null) {
                        final int code = coordinatorAction.getAction().getCode();
                        if (CoordinatorActionEnum.SAVE.getCode() == code) {//code=0, 发起者order第一次进入切面@Myth处理时，调用StartMythTransactionHandlerd的handler方法中的mythTransactionManager.begin(point);
                            // 该方法通过coordinatorCommand.execute(new CoordinatorAction(CoordinatorActionEnum.SAVE, mythTransaction)); 将持久化消息发送至此
                            save(coordinatorAction.getMythTransaction());//保存本地补偿事务信息
                        } else if (CoordinatorActionEnum.DELETE.getCode() == code) {//code=1
                            remove(coordinatorAction.getMythTransaction().getTransId());//删除补偿事务信息
                        } else if (CoordinatorActionEnum.UPDATE.getCode() == code) {//code=2
                            update(coordinatorAction.getMythTransaction());//更新补偿事务信息
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    LogUtil.error(LOGGER, "执行协调命令失败：{}", e::getMessage);
                }
            }

        }
    }


}
