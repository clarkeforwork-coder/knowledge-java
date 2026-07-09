# Optional

## 前言

Optional 大概是被誤用得最有創意的 API：`optional.get()` 裸呼叫（NPE 換了個名字叫 `NoSuchElementException`）、`isPresent()` 之後 `get()`（null check 換皮）、欄位宣告成 `Optional<String>`（連序列化都出問題）——用了 Optional 卻把它用成了 null 的化妝品。

回到它的本意：**把「可能沒有」寫進回傳型別**。`Member findByName(String)` 的簽名看不出查無資料時會怎樣；`Optional<Member>` 把這件事攤在型別上，**呼叫者被編譯器逼著面對**。這篇講正確姿勢的完整鏈，外加一個連老手都常踩的坑：`orElse` 的參數**永遠會被求值**（實測給你看）。

## 技術背景

### 它在解決什麼：null 的簽名沉默

[資料型態篇](data-types.md)講過 null 是「沒有值」的合法表達——問題在**簽名不會說話**：回傳 `Member` 的方法會不會回 null？看文件、看原始碼、或上線後看 NPE。Optional 讓型別自己說：回傳 `Optional<Member>` ＝「我可能沒有，你必須處理」。（[03 章](../03-spring-to-spring-boot/exception-handling-and-validation.md)的紀律呼應：單筆查無用 Optional/404，不是拋例外。）

### 取值的正確姿勢：永遠帶著 Plan B

```java
member.orElse(defaultMember)                    // 沒有就給備胎（注意：參數永遠被求值！見案例一）
member.orElseGet(() -> buildDefault())          // 惰性版：沒有才執行 lambda
member.orElseThrow(() -> new MemberNotFound(id)) // 沒有就拋語意化例外
member.ifPresent(m -> send(m));                 // 有才做
member.ifPresentOrElse(m -> send(m), () -> log("查無"));
```

**`get()` 幾乎永遠是錯的**——它就是沒有 Plan B 的取值，跟直接解參考 null 同罪（JDK 自己都後悔，補了語意更誠實的 `orElseThrow()` 無參版當同義詞）。

### 鏈式轉換：null check 金字塔的解藥

```java
// ❌ 金字塔
String name = null;
if (order != null && order.member() != null && order.member().name() != null) {
    name = order.member().name();
}

// ✅ Optional 鏈（06 章 Stream 的同款思維——每步自動短路 empty）
String name = Optional.ofNullable(order)
        .map(Order::member)           // 任何一步是 null → 整條變 empty
        .map(Member::name)
        .orElse("（無名氏）");
```

`map`/`flatMap`/`filter` 與 [Stream](../06-lambda-and-stream/stream-operations.md) 同名同義——Optional 可以想成「最多一個元素的流」。

### 建立與邊界

- **`of` vs `ofNullable`**：`of(null)` 當場 NPE（實測）——確定非空用 `of`（提早炸＝提早發現）、可能為空用 `ofNullable`
- **使用邊界（官方立場）**：Optional 設計給**回傳值**。三個不要——**不當欄位**（不可序列化、徒增一層）、**不當參數**（呼叫端被迫包裝，多載更乾淨）、**不包集合**（`Optional<List<T>>` 是雙重否定——空集合本身就是「沒有」的正確表達，[03 章](../03-spring-to-spring-boot/exception-handling-and-validation.md)同一條紀律）

## 實際案例

### 案例一：orElse 的隱形帳單

```java
Optional<String> present = Optional.of("實際值");
present.orElse(buildDefault());        // 有值的情況下……
```

實測：

```
=== orElse：有值也照樣求值參數 ===
  （建立預設值——昂貴操作被執行了！）   ← 有值還是執行了！
結果：實際值
=== orElseGet：有值就不碰 lambda ===
結果：實際值
```

`orElse(x)` 的 `x` 是**方法參數**——Java 的求值規則是呼叫前先算參數，跟 Optional 有沒有值無關。`buildDefault()` 如果是查 DB、建物件，每次呼叫都白付。規矩：**預設值是常數用 `orElse`，要計算的用 `orElseGet`**（lambda 惰性——[06 章](../06-lambda-and-stream/functional-interface-and-lambda.md)「延遲執行」的實戰）。

### 案例二：of(null) 與鏈式取值（實測）

```
ofNullable(null)：Optional.empty
of(null)       ：NullPointerException
鏈式結果：王小明                      ← 三層 map，任何一層 null 都安全短路
```

## 技術優缺點

### Optional 買到的

- **簽名會說話**：「可能沒有」從文件約定升級為型別事實，編譯器監督
- **鏈式短路**：金字塔 null check 變一條 map 鏈——與 Stream 心智模型共用

### 代價與紀律

- **物件成本**：每個 Optional 是一次包裝——熱路徑的內部邏輯別為了時髦到處包（它是 API 邊界的工具，不是 null 的全域替代品）
- **誤用面積大**：get 裸呼叫、isPresent+get、欄位／參數／集合包裝——code review 的固定檢查點
- **orElse 的求值陷阱**（實測）——最容易從「正確」滑向「慢」的一步

## 小結

- Optional 的本意：**把「可能沒有」寫進回傳型別**——簽名會說話，呼叫者被迫面對
- 取值永遠帶 Plan B：`orElse`／`orElseGet`／`orElseThrow`／`ifPresent`——**`get()` 幾乎永遠是錯的**
- **`orElse` 參數永遠被求值**（實測：有值照樣執行昂貴操作）——要計算的預設值用 `orElseGet`
- 鏈式 `map` 是 null 金字塔的解藥（任一步 null 自動短路）；`of(null)` 當場炸、`ofNullable` 收容
- 三個不要：不當欄位、不當參數、不包集合——它是**回傳值**的工具

02 章語言核心還剩最後一塊拼圖：值物件的現代標準答案——見規劃中的〈record 與不可變物件〉。

## 常見面試題

1. Optional 解決了 null 的什麼問題？哪裡該用、哪裡不該用？（提示：簽名沉默；回傳值 yes、欄位參數集合 no）
2. `orElse` 和 `orElseGet` 差在哪？（提示：參數求值時機，實測證據）
3. `Optional.get()` 為什麼被視為壞味道？（提示：沒有 Plan B；orElseThrow 無參版的存在意義）

## 延伸閱讀

- [Optional（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Optional.html) — API Note 開宗明義寫著「主要用於回傳型別」
- [Stuart Marks: Optional — The Mother of All Bikesheds](https://www.youtube.com/watch?v=Ej0sss6cq14) — JDK 維護者親講的使用準則，「三個不要」的出處
