# 資料型態：基本型別 vs 參考型別

## 基本資料型態（Primitive Data Type）

`byte`、`short`、`int`、`long`、`float`、`double`、`char`、`boolean`

- 大小固定，直接儲存**值**
- 不需要透過參考，存取速度快、效能好
- 儲存在 Thread Stack 中
- 支援自動型別轉換（widening）
- 每個都有對應的參考類別（Wrapper Class）：`int` ↔ `Integer`、`double` ↔ `Double`…

## 參考資料型態（Reference Data Type）

包含：

- 自定義的類別（class）
- 陣列（array）
- 介面（interface）
- 列舉（enum）
- 包裝類別（Wrapper Classes）

特色：

- 動態記憶體分配（物件本體在 Heap）
- 生命週期由 GC 管理
- 變數存的是**參考**，傳遞時共享同一個物件（見[經典陷阱](pitfall-shared-reference-in-loop.md)）

## 兩者的差別，一個例子

```java
public static void main(String[] args) {
    int a = 100;        // 值直接存在 stack
    Integer b = 100;    // b 是參考，指向 heap 上的 Integer 物件
}
```

`a` 的 100 就存在 stack 的變數裡；`b` 存的是一個指向 Heap 上 `Integer` 物件的參考。

## 重點

- 效能敏感的場景優先用基本型別，避免不必要的裝箱
- 集合（`List<Integer>`）只能放參考型別，這是 autoboxing 存在的原因
- `==` 對基本型別比較值、對參考型別比較參考——這是大量 bug 的來源
