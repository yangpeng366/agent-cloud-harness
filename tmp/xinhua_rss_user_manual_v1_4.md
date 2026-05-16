新华全媒新闻服务平台RSS

用户手册

V1.4

新华社技术局

2020年9月

# 简介

新华全媒新闻服务平台RSS内容分发系统实时发布新华社2小时之内签发的稿件。您可以通过自行开发的程序定时访问新华社RSS供稿接口，得到基于标准RSS协议的xml稿件列表及稿件详情，将稿件自动下载到本地后存储使用。

# 环境准备

## 硬件环境

您需准备一台连接互联网的服务器，服务器最小配置建议如下。

|  |  |
| --- | --- |
| **项目** | **最小配置** |
| 处理器 | 英特尔第四代（Haswell）Core i7/i5/Xeon（最小4核） |
| 操作系统 | Linux或Windows |
| 内存 | 8GB |
| 硬盘 | 256GB |

## 软件环境

您需自行开发稿件接收程序。请您的技术人员与新华社技术人员对接开发事宜。

# RSS供稿方式流程说明

您自行开发稿件接收程序，定时请求新华社RSS接口（GET），返回标准的RSS协议格式的xml稿件列表，从XML文档结构的相应字段中获取稿件的相关信息，如标题、作者、正文、附件等。将稿件信息下载到本地后存储使用。

用appid和appkey方式访问RSS的流程如下：

![](data:image/png;base64...)

（注意:下载附件时http请求的head中需包含useragent信息，如果useragent为空，系统判断为非法请求予以拦截。)

# 获取RSS地址

## 进入“RSS订阅中心”

使用账号、密码登录新华全媒新闻服务平台http://home.xinhua-news.com，在“综合页”点击RSS订阅中心按钮，或者访问http://home.xinhua-news.com/rss/index 进入“RSS订阅中心”。

![](data:image/png;base64...)

## 查找所需RSS地址

选择所需的线路（栏目）或分类，点击“复制”可以将对应的RSS地址复制到剪贴板，点击“浏览”可以使用浏览器打开RSS链接，看到返回的内容数据。

![](data:image/png;base64...)

## 使用API方式的RSS地址

除了在“RSS订阅中心”中找到RSS地址外，平台还提供第二种RSS接入方式，您可以按照API的规范拼装一个正确的URL。

### 接入URL

接入URL为api.xinhua-news.com/route/rss。

URL拼接规则：

String url = "http://api.xinhua-news.com/route/rss?channelId=" + channelId + "&appId=" + appId + "&timeStamp=" + timeStamp + "&sign=" + sign

拼接的URL示例：

http://api.xinhua-news.com/route/rss?channelId=1766322&appId=uOiM9YpzNl1rU4eDil1zeTgRC3s8C8xd&timeStamp=1561448965519&sign=d68ac8bf4743bddbb93b7406c88685ba110dbdc6

### 接口请求

参数说明：

|  |  |  |  |
| --- | --- | --- | --- |
| **参数** | **必要性** | **说明** | **类型** |
| channelId | 必填 | 所订购线路或者栏目的ID | String |
| appId | 必填 | 应用ID | String |
| timeStamp | 必填 | 时间戳（毫秒级别，13位长整形） | Long |
| sign | 必填 | 签名（用签名规则生成） | String |

channelId查询方法：

1. 点击“综合页”页面最上方的用户名（账号名），进入到“个人中心”。

![](data:image/png;base64...)

（2）进入“个人中心”-“已订购”，“产品编码”为所订购线路或者栏目的ID，如图。![](data:image/png;base64...)

appId查询方法：“个人中心”中点击“用户基础信息”，可以查询到AppId、AppKey等信息。

![](data:image/png;base64...)

![](data:image/png;base64...)

### 签名规则

访问RSS资源时，会进行签名校验，用户需按如下规则生成签名，生成签名的算法为sha1算法。timeStamp为毫秒级别的长整形时间戳。

String str = "appId="+appId+"&appKey="+appKey+"&timeStamp="+timeStamp;

String sign = SHA1Util.getSha1(str);//SHA1Util的类参见以下文件：![](data:image/x-emf;base64...)

参数说明：

|  |  |  |
| --- | --- | --- |
| appId | 必选 | 应用ID |
| appKey | 必选 | 应用key |
| timeStamp | 必选 | 时间戳（毫秒级别） |

