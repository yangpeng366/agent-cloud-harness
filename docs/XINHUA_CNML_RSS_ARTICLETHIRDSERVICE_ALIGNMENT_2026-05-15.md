# 新华社 CNML / RSS / ArticleThirdService 对齐调研

## 1. 背景

本调研对应 harness 中的真实任务：

- `session_id = session_2b11c93d9dcd439c`
- `task_id = task_24cbb3678c684d60`
- 原始目标：
  - 分析 `D:\项目对接-大文件\data\cnml\input` 下的新华社 XML 实体协议
  - 对照 `ArticleThirdService.java`
  - 对照 `"D:\项目对接\01_文档资料\Office文档\新华全媒新闻服务平台RSS用户手册v1.4.docx"`
  - 判断三者能否对上

这条任务在 harness 中没有自动产出最终结论，不是因为前端丢了结果，而是执行链中途失败：

- `codex` 轮次失败：`worker codex failed: thread not found`
- fallback worker 后续又没有产出有效最终结论
- 因此本结论文档由人工复核证据后补齐

## 2. 证据入口

### 2.1 代码

- `D:\gitAll\articleeditor\editor\src\main\java\com\sobey\editor\article\service\ArticleThirdService.java`
  - `buildArticleVOFromThridArticleRest(...)`：约第 `1136` 行
  - `buildArticleVOFromThridArticle(...)`：约第 `1264` 行
  - `pullXinhuaArtcleRss()`：约第 `1392` 行
  - `pullXinhuaArtcleRest()`：约第 `1573` 行
  - `xinhuaUrl.endsWith("rss")` 分流：约第 `1815` 行

### 2.2 RSS 手册

- 源文件：
  - `D:\项目对接\01_文档资料\Office文档\新华全媒新闻服务平台RSS用户手册v1.4.docx`
- 本次转换产物：
  - `.tmp\xinhua-rss-user-manual-v1.4.md`
- 转换命令：

```powershell
& 'D:\ProgramData\miniconda3\python.exe' -m markitdown `
  'D:\项目对接\01_文档资料\Office文档\新华全媒新闻服务平台RSS用户手册v1.4.docx' `
  -o '.tmp\xinhua-rss-user-manual-v1.4.md'
```

### 2.3 CNML 样例

- 文字稿样例：
  - `D:\项目对接-大文件\data\cnml\input\text\20260515\CmxmxcC000006_20260515_CBTFN1.xml`
- 图片稿样例：
  - `D:\项目对接-大文件\data\cnml\input\photo\20260515\CmxztpC000009_20260515_PEPFN1.xml`

## 3. 先给结论

结论很明确：

1. `ArticleThirdService` 当前实现**主要对的是 RSS XML 和 REST JSON 两套接口**。
2. 当前目录里的新华社样例文件是 **CNML 协议**，**不是** RSS 2.0 `channel/item` 结构。
3. 三者之间存在明显的**语义对应关系**，但**不能直接认为“完全对上”**。
4. 如果上游真实输入是 `D:\项目对接-大文件\data\cnml\input` 这种 CNML 落地文件，那么现有 `ArticleThirdService` **不能直接消费**，需要新增一层 **CNML -> 内部 ArticleVO** 的适配解析。

一句话总结：

- `RSS 手册` 和 `ArticleThirdService.rss 分支` 基本对得上
- `CNML 样例` 和 `ArticleThirdService` 只有字段语义能勉强映射，协议形态本身对不上

## 4. ArticleThirdService 当前到底支持什么

### 4.1 RSS 分支

`pullXinhuaArtcleRss()` 的行为是：

- 通过 `GET` 访问：
  - `xinhuaUrl?channelId=...&appId=...&timeStamp=...&m=3&sign=...`
- 使用 `RssParser.parseRss(result)` 解析返回结果
- 读取的是 RSS `channel/item` 风格字段
- 单条稿件通过 `buildArticleVOFromThridArticle(Map rowItem, ArticleVO articleVO)` 转成内部对象

这条链读取的关键字段包括：

- `title`
- `description`
- `author`
- `pubDate`
- `updateDate`
- `evacuate`
- `productName.attrs.productId`
- `productName.attrs.newsCategoryId`
- `productName.attrs.newsCategoryName`
- `productName.attrs.columnCategoryId`
- `productName.attrs.columnCategoryName`
- `productName.attrs.mediaTypeId`
- `imageUrl`
- `videoUrl`

### 4.2 REST 分支

`pullXinhuaArtcleRest()` 的行为是：

- 通过 `POST JSON` 访问：
  - `/getordercid`
  - `/getnewsbyproduct`
  - `/getnewsdetail`
- 用 `buildArticleVOFromThridArticleRest(Map doc, ArticleVO articleVO)` 映射字段
- 读取的是 REST JSON 字段，不再是 RSS `item` 节点

这条链读取的关键字段包括：

