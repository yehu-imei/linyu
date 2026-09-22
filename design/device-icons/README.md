# 设备图标源文件

这里保存**设备类型图标**的 SVG 原图，以及它们在 Android 工程中的对应关系。

## 文件

| 文件 | 说明 |
|---|---|
| `shower.svg` | 热水器 / 淋浴——源图（多色 emoji 风格）|
| `sink.svg` | 洗手台 / 洗漱——源图 |

生成物在 `app/src/main/res/drawable/`：

| 源图 | 生成 | 用在 |
|---|---|---|
| `shower.svg` | `ic_device_shower.xml` | 🚿 热水器 |
| `sink.svg` | `ic_device_sink.xml` | 🪥 洗手台 |
| — | `ic_device_*`（暂无）| 🚰 饮水机三种还是 emoji，见下 |

## 为什么是 emoji 图，不是图标

这两张原本是 **emoji 风格的彩色图**（多色 + 细节丰富），不是为 24dp 单色图标设计的。当前 Android vector 生成物做了两项适配：

1. **合并成单色**——颜色全部丢掉，实际颜色由使用处的 `tint` 决定
2. **砍细节**——花洒原本有 7 条水流线，缩到 14dp 会糊成一片，砍到 3 条

## 饮水机那三个还没换

`🚰 / ❄️ / ♨️` 目前仍是 emoji。要换的话：

- 在这个目录补充源图，并在 `app/src/main/res/drawable/` 中提供对应 vector drawable
- 在 `ui/DeviceGlyph.kt` 的 `deviceIconRes()` 里加两行映射

⚠️ **数据层不用动**——emoji 字符串仍然贯穿 `PrefsHelper.lastDeviceEmoji` 和小组件快照，
换图标只发生在渲染时。这是有意的：直接改 emoji 字面量会让**老用户设备上存着的旧值认不出来**。

> 顺带一提：小组件那边**不换**，账单行和附近设备行的圆头像继续用 emoji。
