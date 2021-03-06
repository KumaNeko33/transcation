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
package com.github.myth.core.spi.repository;

import com.alibaba.druid.pool.DruidDataSource;
import com.github.myth.common.bean.entity.MythParticipant;
import com.github.myth.common.bean.entity.MythTransaction;
import com.github.myth.common.config.MythConfig;
import com.github.myth.common.config.MythDbConfig;
import com.github.myth.common.enums.RepositorySupportEnum;
import com.github.myth.common.exception.MythException;
import com.github.myth.common.exception.MythRuntimeException;
import com.github.myth.common.serializer.ObjectSerializer;
import com.github.myth.common.utils.RepositoryPathUtils;
import com.github.myth.core.helper.SqlHelper;
import com.github.myth.core.spi.CoordinatorRepository;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * @author xiaoyu
 */
@SuppressWarnings("unchecked")
public class JdbcCoordinatorRepository implements CoordinatorRepository {


    private Logger logger = LoggerFactory.getLogger(JdbcCoordinatorRepository.class);

    private DruidDataSource dataSource;


    private String tableName;

    private ObjectSerializer serializer;

    @Override
    public void setSerializer(ObjectSerializer serializer) {
        this.serializer = serializer;
    }

    @Override
    public int create(MythTransaction mythTransaction) {
        StringBuilder sql = new StringBuilder()
                .append("insert into ")
                .append(tableName)
                .append("(trans_id,target_class,target_method,retried_count,create_time,last_time,version,status,invocation,role,error_msg)")
                .append(" values(?,?,?,?,?,?,?,?,?,?,?)");
        try {

            final byte[] serialize = serializer.serialize(mythTransaction.getMythParticipants());
            return executeUpdate(sql.toString(),
                    mythTransaction.getTransId(),
                    mythTransaction.getTargetClass(),
                    mythTransaction.getTargetMethod(),
                    mythTransaction.getRetriedCount(),
                    mythTransaction.getCreateTime(),
                    mythTransaction.getLastTime(),
                    mythTransaction.getVersion(),
                    mythTransaction.getStatus(),
                    serialize,
                    mythTransaction.getRole(),
                    mythTransaction.getErrorMsg());

        } catch (MythException e) {
            e.printStackTrace();
            return 0;
        }
    }

    @Override
    public int remove(String transId) {
        String sql = "delete from " + tableName + " where trans_id = ? ";
        return executeUpdate(sql, transId);
    }

    /**
     * 更新数据
     *
     * @param mythTransaction 事务对象
     * @return rows 1 成功  失败需要抛异常
     */
    @Override
    public int update(MythTransaction mythTransaction) throws MythRuntimeException {

        final Integer currentVersion = mythTransaction.getVersion();
        mythTransaction.setLastTime(new Date());
        mythTransaction.setVersion(mythTransaction.getVersion() + 1);
        //tableName=myth_order_service,在order项目初始化时已经设置，而filter过滤dubbo服务的接口也属于order的过滤
        String sql = "update " + tableName +
                " set last_time = ?,version =?,retried_count =?,invocation=?,status=?  where trans_id = ? and version=? ";

        try {
            final byte[] serialize = serializer.serialize(mythTransaction.getMythParticipants());

            return executeUpdate(sql,
                    mythTransaction.getLastTime(),
                    mythTransaction.getVersion(),
                    mythTransaction.getRetriedCount(),
                    serialize,
                    mythTransaction.getStatus(),
                    mythTransaction.getTransId(),
                    currentVersion);

        } catch (MythException e) {
            e.printStackTrace();
            throw new MythRuntimeException(e.getMessage());
        }


    }

    /**
     * 更新 List<Participant> mythParticipants对应数据库的invocation 只更新这一个字段数据，即只更新事务表中的 invocation事务参与者
     *
     * @param mythTransaction 实体对象
     */
    @Override
    public int updateParticipant(MythTransaction mythTransaction) throws MythRuntimeException {

        String sql = "update " + tableName +
                " set invocation=?  where trans_id = ?  ";

        try {
            final byte[] serialize = serializer.serialize(mythTransaction.getMythParticipants());

            return executeUpdate(sql, serialize,
                    mythTransaction.getTransId());

        } catch (MythException e) {
            e.printStackTrace();
            throw new MythRuntimeException(e.getMessage());
        }


    }

    /**
     * 更新补偿数据状态
     *
     * @param id     事务id
     * @param status 状态
     * @return rows 1 成功 0 失败
     */
    @Override
    public int updateStatus(String id, Integer status) throws MythRuntimeException {
        String sql = "update " + tableName +
                " set status=?  where trans_id = ?  ";
        return executeUpdate(sql, status, id);
    }


