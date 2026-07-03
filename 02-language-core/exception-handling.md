# 例外處理

## 基礎架構

```java
try {
    // 邏輯處理
} catch (Exception ex) {
    // 針對錯誤情況進行處理
} finally {
    // 無論有沒有錯都一定會執行（finally 不一定要有）
}
```

## Checked vs Runtime Exception

| | Checked Exception | Runtime Exception |
|---|---|---|
| 檢查時機 | **編譯時**就會被檢查 | **執行時**才會被發現 |
| 處理要求 | 必須明確處理（try-catch 或 throws） | 不強制處理 |
| 代表意義 | 可預期的問題（如檔案不存在） | 通常是程式邏輯錯誤 |
| 例子 | `IOException`、`SQLException` | `NullPointerException`、`ArrayIndexOutOfBoundsException` |

## 常見的壞味道

```java
// ❌ 吞掉例外 —— 出錯了沒人知道
try {
    // 邏輯處理
} catch (Exception ex) {
}

// ❌ 只印出來不處理，呼叫端以為一切正常
try {
    // 邏輯處理
} catch (Exception ex) {
    System.out.println(ex.fillInStackTrace());
}
```

原則：**要嘛處理、要嘛往上拋，不要假裝沒事**。

## try-with-resources（AutoCloseable）

實作 `AutoCloseable` 的資源，用 try-with-resources 自動關閉：

```java
String filePath = "...";
try (FileReader fileReader = new FileReader(filePath);
     BufferedReader reader = new BufferedReader(fileReader)) {
    String line;
    while ((line = reader.readLine()) != null) {
        System.out.println(line);
    }
} catch (IOException e) {
    e.printStackTrace();
}
```

對比舊寫法：資源開在 try 裡卻沒有 finally 關閉，一旦中途拋例外就資源洩漏。
try-with-resources 保證離開區塊時自動呼叫 `close()`，且多個資源以**宣告的反序**關閉。
