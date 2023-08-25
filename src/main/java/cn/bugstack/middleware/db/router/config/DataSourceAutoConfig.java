package cn.bugstack.middleware.db.router.config;

import cn.bugstack.middleware.db.router.DBRouterConfig;
import cn.bugstack.middleware.db.router.DBRouterJoinPoint;
import cn.bugstack.middleware.db.router.dynamic.DynamicDataSource;
import cn.bugstack.middleware.db.router.dynamic.DynamicMybatisPlugin;
import cn.bugstack.middleware.db.router.strategy.IDBRouterStrategy;
import cn.bugstack.middleware.db.router.strategy.impl.DBRouterStrategyHashCode;
import cn.bugstack.middleware.db.router.util.PropertyUtil;
import cn.bugstack.middleware.db.router.util.StringUtils;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * @description: 数据源配置解析。类的加载顺序笔记；https://t.zsxq.com/0fZELdch7 @double
 * 全局围绕 DBRouterJoinPoint 展开，他要啥我们就放啥
 * @author: pengchang
 * @date: 2021/9/22
 */
@Configuration
public class DataSourceAutoConfig implements EnvironmentAware {

    /**
     * 连接池属性
     */
    private static final String TAG_POOL = "pool";

    /**
     * 分库全局属性
     */
    private static final String TAG_GLOBAL = "global";

    /**
     * 数据源配置组
     */
    private Map<String, Map<String, Object>> dataSourceMap = new HashMap<>();

    /**
     * 默认数据源配置
     */
    private Map<String, Object> defaultDataSourceConfig;

    /**
     * 分库数量
     */
    private int dbCount;

    /**
     * 分表数量
     */
    private int tbCount;

    /**
     * 路由字段
     */
    private String routerKey;

    /**
     * 路由目标获取
     */
    @Bean(name = "db-router-point")
    @ConditionalOnMissingBean
    public DBRouterJoinPoint point(DBRouterConfig dbRouterConfig, IDBRouterStrategy dbRouterStrategy) {
        return new DBRouterJoinPoint(dbRouterConfig, dbRouterStrategy);
    }

    @Bean
    public DBRouterConfig dbRouterConfig() {
        return new DBRouterConfig(dbCount, tbCount, routerKey);
    }

    @Bean
    public IDBRouterStrategy dbRouterStrategy(DBRouterConfig dbRouterConfig) {
        return new DBRouterStrategyHashCode(dbRouterConfig);
    }

    /**
     * 魔改StatementHandler - 语句处理器的预处理部分
     */
    @Bean
    public Interceptor plugin() {
        return new DynamicMybatisPlugin();
    }

    private DataSource createDataSource(Map<String, Object> attributes) {
        try {
            DataSourceProperties dataSourceProperties = new DataSourceProperties();
            // MySql四个基本参数
            dataSourceProperties.setUrl(attributes.get("url").toString());
            dataSourceProperties.setUsername(attributes.get("username").toString());
            dataSourceProperties.setPassword(attributes.get("password").toString());
            dataSourceProperties.setDriverClassName(attributes.get("driver-class-name").toString());
            // 连接池
            DataSource dataSource = dataSourceProperties.initializeDataSourceBuilder()
                    .type((Class<DataSource>) Class.forName((String) attributes.get("type-class-name"))).build();
            MetaObject dsMeta = SystemMetaObject.forObject(dataSource);
            Map<String, Object> poolProps = (Map<String, Object>) (attributes.containsKey(TAG_POOL) ?
                    attributes.get(TAG_POOL) : Collections.EMPTY_MAP);
            for (Map.Entry<String, Object> entry : poolProps.entrySet()) {
                // 中划线转驼峰
                String key = StringUtils.middleScoreToCamelCase(entry.getKey());
                if (dsMeta.hasSetter(key)) {
                    dsMeta.setValue(key, entry.getValue());
                }
            }
            return dataSource;
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("can not find datasource type class by class name", e);
        }
    }


    /**
     * 装载配置的数据源
     */
    @Bean
    public DataSource dataSource() {
        // 创建数据源
        Map<Object, Object> targetDataSources = new HashMap<>(dataSourceMap.size());
        for (String dbInfo : dataSourceMap.keySet()) {
            Map<String, Object> objMap = dataSourceMap.get(dbInfo);
//            targetDataSources.put(dbInfo, new DriverManagerDataSource(objMap.get("url").toString(),
//                    objMap.get("username").toString(), objMap.get("password").toString()));
            // 根据objMap创建DataSourceProperties,遍历objMap根据属性反射创建DataSourceProperties
            DataSource ds = createDataSource(objMap);
            targetDataSources.put(dbInfo, ds);
        }

        // 设置数据源
        DynamicDataSource dynamicDataSource = new DynamicDataSource();
        dynamicDataSource.setTargetDataSources(targetDataSources);
//        dynamicDataSource.setDefaultTargetDataSource(new DriverManagerDataSource(defaultDataSourceConfig.get("url").toString(),
//                defaultDataSourceConfig.get("username").toString(), defaultDataSourceConfig.get("password").toString()));
        // db00 为默认数据源
        dynamicDataSource.setDefaultTargetDataSource(createDataSource(defaultDataSourceConfig));

        return dynamicDataSource;
    }

    /**
     * 编程式事务TransactionTemplate 保证事务的原子性
     */
    @Bean
    public TransactionTemplate transactionTemplate(DataSource dataSource) {
        DataSourceTransactionManager dataSourceTransactionManager = new DataSourceTransactionManager();
        dataSourceTransactionManager.setDataSource(dataSource);

        TransactionTemplate transactionTemplate = new TransactionTemplate();
        transactionTemplate.setTransactionManager(dataSourceTransactionManager);
        transactionTemplate.setPropagationBehaviorName("PROPAGATION_REQUIRED");
        return transactionTemplate;
    }

    @Override
    public void setEnvironment(Environment environment) {
        String prefix = "mini-db-router.jdbc.datasource.";

        dbCount = Integer.parseInt(Objects.requireNonNull(environment.getProperty(prefix + "dbCount")));
        tbCount = Integer.parseInt(Objects.requireNonNull(environment.getProperty(prefix + "tbCount")));
        routerKey = environment.getProperty(prefix + "routerKey");

        // 分库分表数据源
        String dataSources = environment.getProperty(prefix + "list");

        Map<String, Object> globalInfo = getProps(environment, prefix + TAG_GLOBAL);
        for (String dbInfo : dataSources.split(",")) {
            Map<String, Object> dataSourceProps = getProps(environment, prefix + dbInfo);
            injectGlobal(dataSourceProps, globalInfo);
            dataSourceMap.put(dbInfo, dataSourceProps);
        }

        // 默认数据源
        String defaultData = environment.getProperty(prefix + "default");
        defaultDataSourceConfig = getProps(environment, prefix + defaultData);
        injectGlobal(defaultDataSourceConfig, globalInfo);
    }

    private Map<String, Object> getProps(Environment environment, String key) {
        try {
            return PropertyUtil.handle(environment, key, Map.class);
        } catch (Exception e) {
            return Collections.EMPTY_MAP;
        }
    }

    private void injectGlobal(Map<String, Object> origin, Map<String, Object> global) {
        for (String key : global.keySet()) {
            if (!origin.containsKey(key)) {
                origin.put(key, global.get(key));
            } else if (origin.get(key) instanceof Map) {
                // 被全局属性覆盖
                injectGlobal((Map<String, Object>) origin.get(key), (Map<String, Object>) global.get(key));
            }
        }
    }
}
