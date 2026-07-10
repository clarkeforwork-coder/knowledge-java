# Spring Boot Test：分層測試策略

## 前言

[Mockito 篇](mockito-and-test-doubles.md)把所有依賴 mock 掉，測純邏輯又快又穩。但它測不到**接縫**：Controller 的 `@PathVariable` 有沒有正確綁定？回傳值有沒有被 Jackson 正確序列化成 JSON？Repository 的那句 JPQL 語法對不對？——這些「框架幫我做的事」，mock 全部繞過了。

要測接縫就得**讓真的 Spring 參與**。但「啟動整個應用來測一個 controller」又慢又重。答案是 **Spring Boot 的切片測試（test slices）**：只啟動你要測的那一層。這篇講清楚三層怎麼分、各用什麼、以及為什麼**別把每個測試都寫成完整啟動**——測試金字塔的實戰。

## 技術背景

### 測試金字塔：越下面越多、越上面越少

```
      /\        E2E / @SpringBootTest    ← 少：完整啟動、最慢、最真
     /──\       整合切片 @WebMvcTest…    ← 中：單層＋真框架
    /────\      單元 JUnit + Mockito      ← 多：純邏輯、毫秒級（前兩篇）
   /──────\
```

原則：**能用下層測的，別用上層**。下層快、穩、定位精準；上層慢、脆、但驗的是真接縫。一個健康的專案：大量單元測試 ＋ 適量切片 ＋ 少數端到端。

### 三種 @SpringBootTest 相關工具

| 註解 | 啟動什麼 | 測什麼 | 速度 |
|---|---|---|---|
| （無，純 Mockito） | 什麼都不啟動 | 一個類別的邏輯 | 毫秒 |
| **`@WebMvcTest`** | 只有 web 層（Controller＋MVC 基建） | 請求對應、參數綁定、JSON 序列化、例外處理 | 快 |
| **`@DataJpaTest`** | 只有 JPA 層（EntityManager＋內嵌 DB） | Repository 查詢、entity 對應 | 中 |
| **`@SpringBootTest`** | **整個** ApplicationContext | 跨層整合、真實配置 | 慢 |

切片的精髓：**只裝配你要測的那一層，其他層用 `@MockBean` 換掉**。

### @WebMvcTest：只測 web 層

```java
@WebMvcTest(GreetController.class)     // 只載入這個 controller 與 MVC 基建——不連 DB、不建 service
class GreetControllerTest {
    @Autowired MockMvc mvc;             // 切片自動裝配 MockMvc
    @MockBean GreetService service;     // service 用 mock 換掉——邊界清楚
}
```

`@MockBean` 是 `@Mock` 的 Spring 版——它把一個 mock **放進 Spring 容器**取代真 bean（`@Mock` 只是普通物件）。這裡的分工很清楚：**真的 web 層（[MVC 篇](../03-spring-to-spring-boot/spring-mvc-request-flow.md)那七站）＋ 假的 service**——測的正好是「HTTP ↔ controller」這道接縫，service 的邏輯留給它自己的單元測試。

### @DataJpaTest：只測資料層

```java
@DataJpaTest                           // 只載入 JPA、用內嵌 H2、每個測試自動 rollback
class PolicyRepositoryTest {
    @Autowired PolicyRepository repo;
    @Autowired TestEntityManager em;   // 直接操作 persistence context 準備資料
}
```

三個內建便利：**內嵌 DB**（不碰真資料庫）、**每個測試後自動 rollback**（測試互不污染——[一級快取篇](../08-data-access/deep-hibernate-first-level-cache.md)的交易級隔離用在測試上）、**只載 JPA 相關 bean**（快）。它測的是[N+1 篇](../08-data-access/jpa-lazy-loading-and-n-plus-1.md)那些「JPQL 對不對、關聯對應對不對」——這些 mock 測不到，必須真的打到（內嵌）資料庫。

### @SpringBootTest：完整整合

啟動整個 context，測「所有層接起來對不對」。搭配 `@AutoConfigureMockMvc` 或 `webEnvironment = RANDOM_PORT` 發真 HTTP。**強大但要克制**——每個都完整啟動，測試套件會從幾秒膨脹到幾分鐘。只保留給「關鍵路徑的端到端驗證」。

## 實際案例

驗證環境：Spring Boot 3.2 ＋ `@WebMvcTest`（Docker Maven 實測，綠）。

