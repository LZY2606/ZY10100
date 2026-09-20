# GSB — 多来源分割掩膜共识台（Pair-wise Ground-Truth Segmentation Builder）

研究团队对同一张高分辨率图像导入多套分割掩膜（不同算法或人工修订）。本系统在**不修改任何输入掩膜**的前提下，校验证据、计算一致/分歧/未覆盖、叠加瓦片浏览、做区域级决定，并在门控通过后发布与导出可再导入的版本。

纯 JDK（JDK 21+，已在 JDK 24 上验证）+ Gradle，内置 `com.sun.net.httpserver`，**运行与测试都不依赖外部网络**（依赖与 Gradle 发行版均从本机缓存离线解析）。

## 构建 / 测试 / 启动

```bash
./gradlew --no-daemon assemble
./gradlew --no-daemon test && ./gradlew --no-daemon run --args='--port 5223'
```

打开 <http://127.0.0.1:5223>。

可选参数：`--port <n>`（默认 5223）、`--data <dir>`（默认 `./data`）。
离线环境可加 `--offline`；命令本身在缓存齐全时不联网。

一键播种演示数据（另开一个终端，服务启动后执行）：

```bash
python3 scripts/seed_demo.py http://127.0.0.1:5223 demo
```

## 它到底保证了什么

- **证据不可变**：每个来源掩膜以 RLE 压缩 blob 写入内容寻址存储（SHA-256 分桶、原子落盘、去重），接收后**永不原地改写**。算法更新是一条新的来源修订（`revisionOf` / `revisionNo`），旧决定保留并标记“来源已过期”。
- **像素坐标系完整保留**：来源携带尺寸、像素间距（spacingX/Y）、解剖方向（如 `L|A`）、原点。比较前逐项校验；宽高相同**绝不**放行。
- **图像指纹硬门控**：工作区绑定一个图像指纹；指纹不一致一律拒绝（错误码 `INGEST_REJECTED` / `FINGERPRINT_CONFLICT`），即使宽高恰好相同。
- **重采样边界单独标识**：物理覆盖范围相同但栅格不同时，必须显式 `allowResample=true`；最近邻重采样到规范网格，并把落在类别边界/跨多源像素的位置标记为 `edgeFlag`，比较时可隔离（`resampleEdgePixels`），不混同为真实分歧。
- **类别词表版本**：同名类别跨词表版本只做“提议”（`confirmed=false`），**不自动合并**；需要人工确认。发布要求所有映射确认。
- **阈值化比较**：按置信度阈值先排除，再分类为一致 / 分歧 / 未覆盖 / 重采样边界 / 低置信排除；派生结果带规则版本 `comparison-rules-1` 和来源指纹。
- **共识层只引用**：共识栅格由“来源 + 决定事件”派生，从不回写输入掩膜。决定类型：`ACCEPT_SOURCE` / `CORRECTION` / `PENDING`。
- **乐观并发**：每个提交带 `baseVersion`。两个浏览器基于同一旧版本编辑：重叠像素返回**冲突多边形**（409 + 像素数 + 冲突作者/事件），非重叠片段**自动合入**；后到者可据此调整后重新合并。
- **撤销即事件**：撤销追加一条反向 `UNDO`（原事件保留），重做追加 `REDO`。
- **发布门控**：仅当所有类别映射已确认、且不存在越界（词表之外）像素时才可发布；发布快照记录来源指纹、规则集版本、区域面积、边界哈希。
- **导出/再导入一致**：ZIP 内含压缩掩膜、来源索引、映射、完整决定历史；再导入重算**区域面积**与**边界哈希**，不一致即拒（防篡改/防有损往返）。

## 数据布局（`--data` 指向的目录）

```
data/
  blobs/<前2位hash>/<sha256>.bin     内容寻址、不可变证据（掩膜 RLE、置信度、比较栅格、导出包）
  workspaces/<id>/
      workspace.json                 元数据快照（原子写；只是缓存）
      events.log                     追加式决定事件日志（权威；每行 sha前16位:JSON）
```

- 打开工作区时：读快照 + **重放 `events.log`**。
- 每个 blob 读取时都重新计算 SHA-256 校验，损坏立即报 `blob corrupt`。

## HTTP 摘要

- `POST /api/workspaces`，`GET /api/workspaces`，`GET /api/workspaces/{id}/state`
- `POST|GET /api/workspaces/{id}/sources`（指纹/几何/越界校验，可选重采样）
- `GET /api/workspaces/{id}/mappings`，`POST /api/workspaces/{id}/mappings/confirm/{mappingId}`
- `POST /api/workspaces/{id}/comparisons`
- `GET|POST /api/workspaces/{id}/decisions`，`POST .../decisions/{eventId}/undo|redo`
- `GET /api/workspaces/{id}/gate`，`POST /api/workspaces/{id}/releases`
- `GET /api/workspaces/{id}/export`，`POST /api/import`（JSON `{newWorkspaceId,name,bundleBase64}` 或裸 ZIP）
- 瓦片：`GET /api/workspaces/{id}/tiles/{source|comparison|consensus}/{key|-}/{z}/{x}/{y}.png`（256×256）

