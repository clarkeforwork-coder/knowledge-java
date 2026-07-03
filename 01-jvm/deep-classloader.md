# ClassLoader 的開放性：從 Tomcat 隔離到 Native Image 的死穴

## 前言

在 [What is JVM](what-is-jvm.md) 我們講過雙親委派模型：載入請求一路往上委託，保證核心類別不被覆蓋、同一個 class 不被重複載入。聽起來是個封閉又嚴格的機制。

那問題來了：同一台 Tomcat 上跑兩個 Web Application，一個用 Jackson 2.13、一個用 Jackson 2.17——同樣的 fully qualified class name、不同版本的 Bytecode，為什麼可以共存？如果雙親委派保證「一個 class 只載入一次」，這件事根本不該發生。

答案是：**雙親委派是預設策略，不是鐵律**。Class Loader 機制其實是開放的，而這個開放性撐起了 Java 生態一大半的基礎設施——也埋下了 Native Image 時代最大的一根刺。

## 技術背景

### class 的唯一性由誰決定

JVM 裡一個 class 的身分，不是只看名字，而是 **(ClassLoader, fully qualified name)** 這個組合。同一份 `.class` 被兩個不同的 ClassLoader 載入，在 JVM 眼中就是兩個不同的 class——它們的物件互相 `instanceof` 是 false，強轉會噴 `ClassCastException`。

這是隔離的理論基礎：想讓同名 class 共存，就給它們各自的 ClassLoader。

### 委派模型的「開口」在哪

你可以繼承 `java.lang.ClassLoader`，自己寫一個自定義的 Class Loader。最基本的做法是覆寫 `findClass()`：

```java
public class MyClassLoader extends ClassLoader {
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytecode = loadBytecodeFromSomewhere(name); // 從檔案、網路、甚至動態產生
        return defineClass(name, bytecode, 0, bytecode.length);
    }
}
```

關鍵在 `loadClass()` 與 `findClass()` 的分工：`loadClass()` 實作了「先委託上層、找不到才呼叫自己的 `findClass()`」這套委派邏輯。覆寫 `findClass()` 是在委派框架內擴充「怎麼找」；而**覆寫 `loadClass()` 就能改寫委派順序本身**——這就是打破雙親委派的開口。

換句話說，只要你能拿到合法的 Bytecode——不管是從磁碟讀的、從網路下載的、還是在執行期動態產生的——你都可以把它載入 JVM。

## 實際案例

這不是什麼冷門技巧，很多框架的核心功能就靠這個：

### Tomcat：每個 Web Application 一個 ClassLoader

Tomcat 為每個 webapp 建立獨立的 WebappClassLoader，而且對應用自己的類別採取 **local-first**：先找 `WEB-INF/classes` 和 `WEB-INF/lib`，找不到才委託上層（JDK 核心類別仍然先委派，避免安全問題）。兩個 webapp 的 Jackson 由各自的 ClassLoader 載入，(loader, name) 不同，自然互不干擾。

### 熱更新：換一個 ClassLoader 重新載入

已載入的 class 在 JVM 裡不能原地替換，但可以**丟掉整個 ClassLoader 換新的**。Spring DevTools 的 restart 機制就是雙 ClassLoader 設計：第三方依賴放在不變的 base ClassLoader，你的程式碼放在 restart ClassLoader——改了程式碼，只要拋棄後者重建，比冷啟動快得多。JRebel 這類工具則做得更細，能做到 class 層級的重載。

### OSGi：模組的動態載入與卸載

OSGi 給每個 bundle 一個 ClassLoader，模組能在執行期安裝、啟動、卸載，模組間的依賴透過 ClassLoader 網狀委派解析——這是把「開放性」用到極致的例子。

## 技術優缺點

### 開放性帶來的能力

- **隔離**：同名不同版的 library 共存（應用伺服器的基礎）
- **動態性**：執行期載入新程式碼——插件系統、熱更新、動態代理（Spring AOP 的 CGLIB 子類就是執行期生成 Bytecode 再載入的）
- **來源自由**：Bytecode 可以來自磁碟、網路、記憶體動態產生

### 代價

- **跨 loader 的類型不相容**：同名 class 在不同 loader 下互轉會 `ClassCastException`，這類 bug 只在特定部署形態出現，很難查。
- **ClassLoader 洩漏**：只要有一個物件還被外部參考，整個 ClassLoader 連同它載入的所有 class metadata 都無法回收——webapp 重複部署後的 Metaspace 洩漏多半是這個。
- **AOT 的死穴**：如果 class 是在執行期才決定、甚至才「產生」的，AOT 編譯時根本不知道它的存在。GraalVM Native Image 走 closed-world assumption（編譯期必須看見所有可達的 class），所以動態載入、反射、動態代理都需要額外的設定檔（reachability metadata）明確宣告——Java 生態最大的彈性來源，正好是 Native Image 最大的相容性障礙。

## 小結

- class 的身分是 **(ClassLoader, class name)**，不是只有名字——隔離與共存都建立在這之上
- 雙親委派是 `loadClass()` 的預設實作，不是 JVM 的硬性規則；覆寫它就能改寫委派順序
- Tomcat 靠 local-first 的 WebappClassLoader 做應用隔離；熱更新靠「換掉整個 ClassLoader」；OSGi 靠 loader 網狀委派做模組化
- 動態載入是 Java 彈性的來源，也是 GraalVM Native Image 的最大障礙——執行期才出現的 class，AOT 看不見

這條線再往下走就是 GraalVM 與 Native Image 本身：AOT 編譯如何處理反射、動態代理與 ClassLoader，以及 Spring Boot 3 的 AOT processing 做了什麼——留給未來的筆記。

## 常見面試題

1. 兩個不同的 ClassLoader 載入同一份 `.class`，得到的是同一個 class 嗎？（提示：class 唯一性的定義，`instanceof` 會怎樣）
2. Tomcat 為什麼要打破雙親委派？它怎麼打破的？（提示：webapp 隔離需求、local-first 順序）
3. 為什麼 webapp 重複部署會造成 Metaspace 洩漏？（提示：ClassLoader 的可達性連著它載入的所有 class）

## 延伸閱讀

- [java.lang.ClassLoader（Javadoc）](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ClassLoader.html) — `loadClass()` 委派邏輯的官方描述
- [Apache Tomcat Class Loader HOW-TO](https://tomcat.apache.org/tomcat-10.1-doc/class-loader-howto.html) — Tomcat 的委派順序官方文件
- [GraalVM Native Image: Reachability Metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/) — 動態特性在 AOT 下的處理方式
