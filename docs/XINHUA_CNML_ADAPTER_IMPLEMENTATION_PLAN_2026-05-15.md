# 新华社 CNML 适配实现计划

## 1. 背景

上一份调研文档已经确认：

- [XINHUA_CNML_RSS_ARTICLETHIRDSERVICE_ALIGNMENT_2026-05-15.md](/D:/gitAll/agent-cloud-harness/docs/XINHUA_CNML_RSS_ARTICLETHIRDSERVICE_ALIGNMENT_2026-05-15.md)

核心结论是：

- `ArticleThirdService` 当前支持 `RSS XML` 与 `REST JSON`
- `D:\项目对接-大文件\data\cnml\input` 中的真实样例是 `CNML`
- 如果真实上游已经变成 CNML 文件目录，现有实现不能直接消费

本计划文档的目标不是再次证明“对不上”，而是把后续实现收成可执行的改造步骤。

## 2. 当前真实输入覆盖面

本次对 `D:\项目对接-大文件\data\cnml\input` 的样本复核结果：

- 当前样本量：
  - `Text`：67 个
  - `Photo`：5 个
- 当前未观察到：
  - `Graph`
  - `Video`
  - `Audio`
  - `MultiMedia`

这意味着第一版适配实现不应该一口气覆盖所有媒体类型，最小可交付范围应锁定为：

1. 文本稿 `Text`
2. 图片稿 `Photo`

## 3. 目标输出

第一版 CNML 适配的目标不是替换 RSS/REST 分支，而是补一条并行能力：

1. 能从单个 CNML 文件解析出 `ArticleVO` 所需关键字段。
2. 能覆盖当前目录里的 `Text` 和 `Photo` 两类样本。
3. 能复用 `ArticleThirdService` 现有的文章类型、正文清洗、图片关键字补位等内部约定。
4. 不误用 RSS 的 `evacuate`、`productName.attrs.*` 等字段假设。

## 4. 推荐改造边界

### 4.1 不建议直接硬改现有 RSS/REST 方法

不建议把 CNML 解析硬塞进下面两个方法：

- `pullXinhuaArtcleRss()`
- `pullXinhuaArtcleRest()`

原因：

- 这两条链的上游合同已经稳定
- 强行混入 CNML 会让输入合同更加模糊
- 后续排障时会很难区分“RSS 解析坏了”还是“CNML 适配坏了”

### 4.2 建议新增独立入口

建议新增：

- `pullXinhuaArticleCnml(...)`
- `buildArticleVOFromCnml(...)`

如果业务上存在目录扫描，还可以再细分：

- `scanCnmlFiles(...)`
- `parseCnmlFile(Path file)`

## 5. 字段映射建议

### 5.1 通用字段

| ArticleVO 语义 | CNML 建议来源 | 备注 |
| --- | --- | --- |
| `title` | `Titles/HeadLine` | 文本稿、图片稿都存在 |
| `platId` | `ItemId` | 比 `PublicId` 更适合作为短主键 |
| `author` | `Creators/Creator/Name/FullName[@xml:lang='zh-CN']` | 没有中文时回退英文或首个姓名 |
| `publish_date` | `DateTime kind='RatifyTime'` | 更接近签发时间 |
| `update_date` | `CurrentRevisionTime` | 对应修订时间 |
| `content` | `DataContent` | 文本稿是正文，图片稿是图片说明 |
| `newsCategoryName` | `SubjectCodes kind='XH_NewsCategory' -> MainCode/Name` | 先保名称 |
| `newsCategoryId` | `SubjectCodes kind='XH_NewsCategory' -> MainCode@topicRef` | 可多值拼接 |
| `columnCategoryName` | `Products/Product/Columns/Column/Name` | 可多值拼接 |
| `columnCategoryId` | `Products/Product/Columns/Column@topicRef` | 可多值拼接 |
| `keyword` / material keyword | `Keywords/Keyword` 或图片说明 | 视现有内部约定落位 |

### 5.2 文本稿 `Text`

建议映射：

- `MediaType topicRef='Text'`
- `articleVO.type = ArticleTypeEnum.ARTICLE`
- `articleVO.libtype = "xhsgk"`
- `content = DataContent`
- 如果正文不是 HTML：
  - 复用现有 `HtmlKit.textToHtml(...)`
- 如果正文带 `<figure>`：
  - 可复用现有 `transFigureDom(...)`

### 5.3 图片稿 `Photo`

建议映射：

- `MediaType topicRef='Photo'`
- `articleVO.type = ArticleTypeEnum.IMAGE`
- `articleVO.libtype = "xhstk"`
- 素材地址：
  - 优先使用 `ContentItem@href`
  - 再结合文件所在目录或业务约定补完整下载路径/相对路径
