# Hidora MCP：让 Agent 控制外接电脑

Hidora 继续用安卓 USB Gadget 作为 **Mac/PC 的真实 USB 键鼠**。本目录的 MCP Server 在 **Agent 所在的另一台电脑** 运行，不在故障电脑运行。支持 macOS、Windows 和 Linux 作为 Agent 主机（要求 Python 3.11+ 和 ADB）。

## 第一次连接（只需配置一次 MCP）

1. 安卓已 Root，使用支持 USB Gadget/HID 的手机；USB 数据线连接被控制的 Mac/PC，在 Hidora 选择「键鼠」并进入操作页。
2. 手机「开发者选项 → 无线调试」打开配对码；在 **Agent 电脑** 上运行：
   ```sh
   adb pair 192.168.1.123:配对端口
   adb connect 192.168.1.123:调试端口
   adb devices
   ```
   配对端口和调试端口按手机实际显示填写，不能直接使用同一个端口。建议将手机保持在可信局域网。
3. Agent 电脑安装 Python 3.11+、Android platform-tools（`adb`），并安装官方 MCP SDK v2：
   ```sh
   python3 -m venv .venv
   .venv/bin/python -m pip install -r mcp/requirements.txt
   ```
4. 在 MCP 客户端配置以下 **stdio** Server（替换绝对路径；仅连接多部手机时配置序列号）：
   ```json
   {
     "mcpServers": {
       "hidora": {
         "command": "/absolute/path/Hidora/.venv/bin/python",
         "args": ["/absolute/path/Hidora/mcp/hidora_mcp.py"],
         "env": { "HIDORA_ADB_SERIAL": "192.168.1.123:调试端口" }
       }
     }
   }
   ```
   上述是通用 MCP 配置示意，不同客户端的配置文件和字段可能不同。若只有一部安卓设备，可省略 `HIDORA_ADB_SERIAL`。

## 每次使用（不需要把 Root、ADB 端口或令牌交给模型）

1. 让 Agent 调用 `hidora_status`，确认手机与 USB 键鼠已经就绪。
2. Agent 调用 `hidora_request_control`。Hidora 页面显示「Agent 请求控制 USB 键鼠」，你点 **允许 10 分钟** 或「拒绝」。
3. 同一轮授权内，Agent 可以调用 `hidora_mouse_move`、`hidora_mouse_click`、`hidora_mouse_scroll`、`hidora_key_press`、`hidora_type_text`。
4. 随时点 Hidora 的 **停止 Agent**，或让 Agent 调用 `hidora_stop_control`。离开键鼠页、App 切后台、USB Gadget 停止、权限到期，都不会静默恢复授权。
5. 重新操作前要重新在手机批准；**不用重新配对 ADB**，前提是无线调试连接依然有效。

## 真实能力与限制

| 能力 | 当前行为 |
|---|---|
| USB 鼠标 | 相对位移、单/双击、竖向 Wheel、横向 AC Pan |
| USB 键盘 | 单键、组合键、最多 64 个 US 布局 ASCII 字符 |
| MCP | 官方 Python SDK v2 stdio，工具可由支持 MCP 的客户端调用 |
| 审批 | 每次显式手机端批准；默认 10 分钟、最多 250 次写操作 |
| 状态 | 读设备状态、回查请求 ID、释放全部输入、立即停止 |
| 查看 Mac/PC 屏幕 | **不提供**；USB HID 不包含画面回传。必须另外使用摄像头、采集卡或其他观察工具 |
| 中文输入 | **不直接支持**；当前 HID ASCII 映射依赖被控端使用 US 键盘布局 |
| 操作成功 | 回执 `usb_written` 仅表示 USB 写入成功，**不等于 Mac/PC 执行成功** |

## 安全与恢复

- 安卓只监听 **127.0.0.1:39083**；Agent 端使用已配对 ADB 的 `adb forward`，不把端口开放给局域网或公网。
- 每次批准由安卓生成随机 256-bit 临时令牌，仅交给本地 MCP 进程内存；不写入 GitHub、日志或普通配置。
- 任何手动触摸键鼠操作会接管并终止 Agent 当前授权。切后台也立即撤权、释放按键。
- 输入操作都带 `request_id`，Agent **不得自动重试**超时/断开的非幂等动作；先用 `hidora_get_receipt(request_id)` 回查。若回执缺失，标记 unknown 并通过真实画面或用户确认。
- 手机端在执行前校验参数、权限、生命周期和预算；无批准时拒绝控制，不通过 ADB 直接绕过审批。
- Android 只启用了本机回环控制端口，无需 Root 打开另一个公网服务。**无线 ADB 本身权限很高**，只能在可信网络配对，禁止公网转发。

## 代码与验证

- `mcp/bridge.py`：ADB 端口转发、配对令牌、USB 命令与 unknown 回执处理（纯 Python 标准库）。
- `mcp/hidora_mcp.py`：官方 MCP SDK v2 工具注册。
- `mcp/tests`：脱离硬件的协议单元测试；真实 Android Root USB HID / macOS 端到端测试仍需单独验证。
- `skills/hidora-control/SKILL.md`：Agent 使用步骤、安全边界和验收规则。

如无画面观察能力，Agent 只能执行用户指定、可预期的低风险快捷键/鼠标动作；不能声称已看见或完成故障修复。
