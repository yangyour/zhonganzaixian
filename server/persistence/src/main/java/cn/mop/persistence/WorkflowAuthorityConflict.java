package cn.mop.persistence;

import cn.mop.domain.DomainException;
import java.sql.SQLException;

/** Fixed SQLSTATE mapping only: database text and customer data never enter API errors. */
final class WorkflowAuthorityConflict {
    private WorkflowAuthorityConflict() {}
    static void rejectIfConflict(Exception failure) {
        if(failure instanceof SQLException sql && "P7401".equals(sql.getSQLState()))
            throw new DomainException(409,"WORKFLOW_MODEL_CONFLICT","该工单已由另一套流程管理，请使用原处理入口");
    }
}
