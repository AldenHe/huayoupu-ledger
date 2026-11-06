# 来源与第三方许可

自 2026-10-31 起，本项目代码以 **GPL-3.0-only** 开源，全文见 [LICENSE](LICENSE)。范围包括本项目有权许可的应用、核心逻辑、测试、脚本及文档。第三方文件保留原有许可和署名，不将基础库、识别模型或商标声明为自研或独占所有。

历史 GPL 授权保持有效。0.5.62 起曾使用的限制性声明属于历史状态，不限制当前 GPL 授权。项目曾使用「知余记账」「慧铭记账」名称，并曾预览「账小伴」品牌，自 2026-11-04 起正式更名为「花有谱」；改名不改变既有许可、作者署名和用户数据格式。

## 历史参考记录

早期版本曾复用或参考 [AutoAccountingOrg/AutoAccounting](https://github.com/AutoAccountingOrg/AutoAccounting) 的 OCR 封装和远程配置实现；相应业务实现后来已移除或替换，当前应用不以该项目作为运行依赖。此处仅记录历史来源，不表示当前业务代码仍复用其实现。核查基线为 `34b38f82ca9ad6d7905c715c66891977f8809c88`；原有授权与第三方权利保持有效。

## 当前默认 V6 构建

| 内容 | 用途 | 许可 / 声明 |
| --- | --- | --- |
| PaddleOCR Android SDK、V6 Tiny 模型 | 本地检测与识别 | Apache-2.0；固定源码与模型哈希见 `third_party/ocr-runtime/config/upstream-lock.json` |
| ONNX Runtime 1.21.1 reduced | 模型执行 | MIT 及完整 ThirdPartyNotices；固定构建见 `third_party/ocr-runtime/config/ledger-v6-runtime-lock.json` |
| Eigen | ONNX 数值运算 | MPL-2.0 及上游文件级许可；固定原始源码随仓库提供 |
| OpenCV 4.12.0 minimal | 图片处理 | Apache-2.0；内部 libpng / libjpeg-turbo 保持自身许可 |
| LLVM libc++ | 原生 C++ 运行库 | Apache-2.0 with LLVM Exceptions 及完整 NDK 通知 |
| AndroidX、Kotlin/coroutines、Guava ListenableFuture、JetBrains Annotations、JSpecify | 基础运行功能 | 发布者 Apache-2.0 声明及构件内原文 |
| Font Awesome Free 6.5.2 | CSS 与字体图标 | CSS MIT；woff2 字体 SIL OFL 1.1；SVG/JS 图标 CC BY 4.0 |

当前 V6 不打包 ML Kit。随包 Maven 目录按实际运行坐标过滤，不能将旧 ML Kit 的“65 个组件”作为 V6 数量。生成清单包含坐标、SHA-256 和原文链接，可在“关于 → 许可与第三方声明”离线查看。ONNX 汇总通知也覆盖本精简构建未启用的模块，不以通知出现与否推断实际链接结果。

原生许可、对应源码与复现说明见 [third_party/ocr-runtime/README.md](third_party/ocr-runtime/README.md)。SDK 原始文件保持固定；CPU arena 策略适配器带有衍生与修改声明。模型权重没有改成自有模型。

This software is based in part on the work of the Independent JPEG Group.

## 历史 ML Kit

早期版本使用过 Google ML Kit，当前构建已移除该引擎及构建入口。基础通知目录仅保留当前 V6 所需的依赖声明；实际随包组件以生成的许可清单为准。历史分发的 SDK 仍适用其原有条款。

## 构建工具

Gradle Wrapper 使用 Apache-2.0；Release 网页构建工具 javascript-obfuscator 使用 BSD-2-Clause，原声明位于 `tools/web-protection/`，生成资产会保留。源码保留可编辑 JavaScript，构建产物的压缩或混淆不改变源码许可。

## 字体、品牌与参考

- 小票交互参考 UI Buttons 的按键层次（MIT）并改编 Arslan Agayev 的 [Payment Receipt](https://github.com/arslanagayev/payment-receipt-animation) 分段走纸（MIT，1c893a2）。完整原始许可随包保留于 `app/src/main/assets/web/receipt-interaction-LICENSE.txt`。ReceiptLine 仅作票据排版研究，未引入代码或运行依赖。

- Font Awesome 来自 [FortAwesome/Font-Awesome 6.5.2](https://github.com/FortAwesome/Font-Awesome/tree/6.5.2)，完整许可在 `app/src/main/assets/web/vendor/LICENSE.txt`，不删除署名。
- 五家银行标识来自官网，仅用于识别账户类型，权利属于各银行，不表示服务关系或背书。来源见下方“银行图标来源”。GPL 不授予银行商标权，也不替代标识使用所需授权。
- 微信、支付宝账户图标来自各自官网发布的原始图像，仅用于识别账户类别，未重绘。来源和机械适配见下方“支付品牌图标来源”；商标权属于相应权利人，GPL 不包含商标授权。
- 花有谱启动图标采用 2026-11-04 通过 toImage 配置的备用服务、gpt-image-2.5 模型生成的“账页开花”素材；项目完成透明前景、Android 自适应与单色资源适配。原图、最终提示词和适配说明见 `.github/branding/`。历史“慧铭”启动图标曾使用 qwen-image-3.0 生成素材，现已替换。源码许可不提供第三方品牌背书或商标许可。
- 视觉参考为用户提供的 HTML ZIP，来源与摘要见下方“视觉参考来源”。原型原件没有独立开源许可，公共源码导出排除其原始 HTML、CSS 和归档；来源记录保留，不将第三方参考改标为 GPL。

分发者需核对实际版本中的依赖与素材范围，同时提供对应源码、构建说明和许可原文。

## 银行图标来源

采集时间：2026-10-19。均从对应银行首页 HTML 或其加载的 CSS 定位资源，不依赖第三方图标服务。只裁切图形标识，不重绘或改变配色，透明画布统一为 80 × 80；运行时按 36 CSS px 等比显示。

| 银行 | 官网原始资源 | 裁切范围（像素，左上右下） |
| --- | --- | --- |
| 工商银行 | https://v.icbc.com.cn/userfiles/Resources/ICBC/shouye/images/2017/logo.png | 78,0,117,41 |
| 农业银行 | https://www.abchina.com/cn/images/logo_ue2.png | 0,0,47,48 |
| 中国银行 | https://www.boc.cn/images/boc2013_logo.png | 0,0,51,49 |
| 建设银行 | https://image4.ccb.com/cn/home/company/v3/images/img/20150828_1440745919/20151201084052927594.png | 0,0,39,40 |
| 交通银行 | https://www.bankcomm.com/BankCommSite/shtml/jyjr/cn/images/logo_img_1004.png | 0,11,34,51 |

打包文件：`app/src/main/assets/web/banks/*.png`。品牌标识权利仍属于各银行，仅用于展示用户自行维护的账户类型。银行选择不会建立银行连接，也不会读取卡号、余额或交易。

## 支付品牌图标来源

采集时间：2026-11-06。通过官网实际页面的图标链接定位原文件，不使用第三方重绘图库。

| 品牌 | 官网页面 | 官网引用的原图 |
| --- | --- | --- |
| 微信 | https://www.wechat.com/en/ | https://newres.wechat.com/t/fed_upload/08b92997-2415-408f-b706-442ed4ede518/OTE0YTAw.png |
| 支付宝 | https://www.alipay.com/ | https://mdn.alipayobjects.com/rms/afts/file/A*7EyORZxdNDkAAAAAAAAAAAAAARQnAQ |

微信使用官网 `apple-touch-icon-precomposed` 的180×180原图；支付宝使用官网 `shortcut icon` 引用的透明PNG，只裁去透明空白、方画布居中并等比缩至180×180，保留原图配色和轮廓。打包文件位于 `app/src/main/assets/web/payment-brands/`，随包来源说明见同目录 `SOURCE.txt`。这些图标不表示花有谱与微信、支付宝存在合作、官方认证或账户连接。

账户方标 `alipay-square.png` 来自支付宝官方下载页 `https://mobile.alipay.com/index.htm` 所链接的 Android 安装包：`wallet_103608100_client_release_202303081916_64.apk` 的 `res/drawable-xxxhdpi-v4/appicon.png`。保留192×192原始图像，仅补充来源元数据；完整下载链接见随包 `SOURCE.txt`。

## 视觉参考来源

当前用户提供 ZIP：记账-version1.0-20261016-141311-533.zip

SHA-256：`b1d41f7fa82c345be8bfda6c9488a80680883e11e8d2d869c4b009cc5365c3ff`

原型原件不随项目分发。新版新增手动记账、圆环统计、账户资产和首页待确认入口。原稿演示记录、资产金额、密钥输入仅作原型参考，应用接入真实本地数据及离线 OCR。

前版：记账-version1.0-20261016-135232-679.zip，SHA-256 `b360b498a461b64ae40f148aa66b1eeabb299ab5e52fe4cc2b018bf6b1582381`。

原件未提供独立开源许可；保留摘要和来源记录不代表授予其再分发权。当前项目代码的开源许可见根目录 LICENSE，第三方范围见 NOTICE.md。

2026-11-05 账单汇总的全屏送纸交互另外参考 aqkprogrammer/receipt-printer（MIT，Copyright (c) 2026 Abdul Qadir Khan）。仅采用本机热敏小票显示的送纸与纸边设计，未引入支付演示、支付数据、声音或网络依赖。许可原文随 app/src/main/assets/web/receipt-interaction-LICENSE.txt 分发。
