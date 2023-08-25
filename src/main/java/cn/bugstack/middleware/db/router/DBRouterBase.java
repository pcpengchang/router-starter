package cn.bugstack.middleware.db.router;

/**
 * @description: 数据源基础配置
 * @author: pengchang
 * @date: 2021/9/22
 
 */
public class DBRouterBase {

    private String tbIdx;

    public String getTbIdx() {
        return DBContextHolder.getTBKey();
    }

}
