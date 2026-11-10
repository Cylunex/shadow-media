# 阅读中换源：范围与会话保留

日期：2026-11-10。本轮是源码优化，未部署服务、未构建或安装 APK。由本机开发会话直接实现与验证；未把这次源码工作记为 Losna 已自行完成的领域操作。

## 发现与实现

详情“找书源”原有 `NovelSourcePicker`，阅读中 `NovelReaderSourcesPanel` 只有“只看同名同作者”，没有标签／分组入口。搜索请求也没有保留提交范围；重试结果中的 remaining=0 会覆盖之前尚未搜索的数量。

- 阅读面板顶部常驻“搜索范围”，可查找已有分组和标签，多组选并集、多标签同时满足；可清除、取消或应用草稿。入口与“在线搜索”快捷跳转在已关联来源长列表之外。“只看同名同作者”明确是结果筛选，独立于搜索范围。
- 复用 Novel `/source-browser` 与 `/source-selection`，只发送仍可搜索的交集；空交集显示提示，不降级成全库。失效分组保留提示，可主动移除，不悄悄丢弃。范围只保存 ID 和标签，不持久化来源响应或 URL。
- `NovelSourceScopeStore` 移到 `core:library`，详情/浏览器继续使用原偏好文件；阅读范围按连接单独保存，搜索任务与结果按当前作品保留在内存。关闭重开不新建搜索任务。
- 每次提交保存关键词、分组和具体候选源快照。之后修改范围只影响下一次新搜索。继续搜索、失败/关联重试使用原任务范围；新出现的书源不会混入原任务。旧服务仍按 12 源分批；新服务使用原有 auto/all 能力，不增加服务并发。
- 剩余数由完整候选快照减去已完成来源推导，子集重试不会吃掉未查范围。已返回来源按身份合并、固定 key 保留；新搜索等待期间保留旧行并提示“暂显上次结果”，新结果到达或任务结束后替换。断网继续轮询原任务，停止保留已有结果。
- 已关联来源继续在刷新时保留；目录/章节推荐预览不调用换源回调，也不写进度。只在明确选择续读或章节时打开。书名相同、作者未知或不同仍不自动关联；身份规则与服务端版本校验不放宽。
- 目录按来源保存 Compose 可恢复状态及最多 12 个目录页，保留查询、排序、页码和滚动位置。当前版本传入本机正在读的章节 ID/ordinal；其他版本使用服务端映射与 `chapter_ordinal`。新增只读 `contents?anchor_id=` 解决目标 ID 不在首页的问题，按固定版本内实际行数定位，支持不连续序号与倒序。旧服务可忽略新参数，仍沿用 ordinal 定位。
- 映射加载或失败有明确提示与重试；无法唯一定位时禁用直接续读，保留手动选章。目标来源目录显示“对应阅读位置”，不误称为正在阅读。关闭目录回到来源列表、关闭重开面板都保留浏览位置；换源后的原位置返回仍使用既有跳转历史。

## 外部源码核对与取舍

本轮阅读官方源码，不引入外部依赖或复制实现。参考文件保留在工作区 `reference/reading/`，不随子仓库提交。

| 固定参考 | 源码证据与许可证 | 在 Shadow 的取舍 |
|---|---|---|
| 新增 hadc188/reader，`777357f638745f0600c2aba45dddd92b98fee1fc` | [ReaderSource.vue](https://github.com/hadc188/reader/blob/777357f638745f0600c2aba45dddd92b98fee1fc/frontend/src/components/reader/ReaderSource.vue)、[sourceCandidateCache.ts](https://github.com/hadc188/reader/blob/777357f638745f0600c2aba45dddd92b98fee1fc/frontend/src/utils/sourceCandidateCache.ts)、[LICENSE](https://github.com/hadc188/reader/blob/777357f638745f0600c2aba45dddd92b98fee1fc/LICENSE)，MIT；原始文件已读取 | 中断结果保留、完整/不完整结果区分、停止请求后恢复控件状态适合现有任务接口。Shadow 保留旧行等待新结果，继续查询精确候选快照。其 startSearch 的注释与实现有差异：该固定版本实际打开仍调用搜索；不据缓存工具的注释宣称它完全不重搜。其不限量持久化缓存不照搬，Shadow 来源响应只留内存，目录页有界。 |
| FnReader v2.0.6，`441fb192cdae391f196d52db9161a1f42087ff53` | [换源弹窗](https://github.com/TZYLXS/FnReader/blob/441fb192cdae391f196d52db9161a1f42087ff53/legado-web/app/web/app.js)、[服务端换源搜索](https://github.com/TZYLXS/FnReader/blob/441fb192cdae391f196d52db9161a1f42087ff53/legado-web/app/server/src/server.js)，Apache-2.0，本机固定克隆复核 | 采用显示当前章节、明确来源作者、按标题帮助定位的交互思路。其服务端接受 group，弹窗自身没有完整标签选择；不把它描述为现成标签面板。其按书名候选和直接替换书架条目的方式不适合 Shadow，保留严格身份、固定版本与显式选章。 |
| LNReader，`dc394e8540d44902efc17b5a35485bdb0724bb00` | [Migration.tsx](https://github.com/LNReader/lnreader/blob/dc394e8540d44902efc17b5a35485bdb0724bb00/src/screens/browse/migration/Migration.tsx)、[migrateNovel.ts](https://github.com/LNReader/lnreader/blob/dc394e8540d44902efc17b5a35485bdb0724bb00/src/services/migrate/migrateNovel.ts)、[LICENSE](https://github.com/LNReader/lnreader/blob/dc394e8540d44902efc17b5a35485bdb0724bb00/LICENSE)，MIT，源码与许可证原文已核对 | 来源选择与迁移执行分开值得保留。其迁移按章号转移阅读状态，并在事务中删除原作品条目；本轮不采用这些行为。Shadow 保留原来源、原阅读位置和既有 revision 冲突机制，不按章号猜测字符偏移。 |

此前的 yyx-library 是离线书库与文字检索参考，本轮未在其源码找到可复用的联网标签换源机制。Readest 官方仓库声明 AGPL-3.0，继续作为目录/阅读交互背景参考，不复制代码。Legado 原官方地址本轮已只显示内容删除公告；已有本地旧快照仅作历史参考，不冒称当前在线版本已重新核验换源源码，也不从不明分叉搬代码。

## 验证与边界

- Novel 使用显式隔离 JSON 配置和临时 SQLite/书根验证，覆盖多组 OR + 多标签 AND、停用来源排除、固定候选续查/重试、分组变更拒绝、非连续 ordinal、正倒序定位、跨版本拒绝与进度只读。CLI schema 由 OpenAPI 自动包含 anchor_id。
- Media 三个模块执行单元/Compose 主机测试与 Lint，覆盖范围应用/保存、改草稿后的原范围续查/重试、列表和目录关闭返回位置、旧服务兼容、停止/断网恢复及未知作者身份边界。
- Novel 全量 393 项 Python 与 8 个 Node 测试文件通过；Media 三模块共 357 项（353 通过、4 项可选外部输入跳过），三模块 Lint 通过。主机回归不等同新 APK 真机验收；本轮未连接生产书库或改写跨设备进度，也未修改部署配置。
