package com.qqlin.oa.service;

import com.qqlin.oa.entity.OperationLog;
import com.qqlin.oa.mapper.OperationLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写操作日志。
 *
 * 核心是这一行：propagation = REQUIRES_NEW。
 *
 * 为什么不能跟着业务走同一个事务：
 *   假设审批请假的事务最后回滚了。如果日志和业务在同一个事务里，
 *   日志会跟着一起被回滚掉 —— 结果就是「这次操作在数据库里没留下任何痕迹」。
 *   而操作日志存在的意义恰恰是「不管成没成，都要留下痕迹」，
 *   尤其是失败的操作，往往比成功的更值得查。
 *
 * REQUIRES_NEW 的做法是：把当前事务挂起，另开一个独立事务写日志并提交，
 * 再恢复原来的事务。这样无论业务事务最后提交还是回滚，日志都已经落库了。
 *
 * 代价：一次操作要占两个数据库连接（业务一个、日志一个）。
 * 高并发下要留意连接池大小。真到那个量级，日志通常改成写 MQ 或本地文件异步落盘。
 */
@Service
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    public OperationLogService(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    /**
     * 保存一条操作日志。
     *
     * 注意这里【故意不捕获异常】：异常要抛给调用方（切面）去处理。
     * 如果在带 @Transactional 的方法内部把异常吞掉，Spring 在提交时会发现
     * 事务已被标记成 rollback-only，然后抛出 UnexpectedRollbackException ——
     * 本来想「吞掉错误」，结果换了个更难排查的错误。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(OperationLog record) {
        operationLogMapper.insert(record);
    }
}
