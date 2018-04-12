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

package com.github.myth.dubbo.interceptor;

import com.github.myth.core.interceptor.AbstractMythTransactionAspect;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;


/**
 * @author xiaoyu
 */
@Aspect //@Aspect的作用：把当前类标识为一个切面供容器读取
@Component
public class DubboMythTransactionAspect extends AbstractMythTransactionAspect implements Ordered {

    //子类设置父类的变量值，什么设计模式？
    @Autowired
    public DubboMythTransactionAspect(DubboMythTransactionInterceptor dubboMythTransactionInterceptor) {
        super.setMythTransactionInterceptor(dubboMythTransactionInterceptor);//设置父类AbstractMythTransactionAspect的切面拦截器为 DubboMythTransationInterceptor
    }


    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