appId、appKey查询方法：同上文所述，在“个人中心”中点击“用户基础信息”，可以查询到AppId、AppKey等信息。

# RSS稿件模板

## XML标签说明

|  |  |  |  |
| --- | --- | --- | --- |
| **标签** | **上级节点** | **必要性** | **说明** |
| rss version="2.0" | Root | 必选 | 标识此文档是一个 RSS 文档的RSS声明 |
| channel | rss | 必选 | 用于描述RSS feed |
| language | channel | 必选 | zh-CN（语言） |
| title | channel | 必选 | 媒体名称/网站频道名称 |
| description | channel | 必选 | 媒体名称/网站频道介绍 |
| link | channel | 必选 | 网站频道地址 |
| pubDate | channel | 必选 | RSS发布时间 |
| generator | channel | 必选 | 规定用于生成该 RSS feed 的程序名称 |
| image | channel | 必选 | 指定一个图片，用以与频道一起显示 |
| url | image | 必选 | 图片的URL |
| title | image | 必选 | 图片替代文字 |
| link | image | 必选 | 频道链接 |
| item | channel | 必选 | 定义每一篇文章的信息 |
| title | item | 必选 | 文章标题 |
| link | item | 必选 | 文章URL地址（未登录状态下看不到稿件全文，是未授权状态） |
| evacuate | item | 必选 | 稿件状态（0：正常，2：改稿，3：撤稿） |
| description | item | 必选 | 文章描述/正文 |
| source | item | 必选 | 文章来源 |
| author | item | 可选 | 作者 |
| pubDate | item | 必选 | 文章发布时间 |
| updateDate | item | 可选 | 文章更新时间 |
| productName | item | 可选 | 产品线路名称 |
| productId |  | 必选 | productName的属性，稿件的id |
| videoUrl | item | 可选 | 视频的绝对路径 |
| image |  | 必选 | videoUrl的属性，视频的封面图 |
| imageUrl | item | 可选 | 图片的绝对路径 |

## 访问成功

![](data:image/png;base64...)

## 访问失败

返回JSON信息。

{

state:”failure”,

errcode:-10001,

message:”失败原因”

}

|  |  |
| --- | --- |
| **错误码** | **说明** |
| -10001 | 请求参数缺失 |
| -10002 | 时间戳非法 |
| -10003 | IP错误 |
| -10006 | appId不存在 |
| -10007 | 签名超时 |
| -10008 | 签名非法 |
| -10009 | 访问策略次数超出 |
| -10010 | 访问策略未知 |
| -10011 | 访问策略过期 |
| -10012 | rss文件没找到 |

# 改撤稿说明

## 改稿说明

RSS文件中返回的evacuate标签值为2，表示该稿件为修改稿，需要将对应的原稿删除，用此篇稿件替换。evacuate标签中的属性link值为原稿的link值，属性docId的值为原稿的productId的值。需要通过这个属性link（或者productId）值查找到此篇改稿对应的原稿，完成改稿操作。link和productId都是稿件的唯一属性。

|  |  |  |
| --- | --- | --- |
| **标签** | **必要性** | **值** |
| evacuate | 必选 | 2 |

## 撤稿说明

RSS文件中返回的evacuate标签值为3，表示该稿件为撤稿通知，需要将对应的原稿删除。evacuate标签中的属性link值为原稿的link值，属性docId的值为原稿的productId的值。需要通过这个属性link（或者productId）值查找到此篇撤稿对应的原稿，完成撤稿操作。link和productId都是稿件的唯一属性。

|  |  |  |
| --- | --- | --- |
| **标签** | **必要性** | **值** |
| evacuate | 必选 | 3 |

# 注意事项

## RSS更新策略

新华社RSS内容分发平台稿件根据新华社发稿情况实时更新。用户每次请求地址，返回距当前时间2小时之内的稿件。如无稿件，可能2小时内没有发稿，可登录新华全媒新闻服务平台web站点核实。为避免漏稿，您应确保每2小时至少获取两次以上最新的RSS文件。

## 正文分段符

RSS文件中description字段表示稿件的正文（如果是图片稿或者视频稿，description表示该稿件的文字说明）。对于正文部分的分段标记符，系统支持2种分段方式可供用户选择：“\r\n”分段符和<p> </p>分段标记。

RSS订阅中心中的RSS地址默认返回的文件中使用“\r\n”分段符，如果用户需要使用<p>的HTML方式分段，则需要在RSS地址后添加“/1”或“/3”即可。

