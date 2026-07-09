# JDBC 與連線池

## 前言

你可能從沒直接寫過 JDBC——JPA、MyBatis、`JdbcTemplate` 都把它包起來了。但當線上服務在尖峰時段冒出「Connection is not available, request timed out」、或是「too many connections」把資料庫打趴時，你面對的就是 JDBC 與連線池這一層。**上層框架的效能天花板，是這一層的參數決定的。**

這篇從裸 JDBC 的樣板講起（知道框架替你做了什麼），主軸放在連線池——為什麼非用不可、參數怎麼定、以及一個實測會卡 30 秒的現場：連線洩漏。

## 技術背景

### 裸 JDBC：框架替你藏起來的樣板

```java
try (Connection c = dataSource.getConnection();                        // 資源一
     PreparedStatement ps = c.prepareStatement("select name from t where id = ?")) {  // 資源二
    ps.setInt(1, id);                                                  // 綁參數
    try (ResultSet rs = ps.executeQuery()) {                           // 資源三
        if (rs.next()) return rs.getString("name");
    }
}   // 三個資源反序自動關閉 —— try-with-resources（例外處理篇的實戰）
```

三個要點，每個都是框架替你做掉的事：

- **三層資源都要關**：Connection、Statement、ResultSet 都是 `AutoCloseable`——手寫 finally 幾乎必漏，[try-with-resources](../02-language-core/exception-handling.md) 是唯一正解
- **一律 `PreparedStatement`，不要字串拼 SQL**：`?` 佔位符讓 SQL 與資料分離——這是 **SQL injection 的根本防線**（拼字串＝把使用者輸入當程式碼），順帶讓 DB 能快取執行計畫
- 這些樣板正是 `JdbcTemplate`／JPA 存在的理由——但**它們關的還是這三層**，只是你看不到

### 為什麼一定要連線池：建立連線很貴

一條 DB 連線的成本：TCP 握手、資料庫認證、session 配置——真實網路資料庫是**幾十毫秒**等級。每個 request 現建現關，這筆錢每次都付。

連線池的做法：**啟動時預建一批連線、借還而非建關**。`getConnection()` 從池裡借（拿現成的）、`close()` 不是真關而是**還回池裡**——這顛覆了直覺，也埋下最常見的坑（見下）。**HikariCP** 是現代事實標準（Spring Boot 預設），以極簡與快著稱。

### close() 的語意反轉：還，不是關

```java
try (Connection c = pool.getConnection()) {   // 借
    // 用
}   // close() = 還回池裡，不是真的關閉物理連線
```

**連線洩漏**就是「借了不還」——忘了 `close()`（或沒用 try-with-resources），這條連線永遠回不了池。借光了池裡的連線，下一個 `getConnection()` 就無限等待（實測見案例二）。這也是為什麼 `@Transactional` 方法裡[呼叫遠端 API 是連線池殺手](../03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md)——交易期間連線被佔住不還。

### 連線池參數：比你想的小

- **`maximumPoolSize`**：最容易配錯的一個——**不是越大越好**。連線數超過 DB 的處理能力只會加劇競爭；HikariCP 官方公式 `連線數 = ((核心數 × 2) + 有效磁碟數)`，多數服務**個位數到 20** 就夠。10 條連線服務上千並發是常態（連線只在真正查詢的毫秒級被佔用）
- **`connectionTimeout`**：借不到連線時等多久放棄（預設 30 秒——實測那個卡住的數字）。設太長＝故障時請求全卡著、雪崩；設短一點讓它快速失敗
- **與 DB 端的 `max_connections` 對齊**：所有應用實例的池上限**加總**不能超過 DB 的上限——10 個 pod × 每個 20 條 = 200，DB 若只開 150 就爆

## 實際案例

驗證環境：H2 記憶體 DB ＋ HikariCP（Docker Maven 實測）。

### 案例一：池 vs 每次新建

300 次「取得連線」，實測：

```
每次新建 ×300：24 ms
連線池   ×300：6 ms
```

**4 倍差距——而且這是 H2 記憶體 DB**（建連線幾乎免費）。換成真實網路資料庫，「每次新建」要加上每次的 TCP＋認證往返，差距會是數量級的。連線池的價值隨連線建立成本放大。

### 案例二：連線洩漏——卡 30 秒的現場

池上限 10，故意借了不還：

```
借出第 1 條 … 借出第 10 條
（第 11 次 getConnection——卡住……30 秒後）
池耗盡：HikariPool-1 - Connection is not available, request timed out after 30003ms
```

第 11 條借不到，就在那裡**等滿 `connectionTimeout`（30 秒）才拋例外**。生產環境的長相：某段程式碼漏了 `close()`，服務跑幾小時後連線一條條漏光，然後**所有請求集體卡 30 秒後 500**——監控看到的是「回應時間突然全部飆到 30 秒」。排查靠 HikariCP 的 `leakDetectionThreshold`（超時未還就印 stack trace，直指漏點）。

## 技術優缺點

### 連線池買到的

- **省下每次的建立成本**（實測 4 倍起，真實 DB 更多）
- **並發的閘門**：`maximumPoolSize` 是保護 DB 的限流器——上層再多請求，打到 DB 的並發被池上限鎖死（[執行緒池](../07-concurrency/executorservice-and-thread-pools.md)的 workQueue 同款「有界資源」哲學）

### 代價與紀律

- **close() 必須有**（且用 try-with-resources）——洩漏是最貴的 bug，實測卡 30 秒、生產雪崩
- **參數要對齊 DB**：池上限 × 實例數 ≤ DB max_connections，否則把資料庫打死
- **maxPoolSize 不是越大越好**：超過 DB 處理力反而更慢——用公式起步、壓測驗證
- 上層 ORM 遮蔽了這一層——但**慢查詢、連線耗盡的鍋最後都落在這裡**，參數不能不懂

## 小結

- 裸 JDBC 三層資源（Connection/Statement/ResultSet）都要關——`try-with-resources`；查詢一律 `PreparedStatement`（防 SQL injection ＋ 計畫快取）
- 連線建立很貴 → **連線池借還取代建關**（實測 4 倍，真實 DB 更大）；HikariCP 是預設標準
- **`close()` ＝ 還回池**，不是真關——漏還就是連線洩漏，實測第 11 條卡滿 30 秒 timeout
- 參數：`maximumPoolSize` 個位數到 20（不是越大越好）、`connectionTimeout` 快速失敗、池上限對齊 DB `max_connections`
- 這層是上層框架的效能天花板——ORM 遮住它，但連線耗盡的帳算在它頭上

上層把這些樣板包好之後，新的問題浮現：ORM 太方便，一個 `.getOrders()` 背後可能偷偷發了 100 條 SQL——見規劃中的〈JPA/Hibernate：lazy loading 與 N+1〉。

## 常見面試題

1. 為什麼要用連線池？`close()` 一條 pooled connection 發生什麼事？（提示：建立成本；還而非關、洩漏）
2. `maximumPoolSize` 是越大越好嗎？怎麼定？（提示：不是；DB 處理力、公式、對齊 max_connections）
3. 為什麼查詢要用 PreparedStatement？（提示：SQL injection、執行計畫快取）

## 延伸閱讀

- [HikariCP: About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing) — 「連線數不是越大越好」的權威論證與公式
- [JDBC Basics（官方教學）](https://docs.oracle.com/javase/tutorial/jdbc/basics/index.html) — PreparedStatement 與資源管理
