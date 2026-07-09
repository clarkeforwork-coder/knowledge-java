# Hibernate 一級快取與 dirty checking

## 前言

一段讓初學者困惑、讓老手依賴的程式碼：

```java
Account a = em.find(Account.class, id);
a.setBalance(a.getBalance().add(new BigDecimal("500")));
// 沒有 em.save()、沒有 em.merge()、沒有 em.update()……
// commit 之後，資料庫的餘額真的變了
```

**改個欄位、什麼都沒呼叫，資料就落庫了**——這不是魔法，是 Hibernate 的 **persistence context（一級快取）** 加 **dirty checking** 在背後工作。[前面三篇](jpa-lazy-loading-and-n-plus-1.md)把 JPA 當「查詢工具」用，這篇拆開它真正的心臟：session 裡那個追蹤一切的快取。搞懂它，`@Transactional` 方法裡「該不該呼叫 save」「為什麼我的改動沒生效」這類日常困惑會一次澄清。

## 技術背景

### persistence context：session 的記憶體帳本

每個 `EntityManager`（＝一個 Hibernate session、通常對應一個 [`@Transactional`](../03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md) 方法）內部有一個 **persistence context**——一個 `Map<主鍵, 實體>`。它就是**一級快取**（first-level cache，永遠開著、無法關閉）。所有經手的實體都被它「託管」（managed 狀態）。

由此得到兩個直接行為：

- **同一 session 內用主鍵查同一筆，第二次不發 SQL**——直接回快取裡那個物件（實測見案例一）。而且回的是**同一個 Java 物件**（`==` 為 true）——這保證了 session 內的實體身分一致（identity guarantee）
- 快取的生命週期＝session 的生命週期——session 一關（交易結束），快取全部清空。所以它是**交易級**的快取，不是全域快取（全域的是二級快取，另一個主題）

### dirty checking：它記得你進門時的樣子

實體被託管的那一刻，Hibernate 存了一份**快照（snapshot）**——欄位的原始值。到了 **flush** 時機，它把每個託管實體的**當前值和快照逐欄比對**，發現不一樣的就自動生成 `UPDATE`。這就是「改欄位不呼叫 save 也落庫」的真相（實測案例二）：**save 是多餘的——托管實體的變更本來就會被偵測**。

推論一個很多人的誤區：`em.merge()` / `save()` 是給**游離（detached）實體**用的（從別的 session 來的、或 session 關了之後的）——**對當前 session 託管的實體呼叫 save 是無效功**，Hibernate 本來就在追蹤它。

### flush：什麼時候真的發 SQL

flush ＝「把記憶體帳本的變更同步到資料庫」。三個觸發時機：

1. **交易 commit 前**（最主要——實測的那次 UPDATE 就在這）
2. **執行查詢前**（Hibernate 怕你查到過期資料，先把 pending 的變更 flush 掉）
3. 手動 `em.flush()`

關鍵理解：**flush ≠ commit**。flush 只是發送 SQL（此時交易還沒結束、還能 rollback）；commit 才是讓它永久生效。所以「flush 了但 rollback」＝SQL 發了又被撤銷。

### write-behind：批次與延遲的紅利

persistence context 還帶來 **write-behind（延遲寫）**：session 內連續改 10 個實體，不是改一個發一條 SQL——而是攢到 flush 時**一次批次發送**。這是效能紅利（減少往返、可 JDBC batch），也是[前一篇 N+1](jpa-lazy-loading-and-n-plus-1.md)之外，「ORM 發的 SQL 跟你的程式碼順序對不上」的另一個原因。

## 實際案例

驗證環境：Hibernate 6.4 ＋ H2，`Statistics` 數 SQL（Docker Maven 實測）。

### 案例一：一級快取——第二次查詢不發 SQL

```java
Account a1 = em.find(Account.class, 10L);
Account a2 = em.find(Account.class, 10L);   // 同 session、同主鍵
```

實測：

```
兩次 find 發出 1 條 SQL｜是同一個物件？true
```

兩次 `find`、**只有 1 條 SQL**——第二次直接命中快取；而且 `a1 == a2`，是**同一個 Java 物件**。這也解釋了一個現象：同一交易裡改了 a1，a2 也「跟著變」——因為它們本來就是同一個。

