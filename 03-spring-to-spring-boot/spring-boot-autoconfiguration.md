# Spring Boot 自動配置

## 前言

[演進篇](spring-evolution.md)的結尾停在一個問題：你在 `application.properties` 只寫了一行 DB 連線字串，DataSource、連線池、JdbcTemplate 就全部就位——**Boot 憑什麼知道怎麼建？**

「自動配置」聽起來像魔法，拆開只有一個核心機制：**條件化配置**——「classpath 上有什麼、你已經配了什麼」決定「我幫你配什麼、我退讓什麼」。這篇拆到底：從 `@SpringBootApplication` 的解剖，到用 plain Spring **親手重現** `@ConditionalOnClass`——魔法拆完就只是機制。

## 技術背景

### @SpringBootApplication 解剖：三合一

```java
@SpringBootConfiguration      // 本身是一個 @Configuration
@EnableAutoConfiguration      // 啟用自動配置 —— 魔法的入口
@ComponentScan(               // 從啟動類所在 package 往下掃你的元件
    excludeFilters = { @Filter(...), @Filter(...) })
public @interface SpringBootApplication { ... }
```

三件事各司其職：你的類別掃描（`@ComponentScan`——所以啟動類要放頂層 package）、你的 Java Config（`@SpringBootConfiguration`）、**別人的配置**（`@EnableAutoConfiguration`）——第三個是本篇主角。

### 自動配置的流水線

```
@EnableAutoConfiguration
  │
  ├─ AutoConfigurationImportSelector：去讀候選清單
  │    └─ 每個 jar 的 META-INF/spring/…AutoConfiguration.imports
  │       （Boot 2.7 前是 spring.factories）——純文字列出配置類
  │
  ├─ 逐一評估每個配置類上的「條件」：
  │    @ConditionalOnClass(DataSource.class)   ← classpath 有這個類才考慮
  │    @ConditionalOnMissingBean(DataSource.class) ← 你沒自己配才出手
  │    @ConditionalOnProperty(...)              ← 開關屬性
  │
  └─ 條件全過的配置類生效 → 它的 @Bean 進容器
```

兩個條件註解就是「**有預設值、可覆蓋**」的機制本體：

- **`@ConditionalOnClass`**＝「看菜下飯」：加了 H2 依賴，DataSource 自動配置才醒來——starter 依賴的意義就是「往 classpath 放料，觸發對應的自動配置」
- **`@ConditionalOnMissingBean`**＝「君子之爭」：你自己宣告了同型別的 bean，官方配置**自動退讓**——「覆蓋預設」不需要任何開關，宣告即覆蓋

### 地基是 plain Spring 的 @Conditional

Boot 的所有 `@ConditionalOnXxx` 都是 Spring 3.x `@Conditional` 的延伸——一個介面而已：

```java
class OnClass implements Condition {
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata meta) {
        try { Class.forName(要求的類名, false, ctx.getClassLoader()); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}
```

實測見實際案例——**二十行重現 `@ConditionalOnClass`**，魔法的祛魅儀式。

### 設定檔：值的來源與優先序

```properties
spring.datasource.url=jdbc:h2:mem:test        # 你給「值」，自動配置管「怎麼建」
spring.datasource.hikari.maximum-pool-size=10
```

同一份設定 yaml 寫法層級化，適合設定多的專案。值的**優先序**（高蓋低）：命令列參數 ＞ 環境變數 ＞ profile 專屬檔（`application-prod.properties`）＞ `application.properties` ＞ 自動配置的預設——所以「本機用 H2、正式用 Oracle」只是換一組外部值的事。**機密（密碼、金鑰）不進版控**：用環境變數或外部化配置注入，這條在保險業是合規要求不是建議。

### 魔法失靈時的排查工具

- **`--debug` 啟動**：印出 CONDITIONS EVALUATION REPORT——每個自動配置「為什麼生效／為什麼沒生效」白紙黑字（`matched`／`did not match` 帶原因），這是自動配置問題的第一現場
- **`exclude`**：`@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)`——明確拒絕某個自動配置（比如你要完全手工控制 DataSource 時）

## 實際案例

### 手工重現 @ConditionalOnClass（plain Spring，二十行）

自訂一個 `@RequiresClass` 註解＋`Condition` 實作，掛在兩個 bean 上：

```java
@Bean
@RequiresClass("org.h2.Driver")              // classpath 上有（專案依賴）
String h2Support() { return "H2 相關配置生效"; }

@Bean
@RequiresClass("com.mysql.cj.jdbc.Driver")   // classpath 上沒有
String mysqlSupport() { return "MySQL 相關配置生效"; }
```

實測：

```
h2Support 存在？   true    ← classpath 有 H2 → 配置醒來
mysqlSupport 存在？false   ← 沒有 MySQL → 配置沉睡
```

這二十行就是自動配置的靈魂：**Boot 上千個自動配置類，每一個都是「這個 Condition ＋ 一批 @Bean」的組合**。看懂這個實驗，`spring-boot-autoconfigure.jar` 裡的任何一個配置類你都讀得懂了。

## 技術優缺點

### 條件化配置買到的

- **零設定起步**：加依賴即得合理預設——新專案十分鐘能跑
- **覆蓋即宣告**：`OnMissingBean` 的退讓機制讓客製無痛——不用學「怎麼關掉預設」
- **版本協調**：starter 保證整套依賴版本相容（自己湊版本的年代是真實的痛）

### 代價與紀律

- **不懂機制＝不會排查**：「它自己就動了」的另一面是「它自己就壞了」——`--debug` 的條件報告是必備技能
- **升級的隱形變動**：Boot 版本升級可能改變預設值或條件——release notes 的 breaking changes 要讀（[Validation 篇](exception-handling-and-validation.md)撞過的 `-parameters` 就是 3.2 的實例）
- **classpath 即配置**：多拉一個依賴可能喚醒一整套你不知道的自動配置——依賴要有意識地加

## 小結

- `@SpringBootApplication`＝三合一：掃你的元件＋你的配置＋**啟用自動配置**
- 自動配置流水線：讀各 jar 的 `AutoConfiguration.imports` 清單 → 逐一評估**條件** → 全過才生效
- 兩個關鍵條件：`OnClass`「看菜下飯」（starter 的意義）、`OnMissingBean`「宣告即覆蓋」（有預設可覆蓋的機制本體）
- 地基是 plain Spring 的 `@Conditional`——**實測二十行重現 OnClass**，魔法拆完只是機制
- 排查靠 `--debug` 的條件報告；機密外部化不進版控

**至此全 repo 的 🔧 待翻新清零**——03 章從演進、自動配置、生命週期、交易、MVC、例外處理到 proxy 深水區，「Spring 的魔法都是機制」一線貫穿。

## 常見面試題

1. `@SpringBootApplication` 由哪三個註解組成？各做什麼？（提示：三合一，主角是 EnableAutoConfiguration）
2. 自動配置怎麼做到「有預設值、可覆蓋」？（提示：OnClass 看菜下飯＋OnMissingBean 宣告即覆蓋）
3. 某個自動配置沒生效，怎麼排查？（提示：--debug 的 CONDITIONS EVALUATION REPORT）

## 延伸閱讀

- [Spring Boot 官方文件：Auto-configuration](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html) — 機制與 exclude 的官方說明
- [Spring Boot 官方文件：Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html) — 想自己寫一個 starter 時的完整指南
