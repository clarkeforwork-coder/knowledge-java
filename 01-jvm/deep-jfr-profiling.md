# 進階剖析：JFR 與 profiler

## 前言

[排查工具箱](troubleshooting-toolbox.md)的侷限收尾埋了這篇的種子：jstack、jmap 都是**單張快照**——事故當下抓得到，**間歇性問題拍不到**。「每天凌晨偶爾慢五秒」這種案子，你不可能整夜守著打 jstack。

答案是把相機換成**行車記錄器**：JFR（Java Flight Recorder）——JVM 內建的事件記錄器，開銷低到可以**常駐 production**，事故發生後把錄影倒回去看。這篇實錄一次完整流程：錄一段有鎖競爭的負載，讓 JFR 告訴我們**誰、在哪一行、等了誰、等多久**——連第一次錄到零筆的失敗都保留下來，因為那個坑本身就是教材。

## 技術背景

### JFR 是什麼：內建、常駐、低開銷

- **內建**：JDK 11 起免費開源（曾是商業功能），不用裝任何東西——`jcmd`、`jfr` 工具都在 JDK 的 bin 裡
- **事件模型**：JVM 各子系統持續發事件——GC（[GC 篇](gc-basics-and-generations.md)看的 log 這裡有結構化版本）、**鎖競爭**（`jdk.JavaMonitorEnter`——[鎖升級篇](../07-concurrency/deep-synchronized-lock-optimization.md)的 fat lock 排隊實況）、配置取樣、CPU 取樣（`jdk.ExecutionSample`）、檔案／socket IO……
- **低開銷的機制**：事件寫入 thread-local buffer、預設帶**閾值過濾**（短事件不記）——設計目標 <1% 開銷，所以敢常駐

### 怎麼開：兩條路

```bash
# 路一：啟動旗標——排查間歇性問題的正解（環形緩衝常駐，只留最近的）
java -XX:StartFlightRecording=maxage=6h,maxsize=200m,settings=profile ...

# 路二：執行中隨開隨關（jcmd —— 排查工具箱那個家族）
jcmd <pid> JFR.start settings=profile
jcmd <pid> JFR.dump filename=rec.jfr        # 事故發生：把錄影倒出來
jcmd <pid> JFR.stop
```

`settings` 兩檔：`default`（開銷最低，常駐用）、`profile`（取樣更密、閾值更低，排查期用）。

### 怎麼讀：CLI 就很能打

```bash
jfr summary rec.jfr                                  # 各事件的數量總表
jfr print --events jdk.JavaMonitorEnter rec.jfr      # 逐筆傾印某類事件
```

深入分析用 **JDK Mission Control（JMC）**——免費 GUI，火焰圖、自動分析（它會直接寫「你有鎖競爭問題，在某某類」）。

## 實際案例

### 一次完整的錄影排查（含一次教學級的失敗）

負載：兩條 thread 搶同一把鎖（臨界區約 40ms）＋配置壓力，跑 8 秒，啟動時掛上錄影。

**第一次嘗試——0 筆鎖事件**：用預設設定錄，`jfr summary` 裡 `jdk.JavaMonitorEnter` 是 **0**。原因：**預設閾值 20ms**——短於閾值的鎖等待不記錄（這正是低開銷的代價）。教訓先於成果：**「錄影裡沒有」不等於「沒發生」，先確認事件的閾值與設定檔**。

**第二次——`settings=profile` 重錄**，40 筆到手，每筆長這樣：

```
jdk.JavaMonitorEnter {
  duration = 28.2 ms                             ← 等了多久
  monitorClass = java.lang.Object
  previousOwner = "worker-A" (javaThreadId = 19) ← 誰佔著鎖
  eventThread = "worker-B" (javaThreadId = 20)   ← 誰在等
  stackTrace = [
    JfrTarget.lambda$main$0() line: 15           ← 卡在哪一行！
    ...
```

