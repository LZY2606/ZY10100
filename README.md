# Pair-wise GSB — 高分辨率图像多来源分割掩膜对账系统

研究团队对同一张高分辨率图像导入多套分割掩膜（不同算法、人工修订）。本系统保留每个来源的**像素坐标系、类别表、置信度、图像指纹**，先做**尺寸/方向/像素间距校验**，再按可选置信度阈值逐像素计算**一致 / 分歧 / 未覆盖**区域，并在网页上提供**瓦片化叠加、区域级决定（接受来源 / 绘制修正 / 置为未决）、并发冲突合并、发布门禁、导出/重导入**。

核心原则：

- **原始证据一经接收不可原地改写**：上传的底图与掩膜进入内容寻址、只追加的证据库（以 SHA-256 指纹为键，永不覆盖）。
- **派生物带规则版本和来源指纹**：重采样后的对齐栅格以自定义压缩格式 `MRLE1` 存储，记录 `derivedFromRuleVersion=pairwise-gsb-v1` 与来源/派生指纹。
- **同名类别、词表版本不同不会自动合并**：只生成 `PROPOSED` 映射，必须人工 `CONFIRMED` 后才参与“一致”判定，发布前所有相关映射都必须确认。
- **指纹不一致禁止比较**：底图指纹不符直接 422，即使宽高完全一致也不放行。
- **重采样边界像素单独标识**，越界（out-of-bounds）像素单独计数并阻断发布。
- **共识层只引用来源与修正事件，绝不修改任何输入掩膜**。
- 算法结果更新后，旧决定保留并标记**来源已过期（stale）**。
- 两个用户基于同一旧版本编辑重叠像素时，后到方收到**冲突多边形（409）**，非重叠部分**自动合入**；撤销产生**反向事件**，历史不删除。
- 发布版本要求**所有相关类别映射已确认且没有越界像素**。
- 导出含**压缩掩膜、来源索引、决定历史**；重新导入后区域面积与边界哈希保持一致。

运行时**主代码零外部依赖**（仅 JDK 内置 HttpServer / ImageIO / Deflate），测试只依赖本地 Gradle 缓存里的 JUnit 5，不需要外部网络。

## 环境要求

- JDK 17 或更高（在 Temurin JDK 24 上验证；构建用 `--release 17`）。
- 已包含 Gradle Wrapper（Gradle 8.14.3），无需系统安装 Gradle。

## 构建、测试、启动

```bash
# 构建（等价于 assemble，产出 build/libs/pair-wisegsb-1.0.0.jar）
./gradlew --no-daemon assemble

# 测试 + 启动（用户指定命令）
./gradlew --no-daemon test && ./gradlew --no-daemon run --args='--port 5223'
```

启动后打开：<http://127.0.0.1:5223>

可选参数：

```bash
--port 5223       # 监听端口（默认 5223）
--data ./data     # 数据目录（默认 ./data）
--recover         # 对所有工作区做一次事件日志损坏恢复后再启动
```

## 60 秒界面导览

1. 左侧「新建图像工作区」选一张 PNG，先在浏览器内算出 SHA-256，连同宽/高/方向/间距一起上传（服务端会重新算指纹强制比对）。
2. 「导入类别词表」：每行 `标签ID,类别名`；同名类别在不同词表版本里是两套词表。
3. 「注册来源」→「上传来源掩膜版本」：可直接上传 `MRLE1`/PNG，也可勾选「使用演示栅格」在浏览器生成并计算指纹。
4. 中间画布是 XYZ 瓦片视图：底图 / 共识 / 仅分歧 / 单来源图层叠加；滚轮缩放、拖拽平移、黄框标记重采样边界像素。
5. 选择「绘制区域」在图上点出多边形，然后「置为未决 / 接受来源 / 绘制修正」提交。
6. 右侧看区域列表（含面积、边界哈希）、决定历史（可撤销）、发布版本；右上可调节置信度阈值、确认类别映射。
7. 底部「导出版本（ZIP）」下载；左侧「导入导出包」校验后在新工作区重建。

