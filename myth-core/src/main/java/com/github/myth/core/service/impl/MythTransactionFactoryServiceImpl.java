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

package com.github.myth.core.service.impl;

import com.github.myth.common.bean.context.MythTransactionContext;
import com.github.myth.common.enums.MythRoleEnum;
import com.github.myth.core.service.MythTransactionFactoryService;
import com.github.myth.core.service.handler.ActorMythTransactionHandler;
import com.github.myth.core.service.handler.LocalMythTransactionHandler;
import com.github.myth.core.service.handler.StartMythTransactionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * <p>Description: .</p>
 *
 * @author xiaoyu(Myth)
 * @version 1.0
 * @date 2017/11/30 10:08
 * @since JDK 1.8
 */
@Component
public class MythTransactionFactoryServiceImpl implements MythTransactionFactoryService {


    private final MythTransactionManager mythTransactionManager;

    @Autowired
    public MythTransactionFactoryServiceImpl(MythTransactionManager mythTransactionManager) {
        this.mythTransactionManager = mythTransactionManager;
    }

    /**
     * 返回 实现TxTransactionHandler类的名称
     *
     * @param context 事务上下文
     * @return Class<T>
     * @throws Throwable 抛出异常
     */
    @Override
    public Class factoryOf(MythTransactionContext context) throws Throwable {
        //如果事务还没开启或者 myth事务上下文context是空，且mythTransactionManager.isBegin()=false 那么应该进入发起调用
        if (!mythTransactionManager.isBegin() && Objects.isNull(context)) {
            return StartMythTransactionHandler.class;
        } else {
            // makePayment中调用accountService.payment(DTO)方法时，触发的@Myth注解拦截处理进入这里时，线程中在ThreadLocal中存储的事务上下文不为空了（在StartMythTransactionHandler中的调用mythTransactionManager.begin(point);已设置发起者角色的上下文）
            // 调用accountService.payment(DTO)方法时所以这里返回ActorMythTransactionHandler.class
            if (context.getRole() == MythRoleEnum.LOCAL.getCode()) {
                return LocalMythTransactionHandler.class;
            }
            return ActorMythTransactionHandler.class;
        }
    }
}
