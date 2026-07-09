# 交易與資料庫鎖

## 前言

一個真實的保險場景：兩位業務員同時打開同一張保單，各自改了不同欄位、幾乎同時按存檔。結果——**後存的把先存的覆蓋掉了**，先改的那筆資料無聲蒸發。這叫 **lost update（更新遺失）**，是併發資料存取的頭號事故。

[03 章的 @Transactional](../03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md) 講的是「一組操作要嘛全成功要嘛全回滾」（原子性）；這篇補上另一半：**多個交易同時碰同一筆資料時，誰讓誰**。這是 [07 章併發](../07-concurrency/synchronized-and-volatile.md)在資料庫層的續集——`counter++` 的 race condition，換到資料庫就是 lost update，武器則從 synchronized 換成**兩種鎖**。

## 技術背景

### ACID 的 I：隔離級別

交易的隔離級別決定「一個交易能看到別的交易多少未完成的操作」，由鬆到嚴四級——級別越嚴，併發異常越少，但併發度越低：

| 級別 | 擋掉的異常 | 代價 |
|---|---|---|
| READ UNCOMMITTED | （幾乎不擋）髒讀都可能 | 最快、最危險 |
| READ COMMITTED | 髒讀 | Oracle/PostgreSQL 預設 |
| REPEATABLE READ | 髒讀、不可重複讀 | **MySQL 預設** |
| SERIALIZABLE | 全部（含幻讀） | 最安全、最慢 |

三種經典異常一句話：**髒讀**（讀到別人還沒 commit 的資料）、**不可重複讀**（同一筆讀兩次值不同——別人改了 commit）、**幻讀**（同一查詢兩次筆數不同——別人插了新行）。**知道自己的 DB 預設在哪一級**是基本功——MySQL 的 REPEATABLE READ 和 Postgres 的 READ COMMITTED 行為就不一樣。

但隔離級別擋不住 lost update（讀-改-寫跨越交易），得靠鎖。

### 兩種鎖的哲學：賭不衝突 vs 先佔住

**樂觀鎖（optimistic）——賭大家不會撞**：不上任何 DB 鎖，靠一個 **`@Version` 版本欄位**。讀的時候記下版本，寫的時候 `UPDATE ... WHERE id=? AND version=?`——**版本沒被別人動過才更新成功，動過就更新 0 行 → 拋 `OptimisticLockException`**（實測見案例一）。適合**衝突罕見**的場景（多數業務資料）。

**悲觀鎖（pessimistic）——先佔住再說**：讀的當下就上 DB 的行鎖（`SELECT ... FOR UPDATE`），其他交易想碰這筆就**阻塞等待**，直到你提交釋放。適合**衝突頻繁、或衝突代價高**的場景（庫存扣減、賬戶餘額）。

### JPA 怎麼用

```java
// 樂觀鎖：實體加一個版本欄位就啟用，JPA 自動維護
@Entity class Account {
    @Version int version;      // int/long/Timestamp——JPA 每次 update 自動 +1
}

// 悲觀鎖：查詢時指定鎖模式
em.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE);   // = SELECT ... FOR UPDATE
```

### 死結：兩種鎖的共同陰影

悲觀鎖（和 DB 內部鎖）會死結——交易 A 鎖了行 1 等行 2、交易 B 鎖了行 2 等行 1，跟 [07 章 jstack 抓到的 Java 死結](../01-jvm/troubleshooting-toolbox.md)是同一個拓撲，只是換到 DB。多數資料庫會**自動偵測並犧牲一個交易**（拋 deadlock 例外）。預防同 Java：**所有交易以固定順序取鎖**（都先鎖 id 小的）。

## 實際案例

驗證環境：Hibernate 6.4 ＋ H2，兩個 EntityManager 模擬並發交易（Docker Maven 實測）。

### 案例一：樂觀鎖——後提交者敗

兩個交易讀到同一筆（version 都是 0）、各改各的、先後提交：

```java
Account a1 = emA.find(Account.class, 1L);   // A 讀到 version=0
Account a2 = emB.find(Account.class, 1L);   // B 也讀到 version=0
a1.balance += 100;  a2.balance -= 50;
emA.commit();   // A 先提交：UPDATE ... WHERE version=0 → 成功，version→1
emB.commit();   // B 提交：UPDATE ... WHERE version=0 → 0 行（version 已是 1）
```

實測：

