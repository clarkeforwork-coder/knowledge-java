# 泛型基礎與型別擦除

## 前言

泛型是你最熟悉的陌生人：`List<String>`、`Map<String, Order>` 天天寫，但一被問到「為什麼不能 `new T()`？」「為什麼沒有 `List<String>.class`？」「為什麼 `instanceof List<String>` 編譯不過？」就答不上來。

這些問題的答案是同一個：**型別擦除（type erasure）**——你寫的 `<String>`，編譯完就不存在了。這篇用三個實測證明它（包括用 `javap` 直接看 bytecode），並整理擦除帶來的完整限制清單——05 章後面兩篇的地雷，根都在這裡。

## 技術背景

### 泛型在解決什麼：把執行期的炸彈提前到編譯期

Java 5 之前的集合只裝 `Object`，拿出來全靠強轉：

```java
// ❌ 前泛型時代：塞什麼沒人管，錯誤在「取出時」才爆
List names = new ArrayList();
names.add("Alice");
names.add(42);                          // 沒人攔
String s = (String) names.get(1);       // 執行期 ClassCastException

// ✅ 泛型：錯誤在「放入時」就被編譯器攔下
List<String> names = new ArrayList<>();
names.add(42);                          // 編譯錯誤，根本進不了版控
```

泛型的本質是**讓編譯器幫你記住容器裡裝什麼**——錯誤從執行期（用戶看到）提前到編譯期（你看到）。

### 自訂泛型：類別與方法

```java
public class Box<T> {                        // 泛型類別：T 是型別參數
    private T content;
    public T get() { return content; }
}

public static <T> T first(List<T> list) {    // 泛型方法：<T> 宣告在回傳型別前
    return list.get(0);
}

public static <T extends Comparable<T>>      // 邊界：T 必須可比較
T max(List<T> list) { ... }                  //（04 章排序篇的 Comparable 在這重逢）
```

命名慣例：`T`（Type）、`E`（Element）、`K`/`V`（Key/Value）——單字母不是懶，是讓讀者一眼認出「這是型別參數，不是真的類別」。

### 型別擦除：編譯完，`<String>` 就不見了

Java 泛型是**編譯期的檢查工具**，不是執行期的機制。編譯器做兩件事然後功成身退：

1. **檢查**：所有泛型規則在編譯期驗證完畢
2. **擦除**：`T` 替換成 `Object`（有邊界則替換成邊界，如 `Comparable`）；在每個取值的地方**代你插入強轉**

所以執行期的世界裡：`List<String>` 和 `List<Integer>` 是**同一個 class**（實測 `getClass()` 相等）；前泛型時代的強轉**沒有消失，只是編譯器代寫**（實測 bytecode 裡的 `checkcast`）。

### 為什麼要擦除：一個誠實的歷史交代

不是做不到保留（C# 的泛型就是 reified，執行期查得到），是**相容性的取捨**：Java 5 要讓泛型程式碼與既有的十年舊 bytecode、舊 JVM 生態無縫共存——擦除讓 `List<String>` 在執行期就是舊的 `List`，一行舊程式碼都不用改。代價就是下面這張限制清單。

### 擦除的限制清單

| 寫不出來的 | 原因 |
|---|---|
| `new T()`、`new T[]` | 執行期不知道 T 是誰，不知道要建什麼 |
| `T.class`、`List<String>.class` | class 物件只有一份（擦除後共用） |
| `x instanceof List<String>` | 執行期查不到 `<String>`，只能 `instanceof List<?>` |
| 兩個方法只差型別參數的多載 | 擦除後簽名相同（實測編譯錯誤） |
| `catch (T e)`、泛型繼承 `Throwable` | 例外表是執行期機制，擦除後無從分辨 |

還有一個容易誤會的：泛型類別的 **static 成員不隨型別參數分家**——`Box<String>` 和 `Box<Integer>` 共用同一份 static（本來就只有一個 class）。

## 實際案例

### 案例一：執行期根本沒有 `<String>` 這件事

```java
List<String> a = new ArrayList<>();
List<Integer> b = new ArrayList<>();
System.out.println(a.getClass() == b.getClass());
```