## 数据与磁盘布局

```
<data>/
  registry.json                       # 工作区索引
  images/<imageId>/
    events.log                        # 哈希链只追加事件日志（JSON Lines）
    evidence/ab/cd/<sha256>           # 不可变原始/派生证据（内容寻址）
    cache/tiles/<layer>/<z>/*.png     # 惰性渲染、按内容版本失效的瓦片缓存
    events.log.corrupt.<ts>           # --recover 时产生的损坏备份
```

`events.log` 每行一个事件，字段含 `seq,type,at,actor,payload,prev,hash`，其中
`hash = sha256(prev + "\n" + canonical(jsonWithoutHash))`。启动时重放全链并逐行校验，任何断链/篡改都会**快速失败**并指出具体 `seq`。

## 规则语义（pairwise-gsb-v1）

- 每个活跃来源版本对每像素贡献：无声明（label 0、低于阈值、或越界 -1）或一个“概念”。
- 概念只通过**已确认**映射组归并；未确认的引用各自是独立概念，因此同名不同词表版本在确认前表现为分歧。
- 像素状态：
  - `AGREE`：至少一个有效声明，且所有声明同一概念；
  - `DISAGREE`：出现两个及以上不同概念；
  - `UNCOVERED`：没有任何声明达到阈值。
- 连通区域按 4-邻域生成，多边形用 marching-squares 输出整数角点环；`boundaryHash = sha256(规范环坐标 + 像素数)`，与面积一起用于导出/重导入一致性校验。
- 重采样（最近邻）仅在尺寸/方向/间距不一致时发生：目标像元足迹不能恰好覆盖一个完整原生像元时标记为**边界像素**；映射到原生帧外的像素标记为 **-1 越界**。

## HTTP API 摘要

| 方法 & 路径 | 说明 |
|---|---|
| `POST /api/images` | 创建工作区（返回 imageId） |
| `POST /api/images/{id}/upload` | multipart：`meta`(JSON) + `file`(PNG)，强制指纹/几何校验 |
| `POST /api/images/{id}/vocabularies` | 导入词表（含版本） |
| `POST /api/images/{id}/sources` | 注册来源 |
| `POST /api/images/{id}/sources/{sid}/versions` | multipart 上传掩膜版本，校验并重采样 |
| `POST /api/images/{id}/threshold` | 设置置信度阈值 |
| `GET  /api/images/{id}/analysis` | 一致/分歧/未覆盖 + 区域 + 哈希 |
| `POST /api/images/{id}/mappings/{gid}/confirm|reject` | 确认/拒绝类别映射 |
| `POST /api/images/{id}/decisions` | 区域决定（body 带 `baseVersion` 乐观并发） |
| `POST /api/images/{id}/decisions/{did}/undo` | 撤销（反向事件） |
| `POST /api/images/{id}/release` | 发布门禁检查并生成版本 |
| `GET  /api/images/{id}/export` | 下载自包含 ZIP |
| `POST /api/import` | multipart：校验并重建导出包 |
| `GET  /api/images/{id}/tiles/{layer}/{z}/{x}/{y}.png` | 瓦片（layer=base/consensus/disagree/source:{verId}） |

所有写请求可用请求头 `X-Actor: <用户名>` 标识操作者。错误返回统一 JSON：
`{error,status,code,message,details[]}`，`details` 给出可读、可操作的诊断。

## 故障恢复演练（新维护者必读，可完整复现一次）

下面用一个真实工作区演示「损坏 → 检出 → 恢复 → 验证」。

### 1) 制造一个正常工作区

用界面或 API 建好底图、两个来源、确认映射并发布（参见上节）。假设数据目录是 `./data`，工作区 `smoke1`，事件日志有 14 行：

```bash
wc -l data/images/smoke1/events.log        # 例如 14
```