```
交易 A 提交成功（version 0→1）
交易 B 提交失敗：OptimisticLockException（讀到的 version 已過期）
```

關鍵在 B 的下場：它**不是覆蓋 A、也不是無聲失敗，而是明確拋例外**——lost update 被轉化成一個「你的資料過期了，請重讀重試」的訊號。應用層接住它、提示使用者或自動重試，資料就安全了。這正是樂觀鎖的價值：**用一個 int 欄位，把無聲的資料覆蓋變成有聲的衝突**。

### 案例二：悲觀鎖——先佔住

```java
Account locked = emC.find(Account.class, 2L, LockModeType.PESSIMISTIC_WRITE);
```

實測確認鎖模式對應 `SELECT ... FOR UPDATE`——交易 C 持鎖期間，任何想改這筆的交易在 **DB 層**阻塞等待，直到 C 提交。差別於樂觀鎖：**衝突在發生前就被擋住**（不用重試），代價是**並發度下降**（後到者真的在等）與死結風險。

## 技術優缺點

### 樂觀 vs 悲觀

| | 樂觀鎖 | 悲觀鎖 |
|---|---|---|
| 機制 | 版本欄位＋更新時比對 | DB 行鎖（FOR UPDATE） |
| 衝突處理 | 事後拋例外，**要重試邏輯** | 事前阻塞，不用重試 |
| 並發度 | 高（不鎖） | 低（後到者等） |
| 死結 | 無 | 有 |
| 適用 | 衝突罕見（多數業務） | 衝突頻繁／代價高（庫存、餘額） |

**預設選樂觀**——大多數業務資料的併發衝突其實罕見，一個 `@Version` 欄位成本極低、並發度最高。只有在「衝突頻繁」或「重試成本高於阻塞」時才上悲觀鎖。

### 實務紀律

- **交易越短越好**：鎖（悲觀）或衝突窗口（樂觀）的持續時間 = 交易長度——[03 章](../03-spring-to-spring-boot/transactional-propagation-and-pitfalls.md)「交易裡別做慢操作」在這裡是雙倍重要，交易裡等一個慢 API＝把鎖也一起佔住
- **樂觀鎖必須配重試**：接住 `OptimisticLockException` 後重讀重試（通常 2-3 次），沒有重試邏輯的樂觀鎖只是把 lost update 換成使用者看到的錯誤頁
- **隔離級別別亂調高**：SERIALIZABLE 能解大多數問題但併發度掉很多——先用鎖解決具體的 lost update，而不是全域調高隔離級別
- 死結預防：固定順序取鎖（同 [07 章](../07-concurrency/thread-basics-and-lifecycle.md)）

## 小結

- 隔離級別擋髒讀/不可重複讀/幻讀，但**擋不住 lost update**——要靠鎖；先知道自己 DB 的預設級別（MySQL RR vs Postgres RC）
- **樂觀鎖**：`@Version` 版本比對，衝突時拋 `OptimisticLockException`（實測後提交者敗）——把無聲覆蓋變有聲衝突，**必須配重試**
- **悲觀鎖**：`SELECT ... FOR UPDATE` 事前佔住，後到者阻塞——衝突頻繁/代價高才用，有死結風險
- 預設樂觀、交易越短越好、死結靠固定取鎖順序——這是 [07 章 race condition](../07-concurrency/synchronized-and-volatile.md) 在資料庫層的對應解

樂觀鎖的重試、悲觀鎖的阻塞——這些都建立在「每次都真的打到資料庫」的前提上。但 Hibernate 其實在 session 裡藏了一層快取，讓某些查詢根本不發 SQL——它怎麼運作、什麼時候幫倒忙，見規劃中的 🔬〈Hibernate 一級快取與 dirty checking〉。

## 常見面試題

1. 樂觀鎖和悲觀鎖的差別？各適合什麼場景？（提示：版本比對事後拋 vs 行鎖事前擋；衝突頻率）
2. 隔離級別能解決 lost update 嗎？（提示：不能——讀改寫跨交易；要靠鎖）
3. 用了樂觀鎖還需要做什麼？（提示：重試邏輯；否則只是把覆蓋變錯誤頁）

## 延伸閱讀

- [Jakarta Persistence: Locking](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html#locking) — LockModeType 與樂觀/悲觀鎖的規格定義
- [PostgreSQL: Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html) — 隔離級別與併發異常的權威說明（範例極清楚）
