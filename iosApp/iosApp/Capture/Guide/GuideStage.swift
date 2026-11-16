import Foundation

/// One set of stage descriptions is used by the native guide and local notifications.
enum GuideStage: String {
    case welcome, preparing, certificate, startingProxy, configureProxy, verifyingProxy
    case waitingForGame, receiving, generating, complete, conversionFailed
    case backgroundRequired, startFailed, stopping

    var step: Int? {
        switch self {
        case .welcome, .stopping: return nil
        case .preparing, .backgroundRequired, .startFailed: return 1
        case .certificate: return 2
        case .startingProxy, .configureProxy: return 3
        case .verifyingProxy: return 4
        case .waitingForGame, .receiving, .generating, .complete, .conversionFailed: return 5
        }
    }

    var title: String {
        switch self {
        case .welcome: return "获取你的 Bestdori 档案"
        case .preparing: return "准备卡牌数据与后台会话"
        case .certificate: return "安装并信任本地证书"
        case .startingProxy: return "启动本地代理"
        case .configureProxy: return "连接本地代理"
        case .verifyingProxy: return "验证代理连接"
        case .waitingForGame: return "连接就绪，请重新打开游戏"
        case .receiving: return "正在接收游戏存档"
        case .generating: return "正在生成 Bestdori 档案"
        case .complete: return "Bestdori 档案已生成"
        case .conversionFailed: return "存档已收到，转换未完成"
        case .backgroundRequired: return "需要恢复后台运行"
        case .startFailed: return "暂时无法开始"
        case .stopping: return "正在停止会话"
        }
    }

    var description: String {
        switch self {
        case .welcome:
            return "按提示准备证书和 Wi-Fi 代理，再重新打开游戏并登录。获取到存档后，应用会自动生成可导入 Bestdori 的档案。整个过程只需这台手机。"
        case .preparing:
            return "正在检查本地卡牌数据，首次使用会自动下载，并准备在切换到设置或游戏时继续运行。"
        case .certificate:
            return "请在系统设置中安装描述文件，并开启证书的完全信任。应用会持续检测，验证通过后自动进入下一步。"
        case .startingProxy:
            return "证书验证已通过，正在启动本地代理。请稍候再修改 Wi-Fi 设置。"
        case .configureProxy:
            return "本地代理已启动。请为当前 Wi-Fi 设置 HTTP 代理，保存后返回这里，应用会自动检测。"
        case .verifyingProxy:
            return "正在检查当前生效的代理配置，并确认检测请求确实经过本次会话的本地代理。"
        case .waitingForGame:
            return "代理连接已验证。请将本应用留在后台，重新打开游戏并登录。"
        case .receiving:
            return "已收到存档响应，请保持游戏连接，接收完成后会自动生成档案。"
        case .generating:
            return "存档已接收完毕，正在转换卡牌、区域道具和角色潜能。"
        case .complete:
            return "下方可以查看结果和转换提示，也可以复制、保存或分享档案。"
        case .conversionFailed:
            return "请查看下方原因。原始响应仍保留在本次会话中，可以刷新卡牌数据后重新解析。"
        case .backgroundRequired:
            return "后台定位已停止。请返回本应用并恢复定位权限，再继续设置或切换到游戏。本地代理不会因此主动关闭。"
        case .startFailed:
            return "本次准备没有完成，请查看原因后重试。"
        case .stopping:
            return "正在停止本地服务器与后台运行，已生成的档案仍保留在本次应用运行期间。"
        }
    }

    var isMilestone: Bool {
        switch self {
        case .configureProxy, .waitingForGame, .complete, .conversionFailed, .backgroundRequired, .startFailed:
            return true
        default: return false
        }
    }
}
