# TV DY

抖音 TV 版，遥控器刷抖音。

本 fork 以轻量 WebView 方案为主，目标是优先保证电视遥控器可用、登录流程不被打断、上下刷视频尽量稳定。

## 按键说明

| 按键 | 功能 |
|------|------|
| 上/下 | 切换视频 |
| OK 短按 | 播放 / 暂停 |
| OK 长按 | 进入 / 退出光标模式 |
| 左/右 | 后退 / 快进 5 秒 |
| 菜单 | 进入 / 退出光标模式 |
| 返回 | 双击退出 |

## 光标模式

按菜单键或长按 OK 进入光标模式后：

| 按键 | 功能 |
|------|------|
| 上/下/左/右 | 移动光标 |
| OK 短按 | 点击当前位置 |
| OK 长按 | 退出光标模式 |
| 菜单 / 返回 | 退出光标模式 |

## 当前改造

- 放开抖音登录、账号、OAuth 等页面跳转，避免扫码或账号登录时被强制跳回推荐页。
- 上/下键增加防抖，减少电视遥控器连发导致一次跳多条视频。
- 上/下键优先发送网页快捷键；如果页面没有响应，再补一次整屏滚动。
- 左/右键发送网页原生 ArrowLeft / ArrowRight，让抖音播放器自己处理后退 / 快进。
- 补充 WebView 的 Cookie、DOM Storage、硬件加速和视频播放相关配置。
- 参考 `ygtec-org/douyin-tv` 的方式实现稳定伪全屏：选中当前可见 video，固定铺满 `100vw/100vh`，比点击网页全屏按钮更可靠。
- 针对电视观看放大 WebView 字体、遥控器提示和功能轮盘文字。
- 新增光标模式，可用遥控器移动光标并点击页面任意位置。
- 默认尝试打开网页自带 Autoplay 开关，并监听 video 结束后自动触发下一条。
- 页面检测到 video 后等待 10 秒，自动进入 video 伪全屏；上下切换时会临时退出并重新铺满新视频。
- 适配小米盒子等少按钮遥控器：没有菜单键时可用 OK 长按进入/退出光标模式。
- 增加轻量 TV 模式：减少网页动画/模糊效果，节流 DOM 监听，并尝试自动选择 720p/标清/流畅等较低画质。

<img width="2176" height="1224" alt="MuMu20260610133333" src="https://github.com/user-attachments/assets/5c459737-44d6-4f4d-afda-22ddf39d04ed" />
<img width="2176" height="1224" alt="MuMu20260610133403" src="https://github.com/user-attachments/assets/472199fb-24c6-48ca-bdb7-e297fe869e02" />
<img width="2176" height="1224" alt="MuMu20260610133429" src="https://github.com/user-attachments/assets/94a4f3f1-4801-472e-98a2-87b336ab3c0d" />
<img width="2176" height="1224" alt="MuMu20260610133459" src="https://github.com/user-attachments/assets/1ec48712-828d-4c8d-9834-aa79c39ee0f1" />





