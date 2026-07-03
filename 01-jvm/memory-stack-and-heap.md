# 記憶體：Stack 與 Heap

## Thread Stack（執行緒堆疊）

存放的東西：

- 局部變數（local variables）
- 方法參數（method parameters）
- 方法返回位址（return addresses）
- 基本資料型別的變數值
- 物件的**參考**（reference，不是物件本身）

特性：

- 每個執行緒獨立擁有自己的 stack
- 先進後出（LIFO）結構
- 生命週期隨方法執行結束自動回收，**不需要 GC**
- 大小固定，可用 `-Xss` 參數調整
- 空間不足時拋出 `StackOverflowError`（常見於無窮遞迴）

## Heap（堆積記憶體）

存放的東西：

- 物件實例（object instances）
- 陣列（arrays）
- Class 物件（包含 static 變數）
- 字串常量池（String Pool）
- 實例變數（instance variables）

特性：

- 所有執行緒**共享**
- 分代管理（Young / Old Generation）
- 需要 GC 管理回收
- 大小可動態調整（`-Xms` / `-Xmx`）
- 空間不足時拋出 `OutOfMemoryError`
- 空間大但存取較慢、空間不連續

## 重點對照

| | Stack | Heap |
|---|---|---|
| 歸屬 | 每個執行緒一份 | 所有執行緒共享 |
| 存放 | 局部變數、參考、方法呼叫 | 物件、陣列、String Pool |
| 回收 | 方法結束自動回收 | GC |
| 溢出錯誤 | `StackOverflowError` | `OutOfMemoryError` |
| 調整參數 | `-Xss` | `-Xms` / `-Xmx` |

## 一句話記憶

> 變數名在 Stack，物件本體在 Heap；`new` 出來的都在 Heap，指過去的參考在 Stack。