### 案例二：dirty checking——沒呼叫 save 卻 UPDATE

```java
Account a1 = em.find(Account.class, 10L);          // 快照：balance=1000
a1.balance = a1.balance.add(new BigDecimal("500")); // 只改欄位
em.getTransaction().commit();                       // 沒有任何 save/merge
```

實測：

```
commit 後發出 1 條 SQL（沒呼叫 save 卻 UPDATE 了）
驗證餘額：1500.00（1000 + 500 自動落庫）
```

commit 前的自動 flush 把 `balance` 的當前值（1500）跟快照（1000）一比，不一樣 → 生成 `UPDATE`。**你的職責只是「改物件」，落庫是 Hibernate 的事**——這是 JPA 最優雅、也最令人困惑的設計。

## 技術優缺點

### 一級快取＋dirty checking 買到的

- **物件即資料**：改 Java 物件就是改資料，不用寫 UPDATE、不用記得呼叫 save——ORM 的核心體驗
- **session 內身分一致**：同主鍵永遠是同物件（`==`），避免同一筆資料在記憶體裡有多個副本各改各的
- **write-behind 批次**：延遲＋合併發送，減少 DB 往返

### 代價與陷阱

- **困惑一：「我沒 save 為什麼變了」**——托管實體的任何欄位改動都會落庫，包括你不小心改的。**只讀查詢用 `@Transactional(readOnly = true)`**（Hibernate 可略過快照與 dirty check，省記憶體也防誤改）
- **困惑二：「我 save 了為什麼沒變」**——對 detached 實體改了值卻沒 merge 回 session，dirty checking 追蹤不到它
- **記憶體陷阱**：一級快取會託管**這個 session 碰過的所有實體**——批次處理十萬筆的 session，快取撐爆記憶體（[heap OOM](../01-jvm/memory-stack-and-heap.md)）。大批次要定期 `em.flush()` ＋ `em.clear()` 清快取，或用 stateless session
- **flush 時序的意外**：查詢前的自動 flush 可能在你沒預期的時候發 SQL——搭配[前篇的 N+1](jpa-lazy-loading-and-n-plus-1.md)，「ORM 到底何時發了什麼」需要開 SQL 日誌才看得清

## 小結

- **persistence context ＝一級快取**：session 內的 `Map<主鍵,實體>`，永遠開、交易級——同主鍵第二次查不發 SQL 且回同一物件（實測 1 條 SQL、`==` true）
- **dirty checking**：託管時存快照，flush 時逐欄比對自動 UPDATE——**改欄位不用 save**（實測餘額自動落庫）
- `save`/`merge` 是給 **detached 實體**的；對託管實體呼叫是無效功
- **flush ≠ commit**：flush 發 SQL（commit 前／查詢前／手動），commit 才永久生效；write-behind 批次延遲
- 兩個陷阱：只讀用 `readOnly` 防誤改省開銷、大批次 `flush()+clear()` 防 heap OOM

**至此 08 資料存取章完軌**——從[連線池](jdbc-and-connection-pool.md)、[N+1](jpa-lazy-loading-and-n-plus-1.md)、[鎖](transactions-and-database-locks.md)到這篇的快取心臟，一條「ORM 遮了什麼、你必須看穿什麼」的線貫穿到底。

## 常見面試題

1. 為什麼改了實體欄位、沒呼叫 save，資料也會更新？（提示：persistence context 快照＋dirty checking＋flush）
2. 一級快取是什麼層級的快取？和二級快取差在哪？（提示：session 級 vs 全域；永遠開 vs 需配置）
3. flush 和 commit 的差別？（提示：發 SQL vs 永久生效；flush 後仍可 rollback）

## 延伸閱讀

- [Hibernate User Guide: Flushing](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#flushing) — flush 時機與 dirty checking 的官方定義
- [Vlad Mihalcea: The anatomy of Hibernate dirty checking](https://vladmihalcea.com/the-anatomy-of-hibernate-dirty-checking/) — 快照比對機制的深入剖析
