# Spring Boot 自動配置

## @SpringBootApplication 裡面是什麼

```java
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootConfiguration      // 本身是一個 @Configuration
@EnableAutoConfiguration      // 啟用自動配置 —— 魔法的來源
@ComponentScan(               // 從啟動類所在 package 往下掃描元件
    excludeFilters = {
        @Filter(type = FilterType.CUSTOM, classes = {TypeExcludeFilter.class}),
        @Filter(type = FilterType.CUSTOM, classes = {AutoConfigurationExcludeFilter.class})
    })
public @interface SpringBootApplication { ... }
```

開發者只需在啟動類加上 `@SpringBootApplication`，Spring Boot 就會自動完成
元件掃描與各種基礎設施的配置（DataSource、JPA、MVC…），
依據 classpath 上有什麼依賴來決定要配置什麼。

## 設定檔：properties 與 yaml

同一份設定的兩種寫法。`application.properties`：

```properties
# 資料庫連線設定
spring.datasource.url=jdbc:mysql://localhost:3306/mydatabase
spring.datasource.username=root
spring.datasource.password=password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# 連線池設定
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.idle-timeout=30000

# JPA 設定
spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
```

`application.yml`（層級化，適合設定多的專案）：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydatabase
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver
```

## 重點

- 自動配置的原則是「有預設值、可覆蓋」：不寫設定用預設，寫了設定就以你的為準
- 機密（密碼、金鑰）不要寫死在設定檔，用環境變數或外部化設定注入