對照 [jstack](troubleshooting-toolbox.md)：jstack 給你「**此刻**誰 BLOCKED」，JFR 給你「**過去八秒內**每一次超過閾值的等待——誰等誰、多久、在哪行」。間歇性的鎖競爭、偶發的慢請求，這是唯一的破案路徑。

同一份錄影裡還躺著 `jdk.ExecutionSample`（687 筆 CPU 取樣——火焰圖的原料）和整套 GC 事件——**一次錄影，CPU／鎖／GC／配置四案並查**。

### async-profiler：JFR 之外該認識的一個名字

JFR 的 CPU 取樣（ExecutionSample）依賴 safepoint 附近的採樣，對某些熱點有偏差（safepoint bias）；**async-profiler** 用 OS 信號直接採，能穿透到 native frame、還能剖析配置與鎖，輸出火焰圖。實務分工：**JFR 常駐飛行記錄、async-profiler 針對性深挖 CPU 熱點**——兩者都會用，profiling 的工具箱就齊了。

## 技術優缺點

### JFR 的定位

- ✅ **常駐可負擔**（<1% 目標）——「事故後才開工具」變成「事故前就在錄」，這是與 jstack/jmap 的本質差異
- ✅ 事件面廣：CPU、鎖、GC、IO、配置一網打盡，時間軸對齊（「慢的那五秒 GC 在幹嘛、鎖在幹嘛」一目瞭然）
- ❌ **閾值與取樣是雙面刃**：低開銷靠「不全記」——短事件、低頻配置可能不在錄影裡（實測的 0 筆教訓）
- ❌ 深度 CPU 剖析有 safepoint bias——交給 async-profiler

### 回到實務

- 生產環境建議**常駐開錄**：`maxage`＋`maxsize` 的環形緩衝，記憶體與磁碟成本可控，換來「任何事故都有錄影」
- 排查節奏：**先 jfr summary 看全貌 → 鎖定事件類別逐筆 print → 需要視覺化再上 JMC**
- 這篇收掉了 01 章的最後一塊拼圖：從 [what-is-jvm](what-is-jvm.md) 到 [排查工具箱](troubleshooting-toolbox.md)再到 JFR——「理解 JVM → 事故當下取證 → 常駐錄影回放」的完整鏈路

## 小結

- JFR ＝ JVM 內建的**行車記錄器**：事件模型、<1% 開銷目標、JDK 11+ 免費——解決 jstack/jmap「單張快照」拍不到間歇性問題的根本侷限
- 兩條開法：啟動旗標常駐（`maxage`/`maxsize` 環形緩衝）、`jcmd JFR.*` 隨開隨關
- **閾值教訓**（實測）：預設 20ms 以下的鎖等待不記——「錄影裡沒有 ≠ 沒發生」，排查期用 `settings=profile`
- 鎖競爭事件的破案力（實測）：誰等誰、等多久、卡在哪一行，一筆事件全給
- 分工：JFR 常駐廣譜、async-profiler 深挖 CPU；讀法從 `jfr summary` 開始

**至此 Roadmap 上的所有規劃項目全部完成**——七章 🔰 完軌、七篇 🔬 深入文各就各位。剩下的（02 語言核心補完、08 資料存取、09 測試、10 版本演進）屬於長期擴充，隨需求開工。

## 常見面試題

1. JFR 和 jstack/jmap 的本質差異？（提示：常駐錄影 vs 單張快照；間歇性問題）
2. 為什麼 JFR 敢說能常駐 production？代價是什麼？（提示：thread-local buffer＋閾值過濾；實測 0 筆的教訓）
3. 排查「偶發的慢請求」你會怎麼做？（提示：常駐錄影＋事後 dump；鎖事件的四要素）

## 延伸閱讀

- [jfr 指令官方手冊](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jfr.html) — print/summary 的完整用法
- [JDK Mission Control](https://openjdk.org/projects/jmc/) — 錄影的 GUI 分析器與自動診斷
- [async-profiler](https://github.com/async-profiler/async-profiler) — 火焰圖與低偏差 CPU 剖析的業界標準
