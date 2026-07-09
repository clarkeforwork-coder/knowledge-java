# JPA/Hibernate：lazy loading 與 N+1

## 前言

[JDBC 篇](jdbc-and-connection-pool.md)結尾的預警在這裡兌現。一段人畜無害的程式碼：

```java
List<Author> authors = repo.findAll();          // 查作者
for (Author a : authors) {
    total += a.getBooks().size();               // 只是讀個關聯
}
```

看起來查了一次。實測——**5 位作者發了 6 條 SQL**；1000 位作者就是 1001 條。這就是 **N+1**，ORM 世界最著名的效能陷阱：**方便到讓你看不見自己在發 SQL**。這篇把 N+1 抓到現行（Hibernate 統計數字為證），講清楚它的成因鏈（lazy loading → proxy → session 邊界），以及三種解法怎麼選。

## 技術背景

### lazy loading：ORM 的核心魔術與代價

JPA 的關聯預設載入策略：

| 關聯 | 預設 | 意思 |
|---|---|---|
| `@OneToMany`／`@ManyToMany` | **LAZY** | 集合不主動載，用到才查 |
| `@ManyToOne`／`@OneToOne` | **EAGER** | 預設就載（常是壞事——見下） |

lazy 的實作：`author.getBooks()` 拿到的不是真的 List，是一個 **proxy**（[03 章 CGLIB](../03-spring-to-spring-boot/deep-transactional-self-invocation.md) 的同款技術，Hibernate 自己的版本）。你一碰它（`.size()`、遍歷），proxy 才**當場發一條 SQL** 去補資料。lazy 本身是好設計——不查用不到的東西；**壞的是「在迴圈裡碰 lazy 關聯」**。

### N+1 的成因鏈

```
查 N 個 Author（1 條 SQL）
  └─ 迴圈裡每個 a.getBooks() 碰 lazy proxy
       └─ 每次補一條 SQL 查該 author 的 books  → N 條
                                          ────────
                                          總計 1 + N 條
```

**1** 是主查詢、**N** 是每個結果各補一刀。資料量小時測不出來（測試資料庫兩三筆），資料一大就線性爆炸——跟[裝箱](../02-language-core/autoboxing-unboxing.md)、[List.contains 進迴圈](../04-collections/collections-overview.md)同一個家族：**單看一行沒問題，放進迴圈就是災難**。

### 三種解法

**解法一：JOIN FETCH（最常用）** —— 在查詢時就一次撈齊：

```java
select distinct a from Author a join fetch a.books
```

一條 SQL 用 JOIN 把作者和書全撈回來（實測 6 條 → 1 條）。缺點：多個集合同時 fetch 會產生笛卡爾積、且 fetch ＋ 分頁在記憶體裡分頁（有陷阱）。

**解法二：`@EntityGraph`** —— 宣告式指定「這次查詢要連帶載入哪些關聯」，Spring Data 的 repository 方法上加 `@EntityGraph(attributePaths = "books")`，比 JOIN FETCH 可讀、可複用。

**解法三：batch fetching** —— `@BatchSize(size = 20)` 或 `hibernate.default_batch_fetch_size`：不消滅 N，而是把 N 條合併成 `where author_id in (?, ?, …)` 的幾條——1+N 變 1+(N/batch)。適合「真的需要 lazy、但要控制條數」的場景。

### EAGER 是更隱蔽的陷阱

把關聯改 `fetch = EAGER` **不是解法**——它只是把「用到才查」變成「每次都查」，N+1 可能照樣發生（EAGER 集合在某些查詢路徑下仍逐一補），還讓你**失去控制權**（每次查 Author 都強制拖著 books，即使這次用不到）。現代共識：**關聯一律 LAZY，需要時在查詢層顯式 fetch**——把「載入策略」從實體定義（一次寫死）移到查詢（按需決定）。

### LazyInitializationException：另一面

lazy proxy 補資料**需要一個活著的 session**。如果實體離開了 session 邊界（`@Transactional` 方法返回後、傳到 view 層）才碰 lazy 關聯——`LazyInitializationException`。這是 N+1 的雙胞胎：一個是「session 內查太多次」，一個是「session 外查不到」。正解同樣是**在 session 內就把需要的資料 fetch 齊**（別靠 `OpenSessionInView` 這種把 session 撐到 view 層的偷懶開關——它讓 N+1 更難發現）。

