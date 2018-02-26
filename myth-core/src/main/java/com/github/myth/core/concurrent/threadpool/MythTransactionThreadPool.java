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
package com.github.myth.core.concurrent.threadpool;


import com.github.myth.common.config.MythConfig;
import com.github.myth.common.enums.BlockingQueueTypeEnum;
import com.github.myth.common.enums.RejectedPolicyTypeEnum;
import com.github.myth.common.utils.LogUtil;
import com.github.myth.core.concurrent.threadpool.policy.AbortPolicy;
import com.github.myth.core.concurrent.threadpool.policy.BlockingPolicy;
import com.github.myth.core.concurrent.threadpool.policy.CallerRunsPolicy;
import com.github.myth.core.concurrent.threadpool.policy.DiscardedPolicy;
import com.github.myth.core.concurrent.threadpool.policy.RejectedPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author xiaoyu
 */
@Component
public class MythTransactionThreadPool {

    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(MythTransactionThreadPool.class);

    private static final String THREAD_FACTORY_NAME = "tccTransaction";
    private static final int MAX_ARRAY_QUEUE = 1000;

    private MythConfig mythConfig;

    private ScheduledExecutorService scheduledExecutorService;

    private ExecutorService fixExecutorService;

    private static final ScheduledExecutorService SCHEDULED_THREAD_POOL_EXECUTOR =
            new ScheduledThreadPoolExecutor(1,
                    MythTransactionThreadFactory.create(THREAD_FACTORY_NAME, true));


    @PostConstruct
    public void init() {
        scheduledExecutorService = new ScheduledThreadPoolExecutor(1,
                MythTransactionThreadFactory.create(THREAD_FACTORY_NAME, true));

        fixExecutorService = new ThreadPoolExecutor(mythConfig.getCoordinatorThreadMax(),
                mythConfig.getCoordinatorThreadMax(), 0, TimeUnit.MILLISECONDS,
                createBlockingQueue(),
                MythTransactionThreadFactory.create(THREAD_FACTORY_NAME, false), createPolicy());

    }


    @Autowired
    public MythTransactionThreadPool(MythConfig mythConfig) {
        this.mythConfig = mythConfig;
    }


    private RejectedExecutionHandler createPolicy() {
//        mythConfig的配置<property name="rejectPolicy" value="Abort"/> fromString后对应ABORT_POLICY
        RejectedPolicyTypeEnum rejectedPolicyType = RejectedPolicyTypeEnum.fromString(mythConfig.getRejectPolicy());
        switch (rejectedPolicyType) {
            case BLOCKING_POLICY:
                return new BlockingPolicy();
            case CALLER_RUNS_POLICY:
                return new CallerRunsPolicy();
            case ABORT_POLICY:
                return new AbortPolicy();
            case REJECTED_POLICY:
                return new RejectedPolicy();
            case DISCARDED_POLICY:
                return new DiscardedPolicy();
            default:
                return new DiscardedPolicy();
        }
    }

    private BlockingQueue<Runnable> createBlockingQueue() {
        BlockingQueueTypeEnum queueType = BlockingQueueTypeEnum.fromString(mythConfig.getBlockingQueueType());
        switch (queueType) {
            case LINKED_BLOCKING_QUEUE:
                return new LinkedBlockingQueue<>();
            case ARRAY_BLOCKING_QUEUE:
                return new ArrayBlockingQueue<>(MAX_ARRAY_QUEUE);
            case SYNCHRONOUS_QUEUE:
                return new SynchronousQueue<>();
            default:
                return new LinkedBlockingQueue<>();
        }

    }

    public ExecutorService newCustomFixedThreadPool(int threads) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                createBlockingQueue(),
                MythTransactionThreadFactory.create(THREAD_FACTORY_NAME, false), createPolicy());
    }

    public ExecutorService newFixedThreadPool() {
        return fixExecutorService;
    }

    public ExecutorService newSingleThreadExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                createBlockingQueue(),
                MythTransactionThreadFactory.create(THREAD_FACTORY_NAME, false), createPolicy());
    }

    public ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return SCHEDULED_THREAD_POOL_EXECUTOR;
    }

    public ScheduledExecutorService newScheduledThreadPool() {
        return scheduledExecutorService;
    }


}