    /**
     * 根据transId获取对象
     *
     * @param transId transId
     * @return TccTransaction
     */
    @Override
    public MythTransaction findByTransId(String transId) {
        String selectSql = "select * from " + tableName + " where trans_id=?";
        List<Map<String, Object>> list = executeQuery(selectSql, transId);
        if (CollectionUtils.isNotEmpty(list)) {
            return list.stream().filter(Objects::nonNull)
                    .map(this::buildByResultMap).collect(Collectors.toList()).get(0);
        }
        return null;
    }

    /**
     * 获取延迟多长时间后（即事务记录的last_time小于 这个时间 的事务记录）的并且状态是开始的事务信息,主要为了防止并发的时候，刚新增的数据被执行
     *
     * @param date 延迟后的时间
     * @return List<MythTransaction>
     */
    @Override
    public List<MythTransaction> listAllByDelay(Date date) {
        String sb = "select * from " +
                tableName +
                " where last_time <?  and status = 2";
//  status:      0:回滚，1：已经提交，2：开始，3：可以发生送消息，4：失败，5：预提交，6：锁定
        List<Map<String, Object>> list = executeQuery(sb, date);
    // 将list内的map元素封装成一个一个 MythTransaction对象（相当于DTO对象)，并返回
        if (CollectionUtils.isNotEmpty(list)) {
            return list.stream().filter(Objects::nonNull)
                    .map(this::buildByResultMap).collect(Collectors.toList());//将list集合转成 包含map（map建立key,对应的值value为 原list集合的元素）的list集合
        }

        return null;
    }


    private MythTransaction buildByResultMap(Map<String, Object> map) {
        MythTransaction mythTransaction = new MythTransaction();
        mythTransaction.setTransId((String) map.get("trans_id"));
        mythTransaction.setRetriedCount((Integer) map.get("retried_count"));
        mythTransaction.setCreateTime((Date) map.get("create_time"));
        mythTransaction.setLastTime((Date) map.get("last_time"));
        mythTransaction.setVersion((Integer) map.get("version"));
        mythTransaction.setStatus((Integer) map.get("status"));
        mythTransaction.setRole((Integer) map.get("role"));
        byte[] bytes = (byte[]) map.get("invocation");
        try {
            final List<MythParticipant> participants = serializer.deSerialize(bytes, CopyOnWriteArrayList.class);
            mythTransaction.setMythParticipants(participants);
        } catch (MythException e) {
            e.printStackTrace();
        }
        return mythTransaction;
    }

    /**
     * 初始化操作
     *
     * @param modelName  模块名称
     * @param mythConfig 配置信息
     */
    @Override
    public void init(String modelName, MythConfig mythConfig) {
        dataSource = new DruidDataSource();
        final MythDbConfig tccDbConfig = mythConfig.getMythDbConfig();
        dataSource.setUrl(tccDbConfig.getUrl());
        dataSource.setDriverClassName(tccDbConfig.getDriverClassName());
        dataSource.setUsername(tccDbConfig.getUsername());
        dataSource.setPassword(tccDbConfig.getPassword());
        dataSource.setInitialSize(tccDbConfig.getInitialSize());
        dataSource.setMaxActive(tccDbConfig.getMaxActive());
        dataSource.setMinIdle(tccDbConfig.getMinIdle());
        dataSource.setMaxWait(tccDbConfig.getMaxWait());
        dataSource.setValidationQuery(tccDbConfig.getValidationQuery());
        dataSource.setTestOnBorrow(tccDbConfig.getTestOnBorrow());
        dataSource.setTestOnReturn(tccDbConfig.getTestOnReturn());
        dataSource.setTestWhileIdle(tccDbConfig.getTestWhileIdle());
        dataSource.setPoolPreparedStatements(tccDbConfig.getPoolPreparedStatements());
        dataSource.setMaxPoolPreparedStatementPerConnectionSize(tccDbConfig.getMaxPoolPreparedStatementPerConnectionSize());
        this.tableName = RepositoryPathUtils.buildDbTableName(modelName);//例如：传过来的modelName是配置中的 order-service，加上前缀 myth_ 并替换"-"为"_"，即 myth_order_service

        executeUpdate(SqlHelper.buildCreateTableSql(tccDbConfig.getDriverClassName(), tableName));
    }


    /**
     * 设置scheme
     *
     * @return scheme 命名
     */
    @Override
    public String getScheme() {
        return RepositorySupportEnum.DB.getSupport();
    }

    private int executeUpdate(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);) {
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject((i + 1), params[i]);
                }
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("executeUpdate-> " + e.getMessage());
        }
        return 0;
    }

    private List<Map<String, Object>> executeQuery(String sql, Object... params) {

        List<Map<String, Object>> list = null;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);) {

            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject((i + 1), params[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int columnCount = md.getColumnCount();
                list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> rowData = new HashMap<>(16);
                    for (int i = 1; i <= columnCount; i++) {
                        rowData.put(md.getColumnName(i), rs.getObject(i));
                    }
                    list.add(rowData);
                }
            }

        } catch (SQLException e) {
            logger.error("executeQuery-> " + e.getMessage());
        }
        return list;
    }
}