## 實際案例

驗證環境：Hibernate 6.4 ＋ H2，用 Hibernate `Statistics` 數實際發出的 SQL 條數（Docker Maven 實測）。

### 情境一：LAZY 集合在迴圈裡 → N+1

```java
List<Author> authors = em.createQuery("select a from Author a", Author.class).getResultList();
for (Author a : authors) books += a.getBooks().size();   // 碰 lazy
```

實測（5 位作者、每人 3 本書）：

```
>>> 5 位作者、15 本書 -> 共發出 6 條 SQL
```

**6 ＝ 1（查作者）＋ 5（每位補一條查書）**。這個 6 隨作者數線性成長——正式環境的 1000 位作者，就是 1001 條 SQL 往資料庫砸，每條都是一次[網路往返＋連線佔用](jdbc-and-connection-pool.md)。

### 情境二：JOIN FETCH → 一條解決

```java
select distinct a from Author a join fetch a.books
```

實測（同樣資料）：

```
>>> 5 位作者、15 本書 -> 共發出 1 條 SQL
```

**6 → 1**，資料完全一樣。差別只在「有沒有告訴 ORM 你等一下要用 books」。這就是 N+1 的解法本質：**把「隱式的按需補查」換成「顯式的一次撈齊」**。

## 技術優缺點

### ORM（JPA/Hibernate）的價值與代價

- ✅ 物件導向操作資料、跨資料庫可攜、省掉[裸 JDBC 的樣板](jdbc-and-connection-pool.md)
- ❌ **抽象洩漏**：SQL 被藏起來，讓你看不見自己發了幾條——N+1、笛卡爾積、無意識的全表掃描都源於此
- 核心紀律：**ORM 讓你「不用寫 SQL」，但不讓你「不用懂 SQL」**——出效能問題時，開 `show_sql` 或統計數字，看它到底發了什麼

### N+1 的實務對策

- **開發期就開 SQL 日誌**：`hibernate.show_sql` 或統計——N+1 在你眼皮底下發生，看得到就治得了
- **預設 LAZY ＋ 查詢層 fetch**：JOIN FETCH／`@EntityGraph`／batch size 三選一，按場景
- **測試要用夠多的資料**：兩三筆測不出 N+1，得有量級才會現形（跟[並發 bug](../07-concurrency/concurrent-collections.md) 一樣，小樣本是假象）
- entity 設計別碰 record（[契約篇](../04-collections/equals-hashcode-contract.md)警告過）——需要無參建構子、proxy、可變欄位

## 小結

- **lazy loading**：關聯用 proxy 延遲載入，碰到才發 SQL——好設計，壞在「迴圈裡碰」
- **N+1**：1 條主查詢 ＋ N 條逐一補查（實測 5 作者 = 6 條），資料量一大線性爆炸
- 三解法：**JOIN FETCH**（實測 6→1）、`@EntityGraph`（宣告式）、`@BatchSize`（合併成 in 查詢）
- **EAGER 不是解法**——關聯一律 LAZY，載入需求移到查詢層顯式決定
- 雙胞胎陷阱 `LazyInitializationException`：session 外碰 lazy——正解也是 session 內 fetch 齊
- 核心：ORM 讓你不用寫 SQL，但**必須懂 SQL**——開日誌看它發了什麼

資料查得對了，下一個問題是「並發改同一筆」：兩個交易同時改一張保單會怎樣？資料庫的鎖與 JPA 的樂觀／悲觀鎖——見規劃中的〈交易與資料庫鎖〉。

## 常見面試題

1. 什麼是 N+1？舉例說明成因。（提示：lazy proxy＋迴圈；1＋N 的實測數字）
2. N+1 有哪些解法？EAGER 算解法嗎？（提示：JOIN FETCH／EntityGraph／batch；EAGER 為什麼不是）
3. `LazyInitializationException` 為什麼發生？（提示：session 邊界外碰 lazy proxy；OpenSessionInView 的爭議）

## 延伸閱讀

- [Hibernate User Guide: Fetching](https://docs.jboss.org/hibernate/orm/6.4/userguide/html_single/Hibernate_User_Guide.html#fetching) — fetch 策略與 N+1 對策的官方章節
- [Vlad Mihalcea: N+1 query problem](https://vladmihalcea.com/n-plus-1-query-problem/) — Hibernate 專家的 N+1 完整剖析