### @WebMvcTest 切片實測

被測：`GreetController` 收 `/greet/{name}` → 呼叫 `GreetService` → 回字串。

```java
@WebMvcTest(GreetController.class)
class GreetControllerTest {
    @Autowired MockMvc mvc;
    @MockBean GreetService service;

    @Test
    void greet_returnsFromService() throws Exception {
        when(service.greet("Clarke")).thenReturn("哈囉，Clarke");   // service 被 mock
        mvc.perform(get("/greet/Clarke"))                          // 發真的 MVC 請求
           .andExpect(status().isOk())                             // 真的走了 MVC 管線
           .andExpect(content().string("哈囉，Clarke"));           // 真的做了序列化
        verify(service).greet("Clarke");                           // controller 有正確呼叫 service
    }
}
```

實測：`Tests run: 1, Failures: 0`。這個測試**真的走了 [MVC 的七站管線](../03-spring-to-spring-boot/spring-mvc-request-flow.md)**——`{name}` 被 `@PathVariable` 綁成 "Clarke"、回傳值被序列化、狀態碼是 200——這些 Mockito 純單元測試碰不到的接縫，全驗到了；而 service 被 mock，所以測試專注在 web 層、不受 service 邏輯干擾。

**踩坑實錄**：這個測試第一次在我累積了 Hibernate 等依賴的專案裡跑，撞到 `slf4j-api` 版本衝突（舊版 vs Boot 的 logback）——切片測試會啟動一部分真 context，**classpath 上的依賴衝突會直接讓它爆**（純 Mockito 測試不啟動 context 就沒事）。教訓：切片測試對依賴環境敏感，乾淨的專案結構是前提。

## 技術優缺點

### 切片測試買到的

- **測到接縫**：參數綁定、序列化、JPQL——框架替你做的事，只有讓框架參與才測得到
- **比完整啟動快**：只裝配一層，啟動成本是 `@SpringBootTest` 的零頭
- **邊界清楚**：`@MockBean` 明確切開「這次測哪一層、其他層假裝」

### 代價與紀律

- **比單元測試慢一個量級**：切片仍要啟動部分 context（實測 1 秒 vs 單元的毫秒）——**別把能用 Mockito 測的邏輯寫成 @WebMvcTest**
- **依賴環境敏感**：classpath 衝突直接讓 context 載入失敗（實測的 slf4j 坑）
- **`@SpringBootTest` 要克制**：完整啟動最真也最慢——濫用會讓 CI 從幾秒變幾分鐘，測試一慢就沒人願意跑，防護就形同虛設
- 心法：**往金字塔下層擠**——先問「這個能不能用純單元測？」，不能才上切片，接縫的端到端才用完整啟動

## 小結

- 測試金字塔：**大量單元（Mockito）＋ 適量切片 ＋ 少數端到端**——能用下層測就別上上層
- **切片測試只啟動一層**：`@WebMvcTest`（web：參數綁定/序列化，實測走真 MVC 管線）、`@DataJpaTest`（JPA：內嵌 DB＋自動 rollback，測 JPQL）
- **`@MockBean`** 把 mock 放進容器換掉其他層——切片邊界的實現
- `@SpringBootTest` 完整啟動、最真最慢——留給關鍵端到端，別濫用
- 切片對 **classpath 衝突敏感**（實測 slf4j 坑）——啟動部分 context 就會撞到

測試工具備齊了——但工具不保證品質。什麼樣的程式碼值得信任、review 該看什麼、壞味道怎麼認，見 [重構與 Code Review 原則](refactoring-and-code-review.md)。

## 常見面試題

1. `@WebMvcTest`、`@DataJpaTest`、`@SpringBootTest` 各測什麼？（提示：web 層/JPA 層/完整；速度遞減）
2. `@MockBean` 和 `@Mock` 差在哪？（提示：進不進 Spring 容器）
3. 為什麼不建議所有測試都用 `@SpringBootTest`？（提示：啟動成本、測試金字塔、CI 變慢沒人跑）

## 延伸閱讀

- [Spring Boot: Testing](https://docs.spring.io/spring-boot/reference/testing/index.html) — 全部 test slice 註解的官方清單
- [Martin Fowler: Test Pyramid](https://martinfowler.com/articles/practical-test-pyramid.html) — 分層測試策略的經典論述