### 2) 人为篡改最后一个事件（模拟磁盘损坏/手工误改）

```bash
python3 - data/images/smoke1/events.log <<'PY'
import sys, json
p = sys.argv[1]
lines = open(p).read().splitlines()
last = json.loads(lines[-1])
last["tampered"] = True                     # 改了 payload 但 hash 没动
lines[-1] = json.dumps(last, separators=(',', ':'))
open(p, 'w').write('\n'.join(lines) + '\n')
PY
```

### 3) 直接启动 —— 必须快速失败，禁止带病服务

```bash
./gradlew --no-daemon run --args='--port 5223'
```

你会看到（进程以非零码退出，不会监听端口）：

```
[pair-wisegsb] FATAL: event chain broken in smoke1 at seq 14:
  event log payload tampered with at seq 14 (recorded hash mismatch)
  Re-run with --recover to truncate after the last verified event
  (a .corrupt backup is created).
```

### 4) 执行恢复

```bash
./gradlew --no-daemon run --args='--port 5223 --recover'
# smoke1: removed 1 invalid lines; backup at .../events.log.corrupt.<ts>
# [pair-wisegsb] listening on http://127.0.0.1:5223
```

恢复器**逐行重放校验哈希链**，保留最后一个完整可验证事件之前的全部前缀，把坏行切掉，并先把原文件备份成 `events.log.corrupt.<时间戳>`。

### 5) 验证恢复结果

```bash
curl -s http://127.0.0.1:5223/api/images/smoke1 | \
  python3 -c 'import sys,json;d=json.load(sys.stdin);print("version",d["version"])'
# version 13  —— 回到被篡改事件之前的一致状态，证据文件原样保留
```

注意：证据是内容寻址的，删事件不会删证据；恢复后如需“重做”被丢弃的操作，重新上传/提交即可（同内容指纹会命中同一证据块，不产生重复）。

## 测试

```bash
./gradlew --no-daemon test
```

覆盖（24 个用例，全部离线）：

- MRLE1 编解码（含 -1 越界标签、置信度、边界位）、损坏文件拒绝；
- 旋转/缩放重采样与边界像素标记；
- 指纹不符即使宽高一致也 422、尺寸不符、各向异性间距、标签超出词表；
- 证据只追加/内容寻址/不可变；
- 同名跨词表版本未确认→分歧，确认后→一致；阈值导致未覆盖；
- 并发编辑：重叠返回冲突多边形、非重叠自动合入、完全重叠 409；
- 撤销反向事件；新算法版本后旧决定 stale；
- 发布门禁（映射未确认/越界阻断，满足后放行）；
- 导出→重导入区域面积与边界哈希一致；篡改导出包校验失败；
- 哈希链损坏快速失败 + `truncateAfterLastValid` 恢复。

## 代码地图

- `gsb/mask/`：`Mask` 栅格、`MrleCodec`（压缩格式）、`PngCodec`（PNG 互换）。
- `gsb/geo/`：`Geometry`（方向/间距/重采样）、`Contour`（marching-squares）、`Components`（连通域）、`RasterOps`（多边形⇄像素）、`Poly`。
- `gsb/analysis/`：`Analysis`（逐像素对账、区域、边界哈希、规则版本）。
- `gsb/store/`：`EventLog`（哈希链）、`EvidenceStore`（不可变证据）、`Model`/`Projector`（事件投影）、`Workspace`/`WorkspaceStore`、`ApiException`（可读诊断）。
- `gsb/service/`：`IngestService`、`MappingService`、`AnalysisService`、`DecisionService`（并发/撤销）、`ReleaseService`、`ExportService`/`ImportService`、`TileService`。
- `gsb/web/`：JDK HttpServer 路由、`Multipart`、静态前端。
- `src/main/resources/static/`：零依赖单页（`index.html`/`styles.css`/`core.js`/`app.js`，含浏览器侧 SHA-256 与 MRLE1 编码）。