- `docId`
- `headLine`
- `content`
- `mediaTypeId`
- `issueDateTime`
- `updateCancelId`
- `attachs`
- `authors`
- `newsCategoryId`
- `newsCategoryName`
- `columnCategoryId`
- `columnCategoryName`

### 4.3 运行时分流方式

`ArticleThirdService` 在运行时按 `xinhuaUrl.endsWith("rss")` 分流：

- 以 `rss` 结尾，走 `pullXinhuaArtcleRss()`
- 否则走 `pullXinhuaArtcleRest()`

这说明它当前实现里**没有 CNML 文件落地目录解析分支**。

## 5. RSS 手册说的是什么

根据 `.tmp\xinhua-rss-user-manual-v1.4.md`，手册定义的是标准 RSS 供稿方式：

- RSS 接口使用 `GET`
- URL 形态：
  - `http://api.xinhua-news.com/route/rss?channelId=...&appId=...&timeStamp=...&sign=...`
- 签名规则：
  - `appId + appKey + timeStamp` 做 `sha1`
- `item` 节点关键字段：
  - `title`
  - `link`
  - `evacuate`
  - `description`
  - `source`
  - `author`
  - `pubDate`
  - `updateDate`
  - `productName`
  - `imageUrl`
  - `videoUrl`
- `productName` 属性扩展：
  - `productId`
  - `columnCategoryId`
  - `newsCategoryId`
  - `columnCategoryName`
  - `newsCategoryName`
  - `mediaTypeId`

另外，手册明确提到：

- `m=1 / m=3` 控制正文分段方式
- `m=2 / m=3` 才能拿到知识属性分类和栏目属性
- `evacuate=2/3` 分别表示改稿/撤稿

这套说明与 `pullXinhuaArtcleRss()` 的字段预期是基本一致的。

## 6. 当前 CNML 样例到底长什么样

### 6.1 文字稿样例特征

`CmxmxcC000006_20260515_CBTFN1.xml` 的结构是：

- 根节点：`CNML`
- 包含：
  - `Envelop`
  - `Items`
  - `Relations`
- 文章元数据在：
  - `Items/Item/MetaInfo`
- 正文在：
  - `Items/Item/Contents/ContentItem/DataContent`

可直接观察到的关键字段：

- 标题：
  - `Titles/HeadLine`
- 作者：
  - `Creators/Creator/Name/FullName`
- 首发/修订时间：
  - `FirstCreateTime`
  - `CurrentRevisionTime`
  - `DateTime kind="RatifyTime"`
- 分类：
  - `SubjectCodes`
- 关键词：
  - `Keywords/Keyword`
- 媒体类型：
  - `ContentItem/.../MediaType topicRef="Text"`
- 正文：
  - `DataContent`
- 主键：
  - `PublicId`
  - `ItemId`
  - `VersionId`

### 6.2 图片稿样例特征

`CmxztpC000009_20260515_PEPFN1.xml` 的结构同样是 `CNML`，但 `ContentItem` 变成图片类型：

- `ContentItem xsi:type="ImageCIType"`
- `href="CmxztpC000009_20260515_PEPFN1A001.JPG"`
- `MediaType topicRef="Photo"`
- `PixelWidth / PixelHeight / SizeInBytes`
- 图片说明文字仍然在 `DataContent`

也就是说：

- 图片文件地址不是 RSS 的 `imageUrl`
- 而是 `ContentItem href`
- 图片文字说明也不是 RSS 的 `description`
- 而是图片 `ContentItem` 的 `DataContent`

## 7. 三方对照结果

### 7.1 能对上的部分：语义层

下表是“语义大致能对应”的部分：

| 业务语义 | RSS 手册字段 | ArticleThirdService 字段 | CNML 样例字段 | 结论 |
| --- | --- | --- | --- | --- |
| 稿件主键 | `productId` | `platId <- productId/docId` | `ItemId` / `PublicId` | 语义接近，但字段名和结构不同 |
| 标题 | `item.title` | `title/headLine -> articleVO.title` | `Titles/HeadLine` | 能对应 |
| 正文/说明 | `description` | `description/content -> articleVO.content` | `DataContent` | 能对应，但节点位置不同 |
| 作者 | `author` | `author/authors` | `Creators` | 能对应 |
| 发布时间 | `pubDate` | `pubDate / issueDateTime` | `FirstCreateTime / RatifyTime` | 可映射，但时间语义不完全一致 |
| 更新时间 | `updateDate` | `updateDate / issueDateTime` | `CurrentRevisionTime` | 可映射 |
| 稿件类型 | `mediaTypeId` | `mediaTypeId` | `MediaType topicRef` | 可映射 |
| 分类 | `newsCategoryId/newsCategoryName` | 同名字段 | `SubjectCodes` | 可映射，但结构不同 |
| 栏目 | `columnCategoryId/Name` | 同名字段 | `Products/Columns` | 部分可映射 |

### 7.2 对不上的部分：协议层

下表是“当前不能直接对上”的核心差异：