例如：

![](data:image/png;base64...)

或：

![](data:image/png;base64...)

如使用api方式的动态地址，请求时增加参数“m=1”或者“m=3”。

注①：

/1面向传统媒体用户，通稿中发布的改稿不可以直接发布，正文中有修改的文字说明。/3服务面向新媒体用户，通稿的改稿可以直接覆盖，无修改的文字说明。同时包含了知识属性分类字段。

注②：

新媒体专线中的所有稿件均为<p>方式分段，不支持“\r\n”分段，不支持此种方式选择。

## 获取稿件的知识属性分类

RSS提供了知识属性分类的补充字段，如果需要使用稿件的新华社知识属性分类。在RSS订阅中心地址之后添加“/2”或者“/3”,可在productName节点的属性位置找到知识属性分类，以及新华社的栏目属性。

productName字段的属性如下表所述：

|  |  |  |  |
| --- | --- | --- | --- |
| **标签** | **属性** | **必要性** | **值** |
| productName | productId | 必选 | 稿件ID |
|  | columnCategoryId | 可选 | 栏目ID |
|  | newsCategoryId | 必选 | 知识属性分类ID |
|  | columnCategoryName | 可选 | 栏目名称 |
|  | newsCategoryName | 必选 | 知识属性分类名称 |
|  | mediaTypeId | 必选 | 稿件类型ID（Text文字、Photo图片、Graph图表Video视频、Multimedia多媒体、Audio音频） |

![](data:image/png;base64...)

或：

![](data:image/png;base64...)

如使用api方式的动态地址，请求时增加参数“m=2”或者“m=3”。

注：/2中的分段方式为/r/n。/3的分段方式为<p></p>。新媒体专线的分段方式统一为<p></p>.

## Link标签内的地址打开后显示“未订购”

每条稿件的item元素中的link为稿件的web发布地址，在浏览器中直接打开后默认显示未订购的状态，用户若需通过link中的url查看全文，则需使用账号登录新华全媒新闻服务平台（home.xinhua-news.com）。该页面为临时预览页面，不能作为稿件内容发布到互联网由终端用户直接访问。稿件的正文和附件请务必从RSS的xml相应字段中提取，保存至本地后使用。

## 视频封面图

对于每一篇视频稿，稿件信息中带有指定的封面图，请下载使用指定的封面图，请勿自行从视频中抽取封面图。

视频封面图的下载地址为vedioUrl标签中的image属性。

例如：

<videoUrl image="https://xxxx.jpg">

<![CDATA[

https://xxxxxx.mp4

]]>

</videoUrl>

## 稿件的类型区分方法

1. 根据附件类型判断

稿件类型有文字稿、多媒体稿、图片稿、视频稿。可以根据附件标签的类型判断稿件类型。图片稿（包含图表）的附件地址标签为<imageUrl>，视频稿的附件地址标签为<vedioUrl>,文字稿和多媒体稿没有附件地址标签<imageUrl>和<vedioUrl>。多媒体稿的附件嵌入到正文中。

1. 稿件类型字段按照7.3中的方法同知识属性分类一同获取。

ProductName节点中mediaTypeId属性字段：

文字稿：Text

图片稿：Photo（照片）、Graph（图表）（注: Graph类型和Photo类型同属图片稿，格式相同）

视频稿：Video

多媒体稿：Mutimedia

音频稿：Audio

稿件格式样例：

![](data:image/x-emf;base64...)

## 按照线路的子栏目获取稿件

如果您希望按照线路的栏目抓取稿件，可以在RSS订阅中心查找所需的栏目对应的RSS地址。如果采用appid和appkey方式抓取RSS，按照栏目抓取RSS需联系新华社技术局（联系方式见后文）进行后台相关配置后方可实现。

## 稿件来源字段

稿件来源字段的内容如为新华社签发部门字母代码，请忽略，在前端统一设置为“新华社”。

## 图片稿件前面存在(1)(2)(3)等序号的情况

目前我社发图片稿的方式是单条签发，相同的标题会在标题前增加(1)(2)(3)等序号，接入方可以按接入需要，去掉标题前面的括号再进行发布。

# 联系方式

我社技术人员通过电话和电子邮件提供7\*24小时用户技术服务。

24小时技术服务电话：010-63073179

技术服务邮箱：feedtech@xinhua.org