實測：`true`——執行期它們是同一個 class。既然檢查只存在於編譯期，用 raw type 就能繞過：

```java
List<String> strs = new ArrayList<>();
((List) strs).add(42);                    // 編譯器只給 unchecked 警告，不擋
String s = strs.get(0);                   // ？
```

實測輸出：

```
塞進去了，size = 1
取出時：class java.lang.Integer cannot be cast to class java.lang.String
```

Integer 安安穩穩住進了 `List<String>`，**取出時**才炸——因為炸的是編譯器代寫的那個強轉。這也是 **unchecked 警告不該無視**的原因：每個警告都是一枚「放進去沒事、取出來爆炸」的延遲引信（術語叫 heap pollution，陣列篇會再遇到它的豪華版）。

### 案例二：bytecode 存證——強轉是編譯器代寫的

```java
static String first(List<String> list) {
    return list.get(0);       // 原始碼沒有任何強轉
}
```

`javap -c` 實測：

```
2: invokeinterface #7,  2   // InterfaceMethod java/util/List.get:(I)Ljava/lang/Object;
7: checkcast     #13        // class java/lang/String
```

看清楚這兩行：`get` 回傳的是 **`Object`**（擦除的直接證據），緊接著一個 **`checkcast String`**——你沒寫的強轉，編譯器寫了。泛型給你的「免強轉」體驗，是語法糖不是機制變革。

### 案例三：擦除後同名——多載直接編譯失敗

```java
void handle(List<String> names) { }
void handle(List<Integer> ids) { }
```

實測編譯錯誤：

```
error: name clash: handle(List<Integer>) and handle(List<String>) have the same erasure
```

錯誤訊息自己把答案講完了：**擦除之後兩個方法的簽名一模一樣**（都是 `handle(List)`），JVM 無從分辨。實務解法：方法改名（`handleNames`/`handleIds`）——比玄學參數更誠實。

## 技術優缺點

### 泛型給你的

- **錯誤提前**：型別錯誤從執行期 CCE 變成編譯錯誤——修 bug 的成本差一個量級
- **免寫強轉**：程式碼乾淨（雖然實測看到它只是被代寫了）
- **API 表達力**：`Map<String, List<Order>>` 的簽名本身就是文件

### 擦除的代價

- **執行期失明**：限制清單裡的每一條，框架作者最有感（序列化、依賴注入都得靠 `TypeReference` 這類技巧補救）
- **unchecked 警告是真警告**：raw type 繞過檢查後，錯誤退回前泛型時代的「取出時爆炸」——而且爆炸點離案發點很遠
- 對比 reified 泛型（C#）：Java 選了相容性——這是工程取捨，不是能力不足

## 小結

- 泛型＝**編譯期的型別檢查**：把「取出時的 CCE」提前成「放入時的編譯錯誤」
- **型別擦除**：編譯後 `T` → `Object`（或邊界），強轉由編譯器代寫——`getClass()` 相等、`checkcast` bytecode，兩個實測為證
- 擦除是**相容性的取捨**（舊生態無縫共存），代價是限制清單：不能 `new T()`、沒有 `T.class`、不能按型別參數多載（實測 name clash）
- **unchecked 警告別無視**：那是「放入沒事、取出爆炸」的延遲引信
- static 成員不隨型別參數分家——class 只有一份

擦除講完，下一個問題馬上來：`List<Integer>` 能不能傳給收 `List<Number>` 的方法？（劇透：不能——泛型沒有繼承關係。）這就是萬用字元和 PECS 要解的題：見規劃中的〈萬用字元與 PECS〉。

## 常見面試題

1. 什麼是型別擦除？Java 為什麼這樣設計？（提示：T → Object＋代寫強轉；相容性取捨）
2. `List<String>` 在執行期還知道自己裝 String 嗎？（提示：getClass 實測、raw type 繞過後取出才爆）
3. 為什麼不能寫兩個只差泛型參數的多載方法？（提示：same erasure，錯誤訊息原文）

## 延伸閱讀

- [Java Tutorials: Type Erasure](https://docs.oracle.com/javase/tutorial/java/generics/erasure.html) — 官方教學的擦除章節
- [JLS §4.6: Type Erasure](https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html#jls-4.6) — 擦除規則的精確定義