- 图片说明：
  - `DataContent`
- 复用现有逻辑：
  - `material.extend.keyword = 图片说明`
  - `articleVO.content = ""`

## 6. 第一版明确不做的事

为了避免第一版范围失控，建议先明确这些暂不处理：

1. `Graph / Video / Audio / MultiMedia` 的完整支持
2. CNML 中复杂 `Relation` 语义的自动业务化解释
3. 改稿 / 撤稿 的自动删除替换逻辑
4. 分布式锁、幂等、分页、远程补详情等高级运行时逻辑

这些能力大多属于 RSS/REST 在线接口链上的要求，不是“本地 CNML 文件适配”第一版的最小闭环。

## 7. 关键风险

### 7.1 改撤稿语义缺口

现有 RSS/REST 依赖：

- `evacuate`
- `updateCancelId`

当前 CNML 样例里没有看到同义字段。虽然样例里有：

- `Relations/Relation`
- `Role href=...`

但目前不能直接证明它就是“改稿/撤稿指针”。

结论：

- 第一版不要擅自把 `Relation` 当 `evacuate` 用
- 先只做“正常稿件导入”

### 7.2 图片地址并非现成 URL

RSS/REST 里图片通常已有可下发地址：

- `imageUrl`
- `attachs.downUrl`

CNML 图片样例里只有：

- `ContentItem href="CmxztpC000009_20260515_PEPFN1A001.JPG"`

这更像文件名或相对引用，不一定是完整可下载 URL。

结论：

- 需要先确认图片实体文件的真实落地位置
- 或确认上游是否还有配套资源目录

### 7.3 分类结构和 RSS 不同

RSS 的分类字段在：

- `productName.attrs.newsCategoryId`
- `productName.attrs.columnCategoryId`

CNML 则分散在：

- `SubjectCodes`
- `Products/Product/Columns`

结论：

- 不能复用现有 RSS 直接取值逻辑
- 但可以复用“最终落到 `newsCategory* / columnCategory*`”的内部目标字段

## 8. 推荐实现步骤

### Step 1. 先做离线解析器

先不要接入定时任务，先做一个离线解析器：

- 输入：单个 CNML 文件
- 输出：内存中的中间对象或 `ArticleVO`

这样最容易验证字段映射，不会被调度、鉴权、数据库写入干扰。

### Step 2. 只支持 `Text` 和 `Photo`

原因很简单：

- 当前样本里只观察到这两类
- 先用真实样本做通，比追求全面更值钱

### Step 3. 复用现有 `ArticleThirdService` 内部清洗逻辑

优先复用：

- `transFigureDom(...)`
- 文本转 HTML 的既有规则
- 图片稿“说明转 keyword，正文清空”的既有规则

不必为了 CNML 再造一套完全不同的入库后规范。

### Step 4. 再决定是否接入导入链

离线解析稳定后，再讨论接法：

1. 直接在 `ArticleThirdService` 新增 CNML 分支
2. 或新建单独 service，再把结果委托给现有入库逻辑

第一种改动更小，第二种边界更清晰。

## 9. 最小测试建议

建议至少准备两类测试：

### 9.1 固定样例测试

直接用当前真实样例：

- 文本：
  - `D:\项目对接-大文件\data\cnml\input\text\20260515\CmxmxcC000006_20260515_CBTFN1.xml`
- 图片：
  - `D:\项目对接-大文件\data\cnml\input\photo\20260515\CmxztpC000009_20260515_PEPFN1.xml`

断言至少包括：

- 标题
- 主键
- 媒体类型
- 作者
- 正文/说明
- 分类
- 栏目
- 图片稿素材引用

### 9.2 回归对照测试

目的不是让 CNML 和 RSS 结构一样，而是保证：

- CNML 解析后的 `ArticleVO` 关键语义
- 与 RSS/REST 解析后的 `ArticleVO` 落点一致

也就是比较：

- `title`
- `type`
- `libtype`
- `author`
- `content`
- `newsCategory*`
- `columnCategory*`

而不是比较“原始节点名”。

## 10. 本计划的落地结论

当前最合理的推进方式是：

1. 把 CNML 当成一条新的输入合同，而不是 RSS 的变体。
2. 第一版只做 `Text + Photo`。
3. 先做离线 `CNML -> ArticleVO` 解析，再接入导入链。
4. 改撤稿、视频音频、多媒体、关系语义，放到第二阶段。

如果直接进入实现，建议先做的最小交付件是：

- 一个 `buildArticleVOFromCnml(...)`
- 两个真实样例测试
- 一份“图片实体路径如何补全”的额外确认