错误统一为 `{ "error": code, "message", "reasons": [...] }`，`reasons` 给出逐条可读诊断。

## 故障恢复演练（新维护者请务必跑一遍）

事件日志每行带内嵌校验和。崩溃时最后一条“写了一半”的行会在重放时被识别并截断，之前的事件全部恢复；blob 内容哈希保证证据未被静默破坏。

### 演练 1：崩溃产生撕裂事件行

```bash
# 1) 启动并播种、制造一个决定（也可在网页上绘制）
./gradlew --no-daemon run --args='--port 5223'
python3 scripts/seed_demo.py http://127.0.0.1:5223 dr
SID=$(curl -s http://127.0.0.1:5223/api/workspaces/dr/state \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['sources'][0]['sourceId'])")
curl -s -X POST http://127.0.0.1:5223/api/workspaces/dr/decisions \
  -H 'Content-Type: application/json' \
  -d "{\"decisionType\":\"ACCEPT_SOURCE\",\"region\":{\"ring\":[[0.5,0.5],[3.5,0.5],[3.5,3.5],[0.5,3.5]]},\"sourceId\":\"$SID\",\"baseVersion\":0,\"author\":\"alice\"}" >/dev/null
```

停掉服务（Ctrl-C 或 kill -9 模拟断电），然后**手工追加一行损坏/写了一半的事件**：

```bash
printf '{"torn":true,partial' >> data/workspaces/dr/events.log
```

重新启动：

```bash
./gradlew --no-daemon run --args='--port 5223'
# 启动日志：restored workspace: dr
curl -s http://127.0.0.1:5223/api/workspaces/dr/state \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('version',d['eventVersion'],'decisions',len(d['decisions']),'note',d['recoveryNote'])"
# => version 1 decisions 1 note recovered after crash: discarded 1 torn event line(s)
```

`events.log` 末尾的撕裂行已被删除，完整的 CREATE 事件照常重放。该流程同时由 `RecoveryTest.tornAppendAtCrashIsDiscardedOnReopenAndEarlierEventsReplay` 自动化覆盖。

### 演练 2：证据 blob 损坏被发现

```bash
# 找到某来源掩膜 blob，用任意字节覆盖
f=$(find data/blobs -name '*.bin' | head -1)
printf 'xxxx' > "$f"
# 任何读取该 blob 的操作（比较/瓦片/导出/发布）都会得到：
#   IllegalStateException: blob corrupt: expected <hash> got <hash>
```

恢复方式：从该工作区的**导出包**或备份取回该 `<sha256>.bin` 放回 `data/blobs/<前2位>/`（内容寻址，文件名即正确哈希）。损坏不会被“悄悄接受”。对应自动化测试：`RecoveryTest.missingBlobIsDetectedViaContentHash`。

### 演练 3：再导入防篡改

导出后修改 ZIP 再导入会被拒（`BUNDLE_TAMPERED` / `BUNDLE_CORRUPT`，或面积/边界哈希不一致）：

```bash
curl -s http://127.0.0.1:5223/api/workspaces/dr/export -o /tmp/dr.zip
# 页面右上“导出”按钮等价；网页左侧“再导入 ZIP”可直接选择文件
```

对应自动化测试：`ReleaseAndExportTest.tamperedBlobIsRejectedOnImport`、`exportThenReimportKeepsRegionAreaAndBoundaryHash`。

## 代码地图

- `gsb/geom/`：`Geometry`（尺寸/方向/间距/物理范围校验）、`Resampler`（最近邻+边界标记）、`Polygons`（栅格化/交并差/冲突多边形）。
- `gsb/rle/`：`RleCodec`（标签游程 + 1bit 边缘图）、`ConfidenceCodec`（0..255 量化）。
- `gsb/model/`：不可变记录（来源、词表、映射、比较、事件、发布快照、工作区）。
- `gsb/service/`：接收校验、比较、共识/并发、发布门控、共识栅格+边界哈希、导出/再导入、瓦片。
- `gsb/storage/`：`BlobStore`（内容寻址）、`EventLog`（校验和+撕裂恢复）、`WorkspaceStore`（原子快照）、`Json`。
- `gsb/web/`：JDK HTTP 路由、静态资源；前端在 `src/main/resources/web/{index.html,app.js}`（无 CDN）。

## 测试

`src/test/java/gsb` 下 32 个 JUnit Jupiter + AssertJ 用例，覆盖：RLE/置信度往返与损坏检测、指纹门控、方向/间距/越界、重采样边界、跨词表版本不自动合并、阈值化比较、乐观并发冲突与自动合入、撤销/重做、来源过期、发布门控、导出/再导入面积与边界哈希、blob 篡改、WAL 崩溃恢复，以及一整套 HTTP 端到端（含 409 冲突多边形与 PNG 瓦片）。

```bash
./gradlew --no-daemon test
```

测试全部使用临时目录与随机高端口，不访问网络。