| 项 | RSS / REST | CNML 样例 | 对现有代码的影响 |
| --- | --- | --- | --- |
| 根结构 | `rss/channel/item` 或 REST JSON | `CNML/Envelop/Items/Relations` | 现有解析器不能直接复用 |
| 主键字段 | `productId` / `docId` | `ItemId` / `PublicId` | 需新增映射规则 |
| 改撤稿标识 | `evacuate` / `updateCancelId` | 样例里未见等价字段 | 现有改撤稿逻辑无法直接套用 |
| 图片地址 | `imageUrl` | `ContentItem href` | 现有图片映射不兼容 |
| 视频地址 | `videoUrl` | CNML 需看视频样例 | 现有视频映射不兼容 |
| 分类字段位置 | `productName.attrs.*` | `SubjectCodes` / `Products/Columns` | 需要重写提取逻辑 |
| 产品/线路字段 | `productName/productId/channelId` | `SentTo/Product/ProductName` | 结构完全不同 |
| 改稿引用关系 | `evacuate.link/docId` | `Relations/Relation` | 需要单独解读 relation 语义 |

### 7.3 关键判断

这里最重要的判断不是“能不能手工看出相似性”，而是“现有代码能不能直接跑通”。

答案是：

- **不能直接跑通**

原因不是字段完全没重合，而是：

- `ArticleThirdService` 的解析前提是“上游已经是 RSS item map 或 REST detail json”
- 现在本地目录中的文件是“原始 CNML 文档”
- 这两者之间差着一层协议适配

## 8. 对现有实现的具体评价

### 8.1 RSS 手册 vs RSS 分支

这一部分基本是对得上的：

- 手册要求的 `channelId/appId/timeStamp/sign`，代码里都有
- 手册要求 `m=3` 可拿知识属性分类，代码里确实固定传了 `m=3`
- 手册定义的 `productName` 属性扩展，代码里确实从 `productName.attrs` 读取
- 手册定义的 `evacuate=2/3`，代码里也按改稿/撤稿处理

所以：

- 如果实际接入的是 RSS 接口，`ArticleThirdService` 的 RSS 支持方向是合理的

### 8.2 CNML 样例 vs 当前实现

这一部分对不上：

- CNML 样例不是 `RssParser.parseRss(result)` 产出的结构
- 也不是 `getnewsdetail` 返回的 REST JSON 结构
- 现有代码里没有任何 `CNML`、`DocumentBuilder`、`XPath`、`SAX` 之类的文件解析分支

所以：

- 如果真实生产输入改成了 `D:\项目对接-大文件\data\cnml\input` 这种文件落地方式
- 那就不能继续沿用 `pullXinhuaArtcleRss()` 或 `pullXinhuaArtcleRest()` 直接接

## 9. 建议的落地方向

### 9.1 如果上游本来就是 RSS

建议：

1. 不要拿 CNML 文件目录去验证 RSS 解析分支是否正确。
2. 直接用 RSS URL 抓回真实 RSS 样例，验证 `RssParser.parseRss()` 和 `buildArticleVOFromThridArticle(...)`。
3. 把 CNML 样例当成“另一套供稿物料”，不要混成同一协议。

### 9.2 如果上游真实交付已经变成 CNML 文件

建议新增独立接入链：

1. 新增 `pullXinhuaArticleCnml()` 之类的入口。
2. 新增 `buildArticleVOFromCnml(...)`。
3. 至少补以下映射：
   - `HeadLine -> title`
   - `DataContent -> content`
   - `Creators -> author`
   - `MediaType -> article type`
   - `ItemId/PublicId -> platId`
   - `SubjectCodes -> newsCategory`
   - `Products/Columns -> columnCategory`
   - `ContentItem href -> material url`
4. 单独定义“撤稿/改稿”在 CNML 中怎么判定，不能沿用 RSS 的 `evacuate` 假设。

### 9.3 最小验证建议

建议后续按下面顺序验证：

1. 先明确真实上游到底是 `RSS URL`、`REST API`，还是 `CNML 文件目录`。
2. 如果是 `CNML 文件目录`，先做一版“文本稿 + 图片稿”最小解析。
3. 用本文里的两个样例文件做第一轮单元测试样本。
4. 再讨论是否要兼容视频稿、音频稿、多媒体稿。

## 10. 本次调研的最终结论

本次问题“CNML 实体协议、`ArticleThirdService.java`、RSS 手册能不能对上”的最终回答是：

- **不能说完全对上**

更准确的说法是：

- `RSS 手册` 与 `ArticleThirdService` 的 `RSS 分支` 是对得上的
- `CNML 样例` 与 `ArticleThirdService` 只有业务语义上能部分映射
- 协议结构层面，当前 `CNML` 和现有 `ArticleThirdService` **不是同一套输入合同**

因此：

- 如果要接 `D:\项目对接-大文件\data\cnml\input` 里的真实文件，现有实现**需要新增 CNML 适配层**
- 不能直接认为当前 `ArticleThirdService` 已经支持这批 CNML 文件
