# Spring 的演進：XML → Annotation → Boot

## 第一代：全 XML 設定的 IoC Container

所有 bean 都在 XML 手工組裝，依賴注入靠人工維護：

```xml
<!-- 數據源配置 -->
<bean id="dataSource" class="org.apache.commons.dbcp.BasicDataSource" destroy-method="close">
    <property name="driverClassName" value="${jdbc.driverClassName}"/>
    <property name="url" value="${jdbc.url}"/>
    <property name="username" value="${jdbc.username}"/>
    <property name="password" value="${jdbc.password}"/>
</bean>

<!-- Hibernate SessionFactory 配置 -->
<bean id="sessionFactory" class="org.springframework.orm.hibernate3.LocalSessionFactoryBean">
    <property name="dataSource" ref="dataSource"/>
    <property name="mappingResources">
        <list>
            <value>com/example/domain/User.hbm.xml</value>
        </list>
    </property>
</bean>
```

專案一大，XML 就成災難：改一個依賴要翻好幾份設定檔。

## 過渡期：XML 簡化 + Annotation

XML 只留 component-scan，IoC 管理交給 annotation，減少人工維護依賴注入：

```xml
<context:component-scan base-package="com" />
<aop:aspectj-autoproxy />
```

```java
@Service           // 標記這是一個 Service 層元件
@Transactional     // 類級別事務配置
public class UserServiceImpl implements UserService {

    @Autowired     // 自動注入 UserDao
    private UserDao userDao;

    @Cacheable(value = "userCache", key = "#id")
    @Override
    public User getUser(Long id) {
        return userDao.findById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    @CachePut(value = "userCache", key = "#user.id")
    @Override
    public User updateUser(User user) {
        return userDao.save(user);
    }
}
```

## 現代：Spring Boot 自動配置

連 XML 都不用了，一個註解起動整個應用：

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

詳見：[Spring Boot 自動配置](spring-boot-autoconfiguration.md)

## Spring vs Spring Boot 一句話

> Spring 給你所有零件，組裝靠自己；Spring Boot 按照約定幫你組好，
> 你只在需要偏離約定時才寫設定（convention over configuration）。
