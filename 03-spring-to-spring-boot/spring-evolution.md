# Spring 的演進：XML → Annotation → Boot

## 前言

為什麼要學一段「歷史」？兩個很實際的理由：**你會在公司同時遇到三個時代的程式碼**——十年前的 XML 專案還在跑、五年前的 annotation 專案在維護、新專案用 Boot；而且不懂「每一代在解決什麼痛」，就不懂 Boot 替你做掉的是什麼——出問題時連往哪查都不知道。

這篇用同一個需求（配一個 DataSource）走過三個時代，痛點與解法一路對照。

## 技術背景

### 時間軸

| 年代 | 里程碑 | 關鍵字 |
|---|---|---|
| 2004 | Spring 1.0 | XML 定義一切 |
| 2007 | Spring 2.5 | annotation 注入（`@Autowired`、`@Component`） |
| 2009 | Spring 3.0 | Java Config（`@Configuration`＋`@Bean`），XML 可完全退場 |
| 2014 | Spring Boot 1.0 | 自動配置、內嵌容器、starter |
| 2022 | Boot 3.0 | Jakarta EE 9＋、要求 Java 17 |

### 第一代：XML 定義一切

```xml
<bean id="dataSource" class="org.apache.commons.dbcp.BasicDataSource" destroy-method="close">
    <property name="driverClassName" value="${jdbc.driverClassName}"/>
    <property name="url" value="${jdbc.url}"/>
    <property name="username" value="${jdbc.username}"/>
    <property name="password" value="${jdbc.password}"/>
</bean>

<bean id="sessionFactory" class="org.springframework.orm.hibernate3.LocalSessionFactoryBean">
    <property name="dataSource" ref="dataSource"/>
    ...
</bean>
```

**它解決的痛**：物件的組裝從程式碼裡抽出來（IoC 的初衷——[生命週期篇](bean-lifecycle-and-scope.md)的流水線就是從這裡開始的）。**它製造的痛**：設定集中但爆炸——每個 bean、每條依賴都是手工 XML，改一個依賴翻好幾份檔案，重構時 IDE 幫不上忙（字串沒有型別檢查）。

### 第二代：annotation 進駐程式碼

XML 縮到只剩「開關」：

```xml
<context:component-scan base-package="com.example"/>
<aop:aspectj-autoproxy/>
```

bean 的定義與依賴搬進程式碼本身：

```java
@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserDao userDao;

    @Transactional(rollbackFor = Exception.class)
    @Cacheable(value = "userCache", key = "#id")
    public User getUser(Long id) { ... }
}
```

**解決的痛**：定義跟著類別走，重構安全、依賴一目瞭然。Spring 3.0 的 `@Configuration`＋`@Bean` 補上最後一塊——第三方類別（你不能加 annotation 的）也能用 Java 定義，XML 至此**可以**完全退場。**殘留的痛**：基礎設施還是要自己搭——DataSource、交易管理器、MVC、Jackson……每個專案開場都是同一套樣板設定的複製貼上。

### 第三代：Boot 把樣板也做掉

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

Boot 的三根柱子：**自動配置**（看 classpath 決定要配什麼——機制見[下一篇](spring-boot-autoconfiguration.md)）、**starter 依賴**（`spring-boot-starter-web` 一個座標拉齊整套版本相容的依賴）、**內嵌容器**（Tomcat 進 jar，`java -jar` 直接跑——部署模型從「丟 war 進應用伺服器」變成「跑一個程式」，這正是容器化時代需要的形狀）。

一句話總結三代：**Spring 給你所有零件，組裝靠自己；Boot 按約定幫你組好，你只在需要偏離約定時才寫設定**（convention over configuration）。

## 實際案例

### 同一個需求，三個時代：配一個 DataSource

```xml
<!-- 第一代：XML，每個 property 手工填 -->
<bean id="dataSource" class="...BasicDataSource">
    <property name="url" value="${jdbc.url}"/> ...
</bean>
```

```java
// 第二代：Java Config —— 本章驗證專案（TxApp）用的正是這一代的寫法
@Bean
DataSource dataSource() {
    var ds = new DriverManagerDataSource("jdbc:h2:mem:test", "sa", "");
    ds.setDriverClassName("org.h2.Driver");
    return ds;
}
```

```properties
# 第三代：Boot —— 寫「值」就好，「怎麼建」自動配置代勞
spring.datasource.url=jdbc:h2:mem:test
```

注意演進的方向：從「描述**怎麼建**」一路退到「只給**值**」——建造知識逐代從你的專案搬進框架。這條線的終點就是下一篇要拆的黑盒：Boot 憑什麼知道怎麼建？

## 技術優缺點

### 演進買到的

- 每一代都把一種樣板消滅：XML 消滅了 new 與組裝、annotation 消滅了 XML、Boot 消滅了基礎設施樣板
- **部署模型現代化**：內嵌容器讓「一個 jar 就是一個服務」——Docker 化、雲原生的前提

### 每一代的代價

- **魔法逐代加深**：XML 時代一切攤在檔案裡；Boot 時代「它怎麼就動了」——不懂自動配置機制，出問題只能求神（解藥在[下一篇](spring-boot-autoconfiguration.md)）
- **XML 沒有完全死**：遺留系統、某些需要「不改碼調整配置」的場景仍在用——讀懂它仍是企業技能
- convention 的另一面：**偏離約定的成本**——Boot 的預設不合用時，你得知道去哪關（`exclude`、條件註解），這比從零寫更需要理解機制

## 小結

- 三代一條線：**XML（組裝出碼）→ annotation（定義回碼）→ Boot（樣板進框架）**——方向是「建造知識逐代從專案搬進框架」
- 時間軸錨點：2004 / 2007（2.5 annotation）/ 2009（3.0 Java Config）/ 2014（Boot）/ 2022（Boot 3 ＝ Java 17＋Jakarta）
- Boot 三柱：自動配置、starter、內嵌容器——最後一根改變了部署模型
- 一句話：Spring 給零件、Boot 按約定組好——**約定優於配置**
- 企業現實：三代並存，讀懂每一代都是本職技能

「Boot 憑什麼知道怎麼建」的答案是條件化配置——`@SpringBootApplication` 裡藏的機關，見[下一篇：Spring Boot 自動配置](spring-boot-autoconfiguration.md)。

## 常見面試題

1. Spring 和 Spring Boot 的關係？（提示：零件 vs 按約定組裝；Boot 三柱）
2. 從 XML 到 annotation 到 Boot，各解決了什麼痛點？（提示：組裝出碼／定義回碼／樣板進框架）
3. 內嵌容器改變了什麼？（提示：war＋應用伺服器 → 一個 jar 一個服務；容器化的前提）

## 延伸閱讀

- [Spring Boot 官方文件：Introducing Spring Boot](https://docs.spring.io/spring-boot/index.html) — 官方對定位的一句話描述
- [Spring Framework Versions（GitHub Wiki）](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions) — 版本與 JDK 對應的權威表
