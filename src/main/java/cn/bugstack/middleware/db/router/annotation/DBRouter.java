package cn.bugstack.middleware.db.router.annotation;

import java.lang.annotation.*;


/**
 * @description: 路由注解
 * @author: pengchang
 * @date: 2021/9/22
 
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface DBRouter {

    /** 分库分表字段 */
    String key() default "";

}
