import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ContentView: View {
    let app: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var navigationPath: [String] = []
    @State private var showSettings = false
    @State private var showFinish = false
    @State private var copied = false
    @State private var saved = false
    @State private var profileToSave: ProfileDocument?
    @State private var saving = false
    @State private var saveError: String?

    var body: some View {
        NavigationStack(path: $navigationPath) {
            ScrollViewReader { reader in
                Form {
                    if !hasProfile || app.phase == .stopping {
                        CaptureGuideSection(app: app).id("task")
                    }
                    if hasProfile { profileSection.id("result") }
                    if app.sessionActive && app.phase != .stopping {
                        Section {
                            Button(app.capture.phase == .running ? "结束获取" : "取消获取") {
                                if app.capture.phase == .running { showFinish = true }
                                else { Task { await app.stopSession() } }
                            }
                        }
                    } else if hasProfile && app.canStart {
                        Section { Button("重新获取存档", action: app.startSession) }
                    }
                }
                .onChange(of: app.notifications.focusRequest) { _, _ in
                    navigationPath.removeAll()
                    showSettings = false
                    showFinish = false
                    withAnimation { reader.scrollTo(hasProfile ? "result" : "task", anchor: .top) }
                }
                .onChange(of: hasProfile) { _, ready in
                    if ready { withAnimation { reader.scrollTo("result", anchor: .top) } }
                }
            }
            .navigationTitle("Garupa Export")
            .navigationDestination(for: String.self) { _ in
                List {
                    if case .ready(let profile) = app.profiles.state {
                        ForEach(profile.warnings.indices, id: \.self) { index in
                            Text(profile.warnings[index]).textSelection(.enabled)
                        }
                    }
                }
                .navigationTitle("转换提示")
                .navigationBarTitleDisplayMode(.inline)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("设置", systemImage: "gearshape") { showSettings = true }
                }
            }
            .sheet(isPresented: $showSettings) {
                NavigationStack {
                    ExportSettingsView(app: app)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { showSettings = false } } }
                }
            }
            .sheet(isPresented: $showFinish) {
                NavigationStack {
                    Form {
                        Section {
                            Text("设置 → Wi-Fi → 原网络 → 配置代理 → 关闭。")
                            Text("切换网络不会清除原 Wi-Fi 的代理设置。")
                                .foregroundStyle(.secondary)
                        }
                        Section {
                            Button("已关闭代理，结束获取") {
                                showFinish = false
                                Task { await app.stopSession() }
                            }
                            .disabled(app.capture.environment?.route == .local)
                            if app.capture.environment?.route == .local {
                                Text("当前网络仍使用本地代理，请先关闭。")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .navigationTitle("结束获取")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { showFinish = false } } }
                }
            }
            .fileExporter(isPresented: $saving,
                          document: profileToSave.map { ProfileFile(json: $0.json) },
                          contentType: .json,
                          defaultFilename: profileToSave?.url.deletingPathExtension().lastPathComponent ?? "bestdori") { result in
                switch result {
                case .success:
                    if profileToSave?.id == profileID { saved = true }
                case .failure(let error):
                    if (error as? CocoaError)?.code != .userCancelled { saveError = error.localizedDescription }
                }
                profileToSave = nil
            }
            .alert("保存失败", isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
                Button("好", role: .cancel) {}
            } message: { Text(saveError ?? "") }
        }
        .task {
            app.setForeground(scenePhase == .active)
            await app.activate()
        }
        .onChange(of: scenePhase) { _, phase in
            app.setForeground(phase == .active)
            if phase == .active { Task { await app.activate() } }
        }
        .onChange(of: profileID) { _, _ in
            navigationPath.removeAll()
            copied = false
            saved = false
        }
        .task(id: copied) {
            guard copied else { return }
            do { try await Task.sleep(for: .seconds(2)) } catch { return }
            copied = false
        }
        .task(id: saved) {
            guard saved else { return }
            do { try await Task.sleep(for: .seconds(3)) } catch { return }
            saved = false
        }
    }

    @ViewBuilder
    private var profileSection: some View {
        switch app.profiles.state {
        case .ready(let profile):
            Section("Bestdori 档案") {
                VStack(alignment: .leading, spacing: 4) {
                    Text(profile.name).font(.headline)
                    Text("卡牌 \(profile.cardCount) · 区域道具 \(profile.areaItemCount) · 潜能 \(profile.potentialCount)")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .textSelection(.enabled)
                Button {
                    UIPasteboard.general.string = profile.json
                    copied = true
                } label: {
                    Label(copied ? "已复制" : "复制档案", systemImage: copied ? "checkmark" : "doc.on.doc")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button {
                    guard !saving else { return }
                    profileToSave = profile
                    saving = true
                } label: {
                    Label(saved ? "已保存" : "保存文件", systemImage: saved ? "checkmark" : "square.and.arrow.down")
                }
                .disabled(saving)
                ShareLink(item: profile.url) { Label("分享", systemImage: "square.and.arrow.up") }
                if !profile.warnings.isEmpty {
                    NavigationLink(value: "warnings") {
                        Label("转换提示（\(profile.warnings.count)）", systemImage: "exclamationmark.triangle")
                    }
                }
            }
        case .failed(let message, let refreshSuggested):
            Section("档案生成失败") {
                Text(message).foregroundStyle(.red).textSelection(.enabled)
                if let error = app.catalog.error, error != message { Text(error).foregroundStyle(.red) }
                Button(refreshSuggested ? "刷新数据并重试" : "重新解析") {
                    app.profiles.retry(refreshCatalog: refreshSuggested)
                }
                .buttonStyle(.borderedProminent)
                .disabled(app.catalog.loading)
                Button(refreshSuggested ? "重新解析" : "刷新数据并重试") {
                    app.profiles.retry(refreshCatalog: !refreshSuggested)
                }
                .disabled(app.catalog.loading)
                if app.catalog.loading { ProgressView("正在刷新卡牌数据") }
            }
        case .empty, .parsing:
            EmptyView()
        }
    }

    private var hasProfile: Bool {
        switch app.profiles.state {
        case .ready, .failed: return true
        case .empty, .parsing: return false
        }
    }

    private var profileID: UUID? {
        if case .ready(let profile) = app.profiles.state { return profile.id }
        return nil
    }
}

private struct ExportSettingsView: View {
    let app: AppModel
    @Environment(\.openURL) private var openURL

    var body: some View {
        Form {
            Section("Bestdori 卡牌数据") {
                LabeledContent("状态", value: catalogStatus)
                if app.catalog.loading { ProgressView() }
                if let cache = app.catalog.cache {
                    LabeledContent("卡牌", value: "\(cache.cardCount) 张")
                    LabeledContent("最近更新") {
                        Text(Date(timeIntervalSince1970: Double(cache.updatedAtMillis) / 1000),
                             format: .dateTime.year().month().day().hour().minute())
                    }
                }
                if let error = app.catalog.error { Text(error).foregroundStyle(.red) }
                Button(app.catalog.cache == nil ? "下载数据" : "刷新数据") { Task { await app.catalog.refresh() } }
                    .disabled(app.catalog.loading)
            }
            Section("运行状态") {
                LabeledContent("证书", value: app.certificates.state.title)
                LabeledContent("代理", value: app.capture.status)
                if app.capture.phase == .running {
                    LabeledContent("连接", value: app.capture.proxyVerified ? "已连接" : "未连接")
                    if let error = app.capture.verificationError { Text(error).foregroundStyle(.secondary) }
                    Button("重新检测连接") { app.recheck(forceProxyCheck: true) }
                        .disabled(app.capture.verifying || app.busy)
                }
            }
            Section("权限") {
                LabeledContent("通知", value: app.notifications.enabled ? "已开启" : "未开启")
                Button("打开本应用设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var catalogStatus: String {
        if app.catalog.loading { return app.catalog.cache == nil ? "正在下载" : "正在刷新" }
        if app.catalog.upToDate { return "已是最新数据" }
        return app.catalog.cache == nil ? "未下载" : "已缓存"
    }
}
