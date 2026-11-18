import Foundation

enum GuideStage: String {
    case welcome, preparing, certificate, startingProxy, configureProxy, verifyingProxy
    case waitingForGame, receiving, generating, complete, conversionFailed
    case backgroundRequired, startFailed, stopping

    var title: String {
        switch self {
        case .welcome: return "获取 Bestdori 档案"
        case .preparing: return "正在准备数据"
        case .certificate: return "安装并信任证书"
        case .startingProxy: return "正在启动代理"
        case .configureProxy: return "设置代理"
        case .verifyingProxy: return "正在检测连接"
        case .waitingForGame: return "等待游戏登录"
        case .receiving: return "正在接收存档"
        case .generating: return "正在生成档案"
        case .complete: return "档案已生成"
        case .conversionFailed: return "档案生成失败"
        case .backgroundRequired: return "需要恢复后台运行"
        case .startFailed: return "无法开始获取"
        case .stopping: return "正在结束获取"
        }
    }

    var description: String {
        switch self {
        case .welcome: return "将游戏存档转换为可导入 Bestdori 的档案。"
        case .certificate: return "下载后在系统设置中安装，并开启完全信任。"
        case .configureProxy: return "设置 → Wi-Fi → 当前网络 → 配置代理 → 手动。保存后返回应用。"
        case .waitingForGame: return "现在可以打开游戏并登录了。如果游戏还在后台，请先在多任务界面上滑关闭游戏，再重新打开。"
        case .receiving: return "请保持游戏连接。"
        case .complete: return "返回应用导出档案。"
        case .conversionFailed: return "返回应用查看原因并重试。"
        case .backgroundRequired: return "需允许定位，以便切换到游戏后维持后台代理。"
        case .preparing, .startingProxy, .verifyingProxy, .generating, .startFailed, .stopping: return ""
        }
    }

    var milestoneNotification: (title: String, body: String)? {
        switch self {
        case .complete, .conversionFailed, .backgroundRequired, .startFailed: return (title, description)
        default: return nil
        }
    }

    var isIndeterminateProgress: Bool {
        switch self {
        case .preparing, .startingProxy, .verifyingProxy, .generating, .stopping: return true
        default: return false
        }
    }
}
